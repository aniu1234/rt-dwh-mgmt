package com.rtdwh.service;

import com.rtdwh.entity.QualityRule;
import com.rtdwh.entity.QualityAlert;
import com.rtdwh.repository.QualityAlertRepository;
import com.rtdwh.repository.QualityRuleRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class QualityServiceTest {
    private final QualityRuleRepository ruleRepository = mock(QualityRuleRepository.class);
    private final QualityAlertRepository alertRepository = mock(QualityAlertRepository.class);
    private final QualityCheckPersistenceService persistenceService = mock(QualityCheckPersistenceService.class);
    private final QualityService service = new QualityService(ruleRepository, alertRepository, persistenceService);

    @Test
    void normalizesVolumeRuleAndClearsUnusedFields() {
        when(ruleRepository.save(any(QualityRule.class))).thenAnswer(invocation -> invocation.getArgument(0));
        QualityRule input = QualityRule.builder()
                .ruleName("  ODS 数据量  ")
                .ruleType("VOLUME_COMPARE")
                .layer("ODS")
                .targetTable("ods.orders")
                .targetColumn("unused")
                .expression("unused > 0")
                .threshold(1000.0)
                .build();

        QualityRule saved = service.createRule(input);

        assertEquals("ODS 数据量", saved.getRuleName());
        assertEquals("volume_compare", saved.getRuleType());
        assertEquals("ods", saved.getLayer());
        assertNull(saved.getTargetColumn());
        assertNull(saved.getExpression());
        assertTrue(saved.getEnabled());
    }

    @Test
    void rejectsRatioThresholdOutsideZeroToOne() {
        QualityRule input = baseRule("null_rate");
        input.setThreshold(1.01);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createRule(input));

        assertEquals("比率阈值必须在 0 到 1 之间", error.getMessage());
    }

    @Test
    void requiresSafeExpressionForRangeCheck() {
        QualityRule missing = baseRule("range_check");
        missing.setExpression(null);
        assertThrows(IllegalArgumentException.class, () -> service.createRule(missing));

        QualityRule unsafe = baseRule("range_check");
        unsafe.setExpression("amount >= 0; DROP TABLE orders");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createRule(unsafe));
        assertEquals("范围检查表达式包含不安全内容", error.getMessage());
    }

    @Test
    void rejectsInvalidLayerAndTableIdentifier() {
        QualityRule invalidLayer = baseRule("uniqueness");
        invalidLayer.setLayer("stg");
        assertThrows(IllegalArgumentException.class, () -> service.createRule(invalidLayer));

        QualityRule invalidTable = baseRule("uniqueness");
        invalidTable.setTargetTable("ods.orders;drop");
        assertThrows(IllegalArgumentException.class, () -> service.createRule(invalidTable));
    }

    @Test
    void disablingRuleSuppressesItsOpenQualityAlerts() {
        QualityRule rule = baseRule("null_rate");
        rule.setId(9L);
        rule.setVersion(0L);
        QualityAlert open = QualityAlert.builder().id(90L).ruleId(9L).resolved(false).build();
        when(ruleRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(rule));
        when(alertRepository.findByRuleIdAndResolvedFalseOrderByTriggeredAtDesc(9L))
                .thenReturn(List.of(open));
        when(ruleRepository.save(any(QualityRule.class))).thenAnswer(invocation -> invocation.getArgument(0));

        QualityRule result = service.setRuleEnabled(9L, false);

        assertFalse(result.getEnabled());
        assertTrue(open.getResolved());
        assertEquals("suppressed", open.getResolutionReason());
        verify(alertRepository).saveAll(List.of(open));
    }

    private QualityRule baseRule(String ruleType) {
        return QualityRule.builder()
                .ruleName("订单质量")
                .ruleType(ruleType)
                .layer("ods")
                .targetTable("orders")
                .targetColumn("order_id")
                .threshold(0.05)
                .expression("amount >= 0")
                .enabled(true)
                .build();
    }
}
