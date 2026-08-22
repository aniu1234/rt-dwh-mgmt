package com.rtdwh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.dto.WorkflowDTO;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.entity.TaskDefinitionVersion;
import com.rtdwh.entity.TaskDependency;
import com.rtdwh.entity.TaskRunInstance;
import com.rtdwh.entity.TaskRunInstance.RunStatus;
import com.rtdwh.repository.SyncTaskRepository;
import com.rtdwh.repository.TaskDefinitionVersionRepository;
import com.rtdwh.repository.TaskDependencyRepository;
import com.rtdwh.repository.TaskRunInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
public class WorkflowService {
    private final SyncTaskRepository taskRepository;
    private final TaskDependencyRepository dependencyRepository;
    private final TaskDefinitionVersionRepository versionRepository;
    private final TaskRunInstanceRepository instanceRepository;
    private final ObjectMapper objectMapper;
    private final DatasetProductionService datasetProductionService;

    @Value("${workflow.runner.lease-seconds:60}")
    private long leaseSeconds = 60;

    @Value("${workflow.runner.max-retries:3}")
    private int maxRetries = 3;

    @Value("${workflow.runner.retry-backoff-seconds:30}")
    private long retryBackoffSeconds = 30;

    public Map<String, Object> graph() {
        List<SyncTask> tasks = taskRepository.findAll();
        List<TaskDependency> edges = dependencyRepository.findAllByOrderByIdAsc();
        return Map.of("tasks", tasks, "dependencies", edges);
    }

    @Transactional
    public TaskDependency addDependency(Long upstreamId, Long downstreamId, Long userId) {
        if (Objects.equals(upstreamId, downstreamId)) {
            throw new IllegalArgumentException("任务不能依赖自身");
        }
        requireTask(upstreamId);
        requireTask(downstreamId);
        if (dependencyRepository.existsByUpstreamTaskIdAndDownstreamTaskId(upstreamId, downstreamId)) {
            throw new IllegalStateException("该依赖关系已存在");
        }
        if (canReach(downstreamId, upstreamId)) {
            throw new IllegalArgumentException("新增依赖会形成环路");
        }
        return dependencyRepository.save(TaskDependency.builder()
                .upstreamTaskId(upstreamId)
                .downstreamTaskId(downstreamId)
                .conditionType("success")
                .createdAt(LocalDateTime.now())
                .createdBy(userId)
                .build());
    }

    @Transactional
    public void removeDependency(Long upstreamId, Long downstreamId) {
        dependencyRepository.deleteByUpstreamTaskIdAndDownstreamTaskId(upstreamId, downstreamId);
    }

