package com.rtdwh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.entity.TaskRunInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowSqlRunnerService {
    private static final Pattern PARAMETER_PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");

    private final WorkflowService workflowService;
    private final FlinkClusterService flinkClusterService;
    private final ObjectMapper objectMapper;

    @Value("${workflow.runner.executor-id:internal-flink-sql}")
    private String executorId = "internal-flink-sql";

    @Value("${workflow.runner.max-concurrent:2}")
    private int maxConcurrent = 2;

    /**
     * Reconcile existing jobs before reclaiming leases. This avoids resubmitting a
     * Flink job that kept running while the management process was temporarily down.
     */
    public RunCycleSummary runCycle() {
        int completed = 0;
        int retried = 0;
        List<TaskRunInstance> running = workflowService.runningByExecutor(executorId, 200);
        for (TaskRunInstance instance : running) {
            ReconcileResult result = reconcile(instance);
            completed += result.completed();
            retried += result.retried();
        }

        int recovered = workflowService.recoverExpiredInstances();
        int active = workflowService.runningByExecutor(executorId, 200).size();
        int submitted = 0;
        int capacity = Math.max(0, maxConcurrent - active);
        for (int slot = 0; slot < capacity; slot++) {
            var claimed = workflowService.claim(executorId);
            if (claimed.isEmpty()) break;
            if (submit(claimed.get())) submitted++;
        }
        return new RunCycleSummary(submitted, completed, retried, recovered);
    }

    public TaskRunInstance cancel(Long instanceId) {
        TaskRunInstance instance = workflowService.getInstance(instanceId);
        if (instance.getExternalJobId() != null && !instance.getExternalJobId().isBlank()) {
            flinkClusterService.cancelJob(instance.getExternalJobId());
        }
        return workflowService.cancel(instanceId);
    }

    private boolean submit(TaskRunInstance instance) {
        try {
            SyncTask definition = workflowService.taskForInstance(instance);
            if (definition.getTaskType() == SyncTask.TaskType.cdc_sync) {
                workflowService.complete(instance.getId(), false, "CDC 长流任务不能作为工作流补数实例执行");
                return false;
            }
            String sql = renderSql(definition.getFlinkSql(), instance, objectMapper);
            SyncTask executable = SyncTask.builder()
                    .id(definition.getId())
                    .taskName(definition.getTaskName() + "@" + instance.getBusinessDate())
                    .taskType(definition.getTaskType())
                    .sourceConfigId(definition.getSourceConfigId())
                    .targetConfigId(definition.getTargetConfigId())
                    .flinkSql(sql)
                    .parallelism(definition.getParallelism())
                    .checkpointIntervalMs(definition.getCheckpointIntervalMs())
                    .build();
            Map<String, Object> result = flinkClusterService.submitViaSqlGateway(executable);
            Object jobId = result.get("jobId");
            if (jobId == null || jobId.toString().isBlank()) {
                throw new IllegalStateException("SQL Gateway 未返回 Flink Job ID");
            }
            workflowService.attachExternalJob(instance.getId(), executorId, jobId.toString());
            log.info("Workflow instance {} submitted as Flink job {}", instance.getId(), jobId);
            return true;
        } catch (IllegalArgumentException configurationError) {
            workflowService.complete(instance.getId(), false, configurationError.getMessage());
            log.warn("Workflow instance {} has invalid SQL configuration: {}",
                    instance.getId(), configurationError.getMessage());
            return false;
        } catch (Exception submissionError) {
            workflowService.failOrRetry(instance.getId(), submissionError.getMessage());
            log.warn("Workflow instance {} submission failed: {}",
                    instance.getId(), submissionError.getMessage());
            return false;
        }
    }

    private ReconcileResult reconcile(TaskRunInstance instance) {
        if (instance.getExternalJobId() == null || instance.getExternalJobId().isBlank()) {
            workflowService.failOrRetry(instance.getId(), "运行实例缺少 Flink Job ID");
            return new ReconcileResult(0, 1);
        }
        try {
            Map<String, Object> status = flinkClusterService.getJobStatus(instance.getExternalJobId());
            String flinkState = String.valueOf(status.getOrDefault("flinkState", ""))
                    .toUpperCase(Locale.ROOT);
            String mappedStatus = String.valueOf(status.getOrDefault("status", ""))
                    .toUpperCase(Locale.ROOT);
            if ("FINISHED".equals(flinkState)) {
                workflowService.complete(instance.getId(), true, null);
                return new ReconcileResult(1, 0);
            }
            if ("FAILED".equals(flinkState) || "CANCELED".equals(flinkState)
                    || "FAILED".equals(mappedStatus) || "NOT_FOUND".equals(mappedStatus)) {
                workflowService.failOrRetry(instance.getId(),
                        "Flink Job 已终止，状态: " + (flinkState.isBlank() ? mappedStatus : flinkState));
                return new ReconcileResult(0, 1);
            }

            // RUNNING/CREATED/RESTARTING and temporary UNREACHABLE all retain
            // ownership. Retrying on a network partition could create duplicate writes.
            workflowService.heartbeat(instance.getId(), executorId);
            return new ReconcileResult(0, 0);
        } catch (Exception exception) {
            workflowService.heartbeat(instance.getId(), executorId);
            log.warn("Unable to reconcile workflow instance {}: {}",
                    instance.getId(), exception.getMessage());
            return new ReconcileResult(0, 0);
        }
    }

    static String renderSql(String sql, TaskRunInstance instance, ObjectMapper objectMapper) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("任务未配置 Flink SQL");
        }
        String rendered = sql.replace("${bizdate}", instance.getBusinessDate().toString());
        try {
            JsonNode parameters = objectMapper.readTree(
                    instance.getParametersJson() == null ? "{}" : instance.getParametersJson());
            if (!parameters.isObject()) {
                throw new IllegalArgumentException("运行参数必须是 JSON 对象");
            }
            var fields = parameters.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!field.getValue().isValueNode()) {
                    throw new IllegalArgumentException("运行参数只支持字符串、数字和布尔值: " + field.getKey());
                }
                rendered = rendered.replace("${" + field.getKey() + "}", field.getValue().asText());
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("运行参数 JSON 格式不正确");
        }

        Matcher unresolved = PARAMETER_PATTERN.matcher(rendered);
        if (unresolved.find()) {
            throw new IllegalArgumentException("Flink SQL 存在未赋值参数: " + unresolved.group(1));
        }
        return rendered;
    }

    private record ReconcileResult(int completed, int retried) {}

    public record RunCycleSummary(int submitted, int completed, int retried, int recovered) {}
}
