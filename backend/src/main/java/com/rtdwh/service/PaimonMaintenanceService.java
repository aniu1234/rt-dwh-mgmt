package com.rtdwh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.TableMaintenanceLog;
import com.rtdwh.entity.TableMaintenanceLog.Status;
import com.rtdwh.repository.TableMaintenanceLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Durable, sequential Gateway operations. An ambiguous submit is never automatically repeated. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaimonMaintenanceService {
    private final TableMaintenanceLogRepository repository;
    private final RestTemplate rest;
    private final ObjectMapper mapper;
    @Value("${flink.sql-gateway.enabled:false}") private boolean enabled;
    @Value("${flink.sql-gateway.url:http://localhost:9083}") private String gateway;
    @Value("${flink.rest-api.url:http://localhost:8081}") private String flink;
    @Value("${paimon.jdbc-uri}") private String jdbcUri;
    @Value("${paimon.jdbc-user}") private String jdbcUser;
    @Value("${paimon.jdbc-password}") private String jdbcPassword;
    @Value("${paimon.warehouse-path}") private String warehouse;
    @Value("${paimon.catalog-key:rtdwh}") private String catalog;
    @Value("${maintenance.timeout-seconds:1800}") private long timeoutSeconds = 1800;

    public synchronized Map<String, Object> start(TableMaintenanceLog entry) {
        entry.setStatus(enabled ? Status.unknown : Status.pending);
        entry.setExecutionPhase("SESSION");
        repository.saveAndFlush(entry);
        if (enabled) {
            try {
                entry.setSessionId(handle(post("/v1/sessions", Map.of()), "sessionHandle", "sessionId"));
                repository.saveAndFlush(entry);
                submit(entry, "CATALOG", catalogSql());
            } catch (Exception failure) {
                unknown(entry, "Gateway 提交结果无法确认，请核对会话后处理；未自动重试");
            }
        }
        return Map.of("operationId", entry.getId().toString(), "status", entry.getStatus().name(),
                "message", enabled ? "维护请求已记录，请在执行日志查看进展" : "Gateway 未启用，等待人工执行");
    }

    @Scheduled(fixedDelayString = "${maintenance.reconcile-ms:5000}", initialDelay = 15000)
    public synchronized void reconcile() {
        if (!enabled) return;
        for (Status status : List.of(Status.running, Status.unknown, Status.timed_out)) {
            for (TableMaintenanceLog entry : repository.findByStatus(status)) {
                if (entry.getSessionId() == null || entry.getOperationId() == null) continue;
                try { observe(entry); }
                catch (Exception failure) { unknown(entry, "Gateway 或 Flink 状态暂不可确认，等待后续协调"); }
            }
        }
    }

    private void observe(TableMaintenanceLog entry) throws Exception {
        if (entry.getFlinkJobId() != null) {
            JsonNode job = mapper.readTree(rest.getForObject(flink + "/jobs/" + entry.getFlinkJobId(), String.class));
            String state = job.path("state").asText();
            if ("FINISHED".equals(state)) finish(entry, Status.success, null);
            else if (List.of("FAILED", "CANCELED").contains(state)) finish(entry, Status.failed, "Flink Job: " + state);
            else progress(entry);
            return;
        }
        String path = "/v1/sessions/" + entry.getSessionId() + "/operations/" + entry.getOperationId();
        String state = get(path + "/status").path("status").asText();
        if (List.of("ERROR", "CANCELED", "CLOSED").contains(state)) {
            finish(entry, Status.failed, "Gateway operation: " + state);
        } else if ("FINISHED".equals(state)) {
            switch (entry.getExecutionPhase()) {
                case "CATALOG" -> submit(entry, "USE", "USE CATALOG `" + catalog + "`");
                case "USE" -> submit(entry, "CALL", entry.getSqlContent());
                case "CALL" -> {
                    JsonNode result = get(path + "/result/0");
                    if ("NOT_READY".equals(result.path("resultType").asText())) { progress(entry); return; }
                    // Some procedures return a submitted Flink job instead of waiting for its completion.
                    for (JsonNode row : result.path("results").path("data")) {
                        for (JsonNode field : row.path("fields")) {
                            var jobId = java.util.regex.Pattern.compile("(?i)\\b([a-f0-9]{32})\\b").matcher(field.asText());
                            if (field.isTextual() && jobId.find()) {
                                entry.setFlinkJobId(jobId.group(1));
                                entry.setExecutionPhase("JOB");
                                progress(entry);
                                return;
                            }
                        }
                    }
                    if (!result.has("results") && !"EOS".equals(result.path("resultType").asText())) {
                        unknown(entry, "Gateway 未返回可验证的执行结果");
                        return;
                    }
                    finish(entry, Status.success, null);
                }
                default -> unknown(entry, "维护阶段未知，需要人工核对");
            }
        } else if (List.of("INITIALIZED", "PENDING", "RUNNING").contains(state)) progress(entry);
        else unknown(entry, "Gateway 返回未知状态，需要后续协调");
    }

    private void submit(TableMaintenanceLog entry, String phase, String sql) throws Exception {
        // Save the intent before HTTP. A crash or lost response leaves no handle and cannot cause duplicate CALLs.
        entry.setExecutionPhase(phase);
        entry.setOperationId(null);
        entry.setStatus(Status.unknown);
        entry.setErrorMsg("提交结果待确认；无操作标识时须人工核对，禁止重复执行");
        repository.saveAndFlush(entry);
        String operation = handle(post("/v1/sessions/" + entry.getSessionId() + "/statements",
                Map.of("statement", sql, "executionConfig", Map.of("execution.runtime-mode", "batch", "table.dml-sync", "true"))),
                "operationHandle", "operationId");
        entry.setOperationId(operation);
        progress(entry);
    }

    private void progress(TableMaintenanceLog entry) {
        boolean overdue = entry.getStartedAt() != null && Duration.between(entry.getStartedAt(), LocalDateTime.now()).getSeconds() > timeoutSeconds;
        entry.setStatus(overdue ? Status.timed_out : Status.running);
        entry.setErrorMsg(overdue ? "超过观测时限，仍在协调；请勿重复提交" : null);
        repository.saveAndFlush(entry);
    }
    private void unknown(TableMaintenanceLog entry, String message) {
        entry.setStatus(Status.unknown);
        entry.setErrorMsg(message);
        repository.saveAndFlush(entry);
    }
    private void finish(TableMaintenanceLog entry, Status status, String message) {
        entry.setStatus(status);
        entry.setErrorMsg(message);
        entry.setFinishedAt(LocalDateTime.now());
        entry.setDurationMs(Duration.between(entry.getStartedAt(), entry.getFinishedAt()).toMillis());
        repository.saveAndFlush(entry);
        try { rest.delete(gateway + "/v1/sessions/" + entry.getSessionId()); }
        catch (Exception cleanup) { log.warn("Maintenance {} finished; Gateway session cleanup pending", entry.getId()); }
    }
    private JsonNode post(String path, Map<String, Object> body) throws Exception {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        return mapper.readTree(rest.postForObject(gateway + path, new HttpEntity<>(body, headers), String.class));
    }
    private JsonNode get(String path) throws Exception { return mapper.readTree(rest.getForObject(gateway + path, String.class)); }
    private String handle(JsonNode value, String primary, String legacy) {
        String result = value.path(primary).asText(value.path(legacy).asText());
        if (!result.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalStateException("Missing Gateway handle");
        return result;
    }
    private String catalogSql() {
        if (catalog == null || !catalog.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid Catalog");
        return "CREATE CATALOG `" + catalog + "` WITH ('type'='paimon', 'metastore'='jdbc', 'uri'=" + literal(jdbcUri)
                + ", 'jdbc.user'=" + literal(jdbcUser) + ", 'jdbc.password'=" + literal(jdbcPassword)
                + ", 'catalog-key'=" + literal(catalog) + ", 'warehouse'=" + literal(warehouse) + ")";
    }
    private String literal(String value) { return "'" + value.replace("'", "''") + "'"; }
}
