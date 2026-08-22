package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.TaskRunInstance;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowSqlRunnerServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void rendersBusinessDateAndScalarParameters() {
        TaskRunInstance instance = TaskRunInstance.builder()
                .businessDate(LocalDate.of(2026, 8, 22))
                .parametersJson("{\"region\":\"east\",\"limit\":100}")
                .build();

        String sql = WorkflowSqlRunnerService.renderSql(
                "INSERT INTO sink SELECT * FROM source "
                        + "WHERE dt='${bizdate}' AND region='${region}' LIMIT ${limit}",
                instance, objectMapper);

        assertEquals("INSERT INTO sink SELECT * FROM source "
                + "WHERE dt='2026-08-22' AND region='east' LIMIT 100", sql);
    }

    @Test
    void rejectsUnresolvedParameters() {
        TaskRunInstance instance = TaskRunInstance.builder()
                .businessDate(LocalDate.of(2026, 8, 22))
                .parametersJson("{}")
                .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> WorkflowSqlRunnerService.renderSql(
                        "INSERT INTO sink SELECT '${missing}'", instance, objectMapper));

        assertEquals("Flink SQL 存在未赋值参数: missing", exception.getMessage());
    }
}
