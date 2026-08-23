package com.rtdwh.service;

import com.rtdwh.entity.QualityCheckRun;
import com.rtdwh.entity.QualityRule;
import com.rtdwh.repository.QualityCheckRunRepository;
import com.rtdwh.repository.QualityRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityCheckServiceTest {
    private final QualityRuleRepository ruleRepository = mock(QualityRuleRepository.class);
    private final QualityCheckRunRepository runRepository = mock(QualityCheckRunRepository.class);
    private final QualityCheckPersistenceService persistenceService = mock(QualityCheckPersistenceService.class);
    private final DorisConnectionService doris = mock(DorisConnectionService.class);
    private final QualityCheckService service = new QualityCheckService(
            ruleRepository, runRepository, persistenceService, doris);

    @BeforeEach
    void stubRunPersistence() {
        when(persistenceService.startRun(any(QualityCheckRun.class)))
                .thenAnswer(invocation -> {
                    QualityCheckRun run = invocation.getArgument(0);
                    run.setId(100L);
                    return run;
                });
        when(persistenceService.completeRun(any(QualityRule.class), any(QualityCheckRun.class),
                nullable(String.class), nullable(String.class)))
                .thenReturn(false);
    }

    @Test
    void generatesFullyQualifiedDorisQualitySql() {
        when(doris.getCatalog()).thenReturn("rtdwh_paimon");
        QualityRule rule = QualityRule.builder().ruleType("null_rate").layer("ods")
                .targetTable("ods_users").targetColumn("user_id").threshold(0.05).build();

        assertEquals(
                "SELECT COALESCE(CAST(SUM(CASE WHEN `user_id` IS NULL THEN 1 ELSE 0 END) AS DOUBLE) "
                        + "/ NULLIF(COUNT(*), 0), 0.0) FROM `rtdwh_paimon`.`ods`.`ods_users`",
                service.generateCheckSql(rule));
    }

    @Test
    void definesConsistentEmptyTableSemanticsForRatioRules() {
        when(doris.getCatalog()).thenReturn("rtdwh_paimon");
        QualityRule uniqueness = QualityRule.builder().ruleType("uniqueness").layer("dwd")
                .targetTable("users").targetColumn("user_id").threshold(1.0).build();
        QualityRule range = QualityRule.builder().ruleType("range_check").layer("dwd")
                .targetTable("users").targetColumn("age").threshold(0.01)
                .expression("age >= 0 AND age <= 150").build();

        assertEquals("SELECT COALESCE(CAST(COUNT(DISTINCT `user_id`) AS DOUBLE) / NULLIF(COUNT(*), 0), 1.0) "
                        + "FROM `rtdwh_paimon`.`dwd`.`users`", service.generateCheckSql(uniqueness));
        assertEquals("SELECT COALESCE(CAST(SUM(CASE WHEN NOT (age >= 0 AND age <= 150) THEN 1 ELSE 0 END) "
                        + "AS DOUBLE) / NULLIF(COUNT(*), 0), 0.0) FROM `rtdwh_paimon`.`dwd`.`users`",
                service.generateCheckSql(range));
    }

    @Test
    void rejectsUnsafeRangeExpression() {
        when(doris.getCatalog()).thenReturn("rtdwh_paimon");
        QualityRule rule = QualityRule.builder().ruleType("range_check").layer("ods")
                .targetTable("orders").targetColumn("amount").threshold(0.01)
                .expression("amount > 0; DROP TABLE orders").build();

        assertThrows(IllegalArgumentException.class, () -> service.generateCheckSql(rule));
    }

    @Test
    void recordsSuccessfulDorisCheck() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        QualityRule rule = QualityRule.builder().id(9L).ruleName("用户ID完整性")
                .ruleType("null_rate").layer("ods").targetTable("ods_users")
                .targetColumn("user_id").threshold(0.05).enabled(true).build();
        when(ruleRepository.findById(9L)).thenReturn(Optional.of(rule));
        when(doris.getCatalog()).thenReturn("rtdwh_paimon");
        when(doris.getDatabase()).thenReturn("ods");
        when(doris.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(any(String.class))).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getDouble(1)).thenReturn(0.01);

        assertEquals(0, service.runCheck(9L));

        verify(statement).execute("SWITCH `rtdwh_paimon`");
        var runCaptor = org.mockito.ArgumentCaptor.forClass(QualityCheckRun.class);
        verify(persistenceService).completeRun(eq(rule), runCaptor.capture(), isNull(), isNull());
        assertEquals("passed", runCaptor.getValue().getStatus());
    }

    @Test
    void recordsSqlGenerationFailureAndReturnsErrorSummary() throws Exception {
        QualityRule rule = QualityRule.builder().id(10L).ruleName("缺少字段")
                .ruleType("null_rate").layer("ods").targetTable("ods_users")
                .threshold(0.05).enabled(true).build();
        when(ruleRepository.findById(10L)).thenReturn(Optional.of(rule));
        when(doris.getCatalog()).thenReturn("rtdwh_paimon");

        var summary = service.runCheckWithSummary(10L);

        assertEquals(1, summary.total());
        assertEquals(1, summary.errorCount());
        assertEquals(1, summary.abnormalCount());
        var runCaptor = org.mockito.ArgumentCaptor.forClass(QualityCheckRun.class);
        verify(persistenceService).completeRun(eq(rule), runCaptor.capture(), eq("error"), contains("目标字段"));
        QualityCheckRun run = runCaptor.getValue();
        assertEquals("error", run.getStatus());
        assertEquals("null_rate", run.getRuleType());
        assertEquals("ods_users", run.getTargetTable());
        assertNotNull(run.getErrorMessage());
        assertNotNull(run.getFinishedAt());
        verify(doris, never()).getConnection();
    }

    @Test
    void passingCheckAutomaticallyResolvesOpenAlert() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        QualityRule rule = QualityRule.builder().id(11L).ruleName("用户ID完整性")
                .ruleType("null_rate").layer("ods").targetTable("ods_users")
                .targetColumn("user_id").threshold(0.05).enabled(true).build();
        when(ruleRepository.findById(11L)).thenReturn(Optional.of(rule));
        when(doris.getCatalog()).thenReturn("rtdwh_paimon");
        when(doris.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(any(String.class))).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getDouble(1)).thenReturn(0.01);

        assertEquals(0, service.runCheck(11L));

        verify(persistenceService).completeRun(eq(rule), any(QualityCheckRun.class), isNull(), isNull());
    }

    @Test
    void atomicCompletionFailureReturnsAnInfrastructureError() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        QualityRule rule = QualityRule.builder().id(12L).ruleName("用户ID完整性")
                .ruleType("null_rate").layer("ods").targetTable("ods_users")
                .targetColumn("user_id").threshold(0.05).enabled(true).build();
        when(ruleRepository.findById(12L)).thenReturn(Optional.of(rule));
        when(doris.getCatalog()).thenReturn("rtdwh_paimon");
        when(doris.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(any(String.class))).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getDouble(1)).thenReturn(0.20);
        when(persistenceService.completeRun(any(), any(), nullable(String.class), nullable(String.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        var summary = service.runCheckWithSummary(12L);

        var runCaptor = org.mockito.ArgumentCaptor.forClass(QualityCheckRun.class);
        verify(persistenceService).completeRun(eq(rule), runCaptor.capture(), eq("error"), contains("质量检查异常"));
        assertEquals("failed", runCaptor.getValue().getStatus());
        assertEquals(0, summary.failed());
        assertEquals(1, summary.errorCount());
    }

    @Test
    void runFinalizationFailureBlocksTheCheckAsInfrastructureError() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        QualityRule rule = QualityRule.builder().id(16L).ruleName("用户ID完整性")
                .ruleType("null_rate").layer("ods").targetTable("ods_users")
                .targetColumn("user_id").threshold(0.05).enabled(true).build();
        when(ruleRepository.findById(16L)).thenReturn(Optional.of(rule));
        when(doris.getCatalog()).thenReturn("rtdwh_paimon");
        when(doris.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(any(String.class))).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getDouble(1)).thenReturn(0.01);
        when(persistenceService.completeRun(any(), any(), nullable(String.class), nullable(String.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        var summary = service.runCheckWithSummary(16L);

        assertEquals(0, summary.passed());
        assertEquals(1, summary.errorCount());
        assertEquals(1, summary.abnormalCount());
        verify(persistenceService).completeRun(eq(rule), any(QualityCheckRun.class), isNull(), isNull());
    }

    @Test
    void layerCheckUsesLayerQueryAndSkipsDisabledRules() {
        QualityRule disabled = QualityRule.builder().id(13L).enabled(false).layer("ods").build();
        when(ruleRepository.findByLayer("ods")).thenReturn(List.of(disabled));

        assertEquals(0, service.runChecksByLayer("ods"));

        verify(ruleRepository).findByLayer("ods");
        verify(persistenceService, never()).startRun(any());
    }

    @Test
    void productionCheckRequiresExactCatalogDatabaseAndTable() {
        QualityRule otherCatalog = QualityRule.builder().id(14L).enabled(true).layer("ads")
                .targetTable("another_catalog.ads.daily_sales").build();
        QualityRule otherDatabase = QualityRule.builder().id(15L).enabled(true).layer("ads")
                .targetTable("rtdwh_paimon.dws.daily_sales").build();
        when(ruleRepository.findByEnabled(true)).thenReturn(List.of(otherCatalog, otherDatabase));
        when(doris.getCatalog()).thenReturn("rtdwh_paimon");

        assertEquals(0, service.runChecksForTable("rtdwh_paimon", "ads", "daily_sales"));

        verify(persistenceService, never()).startRun(any());
    }
}
