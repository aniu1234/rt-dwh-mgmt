package com.rtdwh.service;

import com.rtdwh.entity.AlertRecord;
import com.rtdwh.entity.AlertRule;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.repository.AlertRecordRepository;
import com.rtdwh.repository.AlertRuleRepository;
import com.rtdwh.repository.QualityAlertRepository;
import com.rtdwh.repository.SyncTaskRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertEvaluationServiceTest {
    private final AlertRuleRepository ruleRepository = mock(AlertRuleRepository.class);
    private final AlertRecordRepository recordRepository = mock(AlertRecordRepository.class);
    private final SyncTaskRepository taskRepository = mock(SyncTaskRepository.class);
    private final QualityAlertRepository qualityAlertRepository = mock(QualityAlertRepository.class);
    private final AlertNotifyService notifyService = mock(AlertNotifyService.class);
    private final AlertEvaluationService service = new AlertEvaluationService(
            ruleRepository, recordRepository, taskRepository, qualityAlertRepository, notifyService);

    @Test
    void parsesDelayUnitsAndRejectsInvalidExpression() {
        assertEquals(5_000L, AlertRuleExpressionParser.delayThresholdMs("5s"));
        assertEquals(120_000L, AlertRuleExpressionParser.delayThresholdMs("2m"));
        assertEquals(6_000L, AlertRuleExpressionParser.delayThresholdMs("lag_ms > 6000"));
        assertThrows(IllegalArgumentException.class,
                () -> AlertRuleExpressionParser.delayThresholdMs("lag > now()"));
    }

    @Test
    void createsOneDeduplicatedDelayAlert() {
        AlertRule rule = rule(1L, "data_delay", "5s");
        SyncTask task = SyncTask.builder().id(8L).taskName("orders-cdc")
                .status(SyncTask.TaskStatus.running).currentLagMs(7_500L).build();
        when(ruleRepository.findByEnabledTrueOrderByIdAsc()).thenReturn(List.of(rule));
        when(taskRepository.findByStatus(SyncTask.TaskStatus.running)).thenReturn(List.of(task));
        when(recordRepository.findFirstByRuleIdAndDedupKeyAndResolvedFalseOrderByTriggeredAtDesc(1L, "task:8"))
                .thenReturn(Optional.empty());
        when(recordRepository.findByRuleIdAndResolvedFalse(1L)).thenReturn(List.of());
        when(recordRepository.save(any(AlertRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notifyService.sendAlertWithResult(any(), any(), any())).thenReturn(true);

        AlertEvaluationService.EvaluationSummary summary = service.evaluateAll();

        assertEquals(1, summary.triggered());
        verify(notifyService).sendAlertWithResult(rule,
                "数据延迟超限：orders-cdc，当前 7500ms，阈值 5000ms", "warn");
    }

    @Test
    void doesNotNotifyAgainWhileConditionRemainsOpen() {
        AlertRule rule = rule(1L, "data_delay", "5000ms");
        SyncTask task = SyncTask.builder().id(8L).taskName("orders-cdc")
                .status(SyncTask.TaskStatus.running).currentLagMs(7_500L).build();
        AlertRecord existing = new AlertRecord();
        existing.setRuleId(1L);
        existing.setDedupKey("task:8");
        existing.setResolved(false);
        when(ruleRepository.findByEnabledTrueOrderByIdAsc()).thenReturn(List.of(rule));
        when(taskRepository.findByStatus(SyncTask.TaskStatus.running)).thenReturn(List.of(task));
        when(recordRepository.findFirstByRuleIdAndDedupKeyAndResolvedFalseOrderByTriggeredAtDesc(1L, "task:8"))
                .thenReturn(Optional.of(existing));
        when(recordRepository.findByRuleIdAndResolvedFalse(1L)).thenReturn(List.of(existing));

        AlertEvaluationService.EvaluationSummary summary = service.evaluateAll();

        assertEquals(0, summary.triggered());
        verify(notifyService, never()).sendAlertWithResult(any(), any(), any());
    }

    @Test
    void resolvesAndNotifiesWhenConditionRecovers() {
        AlertRule rule = rule(1L, "task_failure", "");
        AlertRecord open = new AlertRecord();
        open.setRuleId(1L);
        open.setDedupKey("task:9");
        open.setMessage("同步任务失败：old-task");
        open.setResolved(false);
        when(ruleRepository.findByEnabledTrueOrderByIdAsc()).thenReturn(List.of(rule));
        when(taskRepository.findByStatus(SyncTask.TaskStatus.failed)).thenReturn(List.of());
        when(recordRepository.findByRuleIdAndResolvedFalse(1L)).thenReturn(List.of(open));

        AlertEvaluationService.EvaluationSummary summary = service.evaluateAll();

        assertEquals(1, summary.recovered());
        assertTrue(open.getResolved());
        verify(notifyService).sendAlertWithResult(rule, "告警已恢复：同步任务失败：old-task", "info");
    }

    private AlertRule rule(Long id, String type, String expression) {
        AlertRule rule = new AlertRule();
        rule.setId(id);
        rule.setRuleName("test-rule");
        rule.setRuleType(type);
        rule.setExpression(expression);
        rule.setNotifyChannel("dingtalk");
        rule.setEnabled(true);
        return rule;
    }
}
