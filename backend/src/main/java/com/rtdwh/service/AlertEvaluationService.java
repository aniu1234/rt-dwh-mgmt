package com.rtdwh.service;

import com.rtdwh.entity.AlertRecord;
import com.rtdwh.entity.AlertRule;
import com.rtdwh.entity.QualityAlert;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.repository.AlertRecordRepository;
import com.rtdwh.repository.AlertRuleRepository;
import com.rtdwh.repository.QualityAlertRepository;
import com.rtdwh.repository.SyncTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertEvaluationService {
    private final AlertRuleRepository ruleRepository;
    private final AlertRecordRepository recordRepository;
    private final SyncTaskRepository taskRepository;
    private final QualityAlertRepository qualityAlertRepository;
    private final AlertNotifyService notifyService;

    @Transactional
    public EvaluationSummary evaluateAll() {
        int evaluated = 0;
        int triggered = 0;
        int recovered = 0;
        for (AlertRule rule : ruleRepository.findByEnabledTrueOrderByIdAsc()) {
            try {
                EvaluationSummary result = evaluate(rule);
                evaluated++;
                triggered += result.triggered();
                recovered += result.recovered();
            } catch (Exception exception) {
                log.warn("Alert rule evaluation failed: ruleId={}, error={}", rule.getId(), exception.getMessage());
            }
        }
        return new EvaluationSummary(evaluated, triggered, recovered);
    }

    @Transactional
    public EvaluationSummary evaluateRule(Long ruleId) {
        AlertRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("告警规则不存在: " + ruleId));
        if (!Boolean.TRUE.equals(rule.getEnabled())) return new EvaluationSummary(0, 0, 0);
        return evaluate(rule);
    }

    private EvaluationSummary evaluate(AlertRule rule) {
        List<Condition> conditions = activeConditions(rule);
        Set<String> activeKeys = new HashSet<>();
        int triggered = 0;
        LocalDateTime now = LocalDateTime.now();

        for (Condition condition : conditions) {
            activeKeys.add(condition.key());
            AlertRecord existing = recordRepository
                    .findFirstByRuleIdAndDedupKeyAndResolvedFalseOrderByTriggeredAtDesc(rule.getId(), condition.key())
                    .orElse(null);
            if (existing != null) {
                existing.setLastEvaluatedAt(now);
                recordRepository.save(existing);
                continue;
            }
            AlertRecord record = new AlertRecord();
            record.setRuleId(rule.getId());
            record.setRuleType(rule.getRuleType());
            record.setDedupKey(condition.key());
            record.setMessage(condition.message());
            record.setLevel(condition.level());
            record.setResolved(false);
            record.setTriggeredAt(now);
            record.setLastEvaluatedAt(now);
            record.setNotificationStatus("pending");
            record = recordRepository.save(record);
            boolean sent = notifyService.sendAlertWithResult(rule, condition.message(), condition.level());
            record.setNotificationStatus(sent ? "sent" : "skipped");
            recordRepository.save(record);
            triggered++;
        }

        int recovered = 0;
        for (AlertRecord open : recordRepository.findByRuleIdAndResolvedFalse(rule.getId())) {
            if (activeKeys.contains(open.getDedupKey())) continue;
            open.setResolved(true);
            open.setResolvedAt(now);
            open.setRecoveredAt(now);
            open.setLastEvaluatedAt(now);
            recordRepository.save(open);
            notifyService.sendAlertWithResult(rule, "告警已恢复：" + open.getMessage(), "info");
            recovered++;
        }
        return new EvaluationSummary(1, triggered, recovered);
    }

    private List<Condition> activeConditions(AlertRule rule) {
        return switch (rule.getRuleType()) {
            case "task_failure" -> failedTasks();
            case "data_delay" -> delayedTasks(AlertRuleExpressionParser.delayThresholdMs(rule.getExpression()));
            case "quality_failure" -> unresolvedQualityAlerts();
            default -> throw new IllegalArgumentException("不支持的告警类型: " + rule.getRuleType());
        };
    }

    private List<Condition> failedTasks() {
        return taskRepository.findByStatus(SyncTask.TaskStatus.failed).stream()
                .map(task -> new Condition("task:" + task.getId(),
                        "同步任务失败：" + task.getTaskName() + "；原因："
                                + defaultText(task.getLastErrorMsg(), "请查看 Flink 日志"), "error"))
                .toList();
    }

    private List<Condition> delayedTasks(long thresholdMs) {
        List<Condition> conditions = new ArrayList<>();
        for (SyncTask task : taskRepository.findByStatus(SyncTask.TaskStatus.running)) {
            long lag = task.getCurrentLagMs() == null ? 0L : task.getCurrentLagMs();
            if (lag > thresholdMs) {
                conditions.add(new Condition("task:" + task.getId(),
                        "数据延迟超限：" + task.getTaskName() + "，当前 " + lag
                                + "ms，阈值 " + thresholdMs + "ms", "warn"));
            }
        }
        return conditions;
    }

    private List<Condition> unresolvedQualityAlerts() {
        return qualityAlertRepository.findByResolvedFalseOrderByTriggeredAtDesc().stream()
                .map(this::qualityCondition)
                .toList();
    }

    private Condition qualityCondition(QualityAlert alert) {
        String table = defaultText(alert.getTargetTable(), "未知表");
        return new Condition("quality:" + alert.getId(),
                "数据质量异常：" + table + "；" + defaultText(alert.getMessage(), "质量规则未通过"),
                defaultText(alert.getLevel(), "warn"));
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record Condition(String key, String message, String level) {
    }

    public record EvaluationSummary(int evaluated, int triggered, int recovered) {
    }
}
