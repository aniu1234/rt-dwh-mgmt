package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class FlinkClusterServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void splitsGeneratedSqlWithoutBreakingQuotedSemicolons() {
        String script = "-- task comment\n"
                + "CREATE TABLE `source_t` (`value` STRING) WITH ('note'='a;b');\n"
                + "CREATE TABLE `sink_t` (`value` STRING);\n"
                + "INSERT INTO `sink_t` SELECT * FROM `source_t`;";

        List<String> statements = FlinkClusterService.splitSqlStatements(script);

        assertEquals(3, statements.size());
        assertEquals(true, statements.get(0).startsWith("CREATE TABLE"));
        assertEquals(true, statements.get(2).startsWith("INSERT INTO"));
    }

    @Test
    void extractsJobIdFromSqlGatewayResultRows() throws Exception {
        String jobId = "0123456789abcdef0123456789abcdef";
        var result = objectMapper.readTree("""
                {
                  "results": {
                    "data": [
                      {"kind": "INSERT", "fields": ["%s"]}
                    ]
                  }
                }
                """.formatted(jobId));

        assertEquals(jobId, FlinkClusterService.extractFlinkJobId(result));
    }

    @Test
    void extractsRootCauseInsteadOfGenericGatewayError() throws Exception {
        var result = objectMapper.readTree("""
                {"errors":["Internal server error.",
                  "wrapper\\nCaused by: java.lang.NoClassDefFoundError: org/apache/hadoop/conf/Configuration"]}
                """);

        assertEquals(
                "Caused by: java.lang.NoClassDefFoundError: org/apache/hadoop/conf/Configuration",
                FlinkClusterService.extractSqlGatewayError(result));
    }

    @Test
    void addsFileSchemeToLocalStoragePath() {
        assertEquals(
                "file:///tmp/flink-savepoints",
                FlinkClusterService.normalizeStorageUri("/tmp/flink-savepoints"));
        assertEquals(
                "hdfs://namenode:8020/flink/savepoints",
                FlinkClusterService.normalizeStorageUri("hdfs://namenode:8020/flink/savepoints/"));
    }

    @Test
    void reportsMissingJobSeparatelyFromTemporaryClusterFailure() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        FlinkClusterService service = new FlinkClusterService(restTemplate, objectMapper);
        ReflectionTestUtils.setField(service, "flinkRestUrl", "http://localhost:8081");
        server.expect(requestTo("http://localhost:8081/jobs/missing-job"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        var result = service.getJobStatus("missing-job");

        assertEquals("NOT_FOUND", result.get("status"));
        assertEquals("NOT_FOUND", result.get("flinkState"));
        server.verify();
    }
}