    @Transactional
    public TaskDefinitionVersion publish(Long taskId, String summary, Long userId) {
        SyncTask task = requireTask(taskId);
        int nextVersion = versionRepository.findFirstByTaskIdOrderByVersionNoDesc(taskId)
                .map(item -> item.getVersionNo() + 1).orElse(1);
        try {
            return versionRepository.save(TaskDefinitionVersion.builder()
                    .taskId(taskId)
                    .versionNo(nextVersion)
                    .changeSummary(summary.trim())
                    .snapshotJson(objectMapper.writeValueAsString(task))
                    .createdBy(userId)
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalStateException("任务版本并发冲突，请重试");
        } catch (Exception exception) {
            throw new IllegalStateException("任务版本快照生成失败: " + exception.getMessage());
        }
    }

    public List<TaskDefinitionVersion> versions(Long taskId) {
        requireTask(taskId);
        return versionRepository.findByTaskIdOrderByVersionNoDesc(taskId);
    }

    @Transactional
    public SyncTask rollback(Long taskId, Integer versionNo) {
        SyncTask task = requireTask(taskId);
        if (task.getStatus() != SyncTask.TaskStatus.draft) {
            throw new IllegalStateException("只有 draft 状态任务可以回滚配置");
        }
        TaskDefinitionVersion version = versionRepository.findByTaskIdAndVersionNo(taskId, versionNo)
                .orElseThrow(() -> new IllegalArgumentException("任务版本不存在: " + versionNo));
        try {
            JsonNode snapshot = objectMapper.readTree(version.getSnapshotJson());
            task.setTaskName(text(snapshot, "taskName", task.getTaskName()));
            task.setDescription(nullableText(snapshot, "description"));
            task.setFlinkSql(nullableText(snapshot, "flinkSql"));
            task.setTableMappings(nullableText(snapshot, "tableMappings"));
            task.setParallelism(snapshot.path("parallelism").asInt(task.getParallelism()));
            task.setCheckpointIntervalMs(snapshot.path("checkpointIntervalMs").asLong(task.getCheckpointIntervalMs()));
            return taskRepository.save(task);
        } catch (Exception exception) {
            throw new IllegalStateException("任务版本快照损坏，无法回滚");
        }
    }

    @Transactional
    public List<TaskRunInstance> createBackfill(Long taskId, WorkflowDTO.BackfillRequest request, Long userId) {
        SyncTask task = requireTask(taskId);
        if (task.getTaskType() == SyncTask.TaskType.cdc_sync) {
            throw new IllegalArgumentException("CDC 长流任务不支持按业务日期补数");
        }
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("结束日期不能早于开始日期");
        }
        long days = ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;
        if (days > 366) {
            throw new IllegalArgumentException("单次补数最多创建 366 个实例");
        }
        String batchId = UUID.randomUUID().toString();
        boolean hasUpstream = !dependencyRepository.findByDownstreamTaskId(taskId).isEmpty();
        List<TaskRunInstance> instances = new ArrayList<>();
        for (int offset = 0; offset < days; offset++) {
            LocalDate businessDate = request.getStartDate().plusDays(offset);
            instances.add(TaskRunInstance.builder()
                    .taskId(taskId)
                    .batchId(batchId)
                    .businessDate(businessDate)
                    .triggerType("backfill")
                    .status(hasUpstream ? RunStatus.waiting : RunStatus.queued)
                    .parametersJson(normalizeJson(request.getParametersJson()))
                    .createdBy(userId)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build());
        }
        return instanceRepository.saveAll(instances);
    }

