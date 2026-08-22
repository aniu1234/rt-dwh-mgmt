package com.rtdwh.service;

import com.rtdwh.entity.QualityCheckRun;
import com.rtdwh.entity.QualityRule;
import com.rtdwh.repository.QualityAlertRepository;
import com.rtdwh.repository.QualityCheckRunRepository;
import com.rtdwh.repository.QualityRuleRepository;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityCheckServiceTest {
    private final QualityRuleRepository ruleRepository = mock(QualityRuleRepository.class);
    private final QualityAlertRepository alertRepository = mock(QualityAlertRepository.class);
    private final QualityCheckRunRepository runRepository = mock(QualityCheckRunRepository.class);
    private final AlertNotifyService notifyService = mock(AlertNotifyService.class);
    private final DorisConnectionService doris = mock(DorisConnectionService.class);
    private final QualityCheckService service = new QualityCheckService(
            ruleRepository, alertRepository, runRepository, notifyService, doris);

    @Test
    void generatesFullyQualifiedDorisQualitySql() {
        when(doris.getCatalog()).thenReturn("rtdwh_paimon");
        QualityRule rule = QualityRule.builder().ruleType("null_rate").layer("ods")
                .targetTable("ods_users").targetColumn("user_id").threshold(0.05).build();

        assertEquals(
                "SELECT CAST(SUM(CASE WHEN `user_id` IS NULL THEN 1 ELSE 0 END) AS DOUBLE) "
                        + "/ NULLIF(COUNT(*), 0) FROM `rtdwh_paimon`.`ods`.`ods_users`",
                service.generateCheckSql(rule));
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
        when(runRepository.save(any(QualityCheckRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(doris.getCatalog()).thenReturn("rtdwh_paimon");
        when(doris.getDatabase()).thenReturn("ods");
        when(doris.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(any(String.class))).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getDouble(1)).thenReturn(0.01);

        assertEquals(0, service.runCheck(9L));

        verify(statement).execute("SWITCH `rtdwh_paimon`");
        verify(runRepository, org.mockito.Mockito.atLeast(2)).save(any(QualityCheckRun.class));
    }
}