    @Transactional
    public TaskRunInstance createScheduledInstance(Long taskId, Long scheduleId, java.time.Instant scheduledAt,
                                                   LocalDate businessDate, String parametersJson, Long userId) {
        SyncTask task = requireTask(taskId);
        if (task.getTaskType() == SyncTask.TaskType.cdc_sync) throw new IllegalArgumentException("CDC 长流任务不能周期调度");
        boolean hasUpstream = !dependencyRepository.findByDownstreamTaskId(taskId).isEmpty();
        return instanceRepository.save(TaskRunInstance.builder()
                .taskId(taskId)
                .batchId("schedule-" + scheduleId + "-" + scheduledAt.toEpochMilli())
                .businessDate(businessDate)
                .triggerType("schedule")
                .status(hasUpstream ? RunStatus.waiting : RunStatus.queued)
                .parametersJson(normalizeJson(parametersJson))
                .createdBy(userId)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    public List<TaskRunInstance> instances(Long taskId, RunStatus status, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        PageRequest page = PageRequest.of(0, safeLimit);
        if (taskId != null && status != null) {
            return instanceRepository.findByTaskIdAndStatusOrderByCreatedAtDesc(taskId, status, page);
        }
        if (taskId != null) return instanceRepository.findByTaskIdOrderByCreatedAtDesc(taskId, page);
        if (status != null) return instanceRepository.findByStatusOrderByCreatedAtAsc(status, page);
        return instanceRepository.findAll(PageRequest.of(0, safeLimit,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"))).getContent();
    }

    @Transactional
    public Optional<TaskRunInstance> claim(String executorId) {
        if (executorId == null || executorId.isBlank()) {
            throw new IllegalArgumentException("executorId 不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        Optional<TaskRunInstance> candidate = instanceRepository.findRunnableForUpdate(
                RunStatus.queued, now, PageRequest.of(0, 1)).stream().findFirst();
        candidate.ifPresent(instance -> {
            instance.setStatus(RunStatus.running);
            instance.setExecutorId(executorId.trim());
            instance.setStartedAt(now);
            instance.setFinishedAt(null);
            instance.setHeartbeatAt(now);
            instance.setLeaseExpiresAt(now.plusSeconds(leaseSeconds));
            instance.setNextRetryAt(null);
            instance.setUpdatedAt(now);
            instanceRepository.save(instance);
        });
        return candidate;
    }

    @Transactional
    public TaskRunInstance attachExternalJob(Long instanceId, String executorId, String externalJobId) {
        TaskRunInstance instance = requireRunningInstance(instanceId, executorId);
        if (externalJobId == null || externalJobId.isBlank()) {
            throw new IllegalArgumentException("externalJobId 不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        instance.setExternalJobId(externalJobId.trim());
        instance.setHeartbeatAt(now);
        instance.setLeaseExpiresAt(now.plusSeconds(leaseSeconds));
        instance.setUpdatedAt(now);
        return instanceRepository.save(instance);
    }

    @Transactional
    public TaskRunInstance heartbeat(Long instanceId, String executorId) {
        TaskRunInstance instance = requireRunningInstance(instanceId, executorId);
        LocalDateTime now = LocalDateTime.now();
        instance.setHeartbeatAt(now);
        instance.setLeaseExpiresAt(now.plusSeconds(leaseSeconds));
        instance.setUpdatedAt(now);
        return instanceRepository.save(instance);
    }

    @Transactional
    public TaskRunInstance complete(Long instanceId, boolean success, String errorMessage) {
        TaskRunInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("运行实例不存在: " + instanceId));
        if (instance.getStatus() != RunStatus.running) {
            throw new IllegalStateException("只有 running 实例可以完成，当前状态: " + instance.getStatus());
        }
        instance.setStatus(success ? RunStatus.success : RunStatus.failed);
        instance.setErrorMessage(success ? null : errorMessage);
        instance.setFinishedAt(LocalDateTime.now());
        instance.setHeartbeatAt(null);
        instance.setLeaseExpiresAt(null);
        instance.setNextRetryAt(null);
        instance.setUpdatedAt(LocalDateTime.now());
        instance = instanceRepository.save(instance);
        if (success) datasetProductionService.recordSuccess(instance);
        return instance;
    }

    @Transactional
    public TaskRunInstance failOrRetry(Long instanceId, String errorMessage) {
        TaskRunInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("运行实例不存在: " + instanceId));
        if (instance.getStatus() != RunStatus.running) {
            return instance;
        }
        int retries = instance.getRetryCount() == null ? 0 : instance.getRetryCount();
        LocalDateTime now = LocalDateTime.now();
        instance.setErrorMessage(trimError(errorMessage));
        instance.setHeartbeatAt(null);
        instance.setLeaseExpiresAt(null);
        instance.setExternalJobId(null);
        instance.setExecutorId(null);
        instance.setUpdatedAt(now);
        if (retries < maxRetries) {
            instance.setRetryCount(retries + 1);
            instance.setStatus(RunStatus.queued);
            instance.setStartedAt(null);
            instance.setFinishedAt(null);
            instance.setNextRetryAt(now.plusSeconds(retryDelaySeconds(retries + 1)));
        } else {
            instance.setStatus(RunStatus.failed);
            instance.setFinishedAt(now);
            instance.setNextRetryAt(null);
        }
        return instanceRepository.save(instance);
    }

    @Transactional
    public TaskRunInstance retryFailed(Long instanceId) {
        TaskRunInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("运行实例不存在: " + instanceId));
        if (instance.getStatus() != RunStatus.failed) {
            throw new IllegalStateException("只有 failed 实例可以重试，当前状态: " + instance.getStatus());
        }
        instance.setStatus(RunStatus.queued);
        instance.setRetryCount(0);
        instance.setErrorMessage(null);
        instance.setExecutorId(null);
        instance.setExternalJobId(null);
        instance.setStartedAt(null);
        instance.setFinishedAt(null);
        instance.setHeartbeatAt(null);
        instance.setLeaseExpiresAt(null);
        instance.setNextRetryAt(LocalDateTime.now());
        instance.setUpdatedAt(LocalDateTime.now());
        return instanceRepository.save(instance);
    }

    @Transactional
    public TaskRunInstance cancel(Long instanceId) {
        TaskRunInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("运行实例不存在: " + instanceId));
        if (Set.of(RunStatus.success, RunStatus.failed, RunStatus.cancelled).contains(instance.getStatus())) {
            throw new IllegalStateException("终态实例不能取消，当前状态: " + instance.getStatus());
        }
        instance.setStatus(RunStatus.cancelled);
        instance.setFinishedAt(LocalDateTime.now());
        instance.setHeartbeatAt(null);
        instance.setLeaseExpiresAt(null);
        instance.setNextRetryAt(null);
        instance.setUpdatedAt(LocalDateTime.now());
        return instanceRepository.save(instance);
    }

    @Transactional
    public int recoverExpiredInstances() {
        LocalDateTime now = LocalDateTime.now();
        List<TaskRunInstance> expired = instanceRepository
                .findByStatusAndLeaseExpiresAtBeforeOrderByLeaseExpiresAtAsc(
                        RunStatus.running, now, PageRequest.of(0, 200));
        for (TaskRunInstance instance : expired) {
            failOrRetry(instance.getId(), "执行器租约超时，最后心跳: " + instance.getHeartbeatAt());
        }
        return expired.size();
    }

    public List<TaskRunInstance> runningByExecutor(String executorId, int limit) {
        return instanceRepository.findByStatusAndExecutorIdOrderByUpdatedAtAsc(
                RunStatus.running, executorId, PageRequest.of(0, Math.max(1, Math.min(limit, 200))));
    }

    public SyncTask taskForInstance(TaskRunInstance instance) {
        return requireTask(instance.getTaskId());
    }

    public TaskRunInstance getInstance(Long instanceId) {
        return instanceRepository.findById(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("运行实例不存在: " + instanceId));
    }

    public SyncTask getTask(Long taskId) { return requireTask(taskId); }

    @Transactional
    public int promoteReadyInstances() {
        List<TaskRunInstance> waiting = instanceRepository.findByStatusOrderByCreatedAtAsc(
                RunStatus.waiting, PageRequest.of(0, 200));
        int promoted = 0;
        for (TaskRunInstance instance : waiting) {
            List<TaskDependency> dependencies = dependencyRepository.findByDownstreamTaskId(instance.getTaskId());
            boolean ready = dependencies.stream().allMatch(dependency -> instanceRepository
                    .findFirstByTaskIdAndBusinessDateAndStatusOrderByCreatedAtDesc(
                            dependency.getUpstreamTaskId(), instance.getBusinessDate(), RunStatus.success)
                    .isPresent());
            if (ready) {
                instance.setStatus(RunStatus.queued);
                instance.setUpdatedAt(LocalDateTime.now());
                instanceRepository.save(instance);
                promoted++;
            }
        }
        return promoted;
    }

    private boolean canReach(Long start, Long target) {
        Map<Long, List<Long>> adjacency = new HashMap<>();
        for (TaskDependency dependency : dependencyRepository.findAll()) {
            adjacency.computeIfAbsent(dependency.getUpstreamTaskId(), ignored -> new ArrayList<>())
                    .add(dependency.getDownstreamTaskId());
        }
        Deque<Long> pending = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            Long current = pending.removeFirst();
            if (Objects.equals(current, target)) return true;
            if (visited.add(current)) pending.addAll(adjacency.getOrDefault(current, List.of()));
        }
        return false;
    }

    private SyncTask requireTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
    }

    private TaskRunInstance requireRunningInstance(Long instanceId, String executorId) {
        TaskRunInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("运行实例不存在: " + instanceId));
        if (instance.getStatus() != RunStatus.running) {
            throw new IllegalStateException("实例不是 running 状态: " + instance.getStatus());
        }
        if (executorId == null || !executorId.trim().equals(instance.getExecutorId())) {
            throw new IllegalStateException("实例已由其他执行器持有");
        }
        return instance;
    }

    private long retryDelaySeconds(int retryNumber) {
        long multiplier = 1L << Math.min(Math.max(retryNumber - 1, 0), 10);
        return Math.max(1, retryBackoffSeconds) * multiplier;
    }

    private String trimError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) return "任务执行失败";
        String value = errorMessage.trim();
        return value.length() > 4000 ? value.substring(0, 4000) : value;
    }

    private String normalizeJson(String json) {
        if (json == null || json.isBlank()) return "{}";
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) throw new IllegalArgumentException("补数参数必须是 JSON 对象");
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("补数参数 JSON 格式不正确");
        }
    }

    private String text(JsonNode node, String field, String fallback) {
        String value = nullableText(node, field);
        return value == null ? fallback : value;
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
