package com.rtdwh.service;

import com.rtdwh.dto.QueryExecuteDTO;
import com.rtdwh.entity.QueryHistory;
import com.rtdwh.entity.QueryHistory.QueryStatus;
import com.rtdwh.entity.QueryHistory.QueryType;
import com.rtdwh.repository.QueryHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringWriter;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryService {

    // Dangerous SQL keywords/patterns that could cause data destruction
    private static final Pattern DANGEROUS_KEYWORD_PATTERN = Pattern.compile(
        "\\b(DROP|DELETE|TRUNCATE|ALTER|CREATE\\s+USER|GRANT|REVOKE|SHUTDOWN)\\b",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern MULTIPLE_STATEMENTS_PATTERN = Pattern.compile(
        ";\\s*(DROP|DELETE|TRUNCATE|ALTER|CREATE|GRANT|REVOKE)\\b", Pattern.CASE_INSENSITIVE
    );

    private final QueryHistoryRepository queryHistoryRepository;

    @Value("${paimon.warehouse-path}")
    private String paimonWarehousePath;

    @Value("${paimon.jdbc-uri}")
    private String paimonJdbcUri;

    @Value("${paimon.jdbc-user}")
    private String paimonJdbcUser;

    @Value("${paimon.jdbc-password}")
    private String paimonJdbcPassword;

    @Value("${paimon.catalog-key}")
    private String paimonCatalogKey;

    @Value("${flink.sql-gateway.url}")
    private String flinkSqlGatewayUrl;

    @Value("${flink.sql-gateway.enabled}")
    private boolean sqlGatewayEnabled;

    @Value("${query.max-rows}")
    private int defaultMaxRows;

    @Value("${query.max-export-rows}")
    private int maxExportRows;

    @Value("${query.timeout-seconds}")
    private int defaultTimeout;

    // Track active queries for cancellation support
    private final ConcurrentHashMap<Long, Boolean> activeQueries = new ConcurrentHashMap<>();

    /**
     * Pre-register Paimon catalog during startup to avoid recreating on every query.
     */
    @jakarta.annotation.PostConstruct
    public void initCatalog() {
        if (!sqlGatewayEnabled) {
            log.info("SQL Gateway disabled, skipping catalog pre-registration");
            return;
        }
        try {
            String jdbcUrl = buildJdbcUrl();
            try (Connection conn = DriverManager.getConnection(jdbcUrl, "anonymous", "")) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(buildCatalogStatement());
                    stmt.execute("USE CATALOG paimon");
                    log.info("Paimon catalog pre-registered successfully");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to pre-register Paimon catalog (will register per-query): {}", e.getMessage());
        }
    }

    /**
     * Validate SQL to prevent destructive operations.
     * Only SELECT, SHOW, DESCRIBE, EXPLAIN, WITH are allowed.
     */
    private void validateSql(String sql) {
        String trimmed = sql.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }

        if (DANGEROUS_KEYWORD_PATTERN.matcher(trimmed).find()) {
            throw new IllegalArgumentException("不允许执行危险操作（DROP/DELETE/TRUNCATE/ALTER 等）");
        }

        if (MULTIPLE_STATEMENTS_PATTERN.matcher(trimmed).find()) {
            throw new IllegalArgumentException("不允许执行多条危险语句");
        }

        String firstWord = trimmed.toUpperCase().split("\\s+")[0];
        if (!Arrays.asList("SELECT", "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "WITH").contains(firstWord)) {
            throw new IllegalArgumentException("仅支持查询类语句（SELECT/SHOW/DESCRIBE/EXPLAIN）");
        }
    }

    private String buildJdbcUrl() {
        String gatewayHostPort = flinkSqlGatewayUrl.replace("http://", "").replace("https://", "");
        return "jdbc:hive2://" + gatewayHostPort + "/default;transportMode=http;httpPath=flink/sql-gateway";
    }

    private String buildCatalogStatement() {
        return String.format(
            "CREATE CATALOG IF NOT EXISTS paimon WITH (" +
            "'type' = 'paimon', " +
            "'metastore' = 'jdbc', " +
            "'uri' = '%s', " +
            "'jdbc.user' = '%s', " +
            "'jdbc.password' = '%s', " +
            "'catalog-key' = '%s', " +
            "'warehouse' = '%s'",
            escapeSingleQuote(paimonJdbcUri),
            escapeSingleQuote(paimonJdbcUser),
            escapeSingleQuote(paimonJdbcPassword),
            escapeSingleQuote(paimonCatalogKey),
            escapeSingleQuote(paimonWarehousePath)
        ) + ")";
    }

    private String escapeSingleQuote(String value) {
        if (value == null) return "";
        return value.replace("'", "''");
    }

    /**
     * Execute SQL query with safety validation.
     */
    public Map<String, Object> executeQuery(QueryExecuteDTO dto, Long userId) {
        validateSql(dto.getSql());

        int maxRows = dto.getMaxRows() != null ? Math.min(dto.getMaxRows(), maxExportRows) : defaultMaxRows;
        long startTime = System.currentTimeMillis();
        long historyId = -1L;

        // Create history record first (status = running)
        QueryHistory history = QueryHistory.builder()
                .userId(userId)
                .sqlText(dto.getSql())
                .queryType(QueryType.adhoc)
                .status(QueryStatus.success) // will update on completion
                .build();
        history = queryHistoryRepository.save(history);
        historyId = history.getId();

        // Mark as active for cancellation
        activeQueries.put(historyId, false);

        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        String errorMsg = null;
        QueryStatus status = QueryStatus.success;

        try {
            String jdbcUrl;
            String jdbcUser;
            String jdbcPassword;

            if (sqlGatewayEnabled) {
                jdbcUrl = buildJdbcUrl();
                jdbcUser = "anonymous";
                jdbcPassword = "";
            } else {
                jdbcUrl = paimonJdbcUri;
                jdbcUser = paimonJdbcUser;
                jdbcPassword = paimonJdbcPassword;
                log.warn("Flink SQL Gateway disabled. Query limited to Paimon metastore metadata tables.");
            }

            try (Connection conn = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword)) {
                try (Statement stmt = conn.createStatement(
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                    int timeout = dto.getTimeoutSeconds() != null ? dto.getTimeoutSeconds() : defaultTimeout;
                    stmt.setQueryTimeout(timeout);
                    stmt.setMaxRows(maxRows);

                    if (sqlGatewayEnabled) {
                        // Try pre-registered catalog first, fall back to CREATE IF NOT EXISTS
                        try {
                            stmt.execute("USE CATALOG paimon");
                        } catch (SQLException e) {
                            log.debug("Catalog not pre-registered, creating on-demand: {}", e.getMessage());
                            stmt.execute(buildCatalogStatement());
                            stmt.execute("USE CATALOG paimon");
                        }
                    }

                    try (ResultSet rs = stmt.executeQuery(dto.getSql())) {
                        ResultSetMetaData meta = rs.getMetaData();
                        int colCount = meta.getColumnCount();

                        for (int i = 1; i <= colCount; i++) {
                            columns.add(meta.getColumnLabel(i));
                        }

                        int rowCount = 0;
                        while (rs.next() && rowCount < maxRows) {
                            // Check cancellation
                            if (activeQueries.getOrDefault(historyId, false)) {
                                status = QueryStatus.cancelled;
                                break;
                            }
                            List<Object> row = new ArrayList<>();
                            for (int i = 1; i <= colCount; i++) {
                                row.add(rs.getObject(i));
                            }
                            rows.add(row);
                            rowCount++;
                        }
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Query execution failed: {}", e.getMessage());
            errorMsg = e.getMessage();
            status = QueryStatus.failed;
        } finally {
            // Mark query as completed
            activeQueries.remove(historyId);
        }

        long durationMs = System.currentTimeMillis() - startTime;

        // Update history record
        try {
            history.setResultRowCount(rows.size());
            history.setDurationMs(durationMs);
            history.setStatus(status);
            history.setErrorMsg(errorMsg);
            queryHistoryRepository.save(history);
        } catch (Exception e) {
            log.warn("Failed to update query history: {}", e.getMessage());
        }

        return Map.of(
                "columns", columns,
                "rows", rows,
                "rowCount", rows.size(),
                "durationMs", durationMs,
                "status", status.name(),
                "errorMsg", errorMsg,
                "historyId", historyId
        );
    }

    /**
     * Export query results to CSV format.
     */
    public String exportToCsv(QueryExecuteDTO dto, Long userId) {
        Map<String, Object> result = executeQuery(dto, userId);
        String status = (String) result.get("status");
        if (!"success".equals(status)) {
            throw new RuntimeException("查询失败: " + result.get("errorMsg"));
        }

        @SuppressWarnings("unchecked")
        List<String> columns = (List<String>) result.get("columns");
        @SuppressWarnings("unchecked")
        List<List<Object>> rows = (List<List<Object>>) result.get("rows");

        StringWriter writer = new StringWriter();
        writer.append("\"").append(String.join("\",\"")).append("\"\n");
        for (List<Object> row : rows) {
            String line = row.stream()
                    .map(v -> "\"" + (v == null ? "" : String.valueOf(v).replace("\"", "\"\"")))
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
            writer.append(line).append("\n");
        }
        return writer.toString();
    }

    /**
     * Cancel a running query.
     */
    public void cancelQuery(Long historyId) {
        Boolean wasActive = activeQueries.put(historyId, true);
        if (wasActive == null || !wasActive) {
            // Query already finished, just mark as cancelled in history
            QueryHistory history = queryHistoryRepository.findById(historyId).orElse(null);
            if (history != null && history.getStatus() == QueryStatus.success) {
                history.setStatus(QueryStatus.cancelled);
                queryHistoryRepository.save(history);
            }
        }
    }

    /**
     * Get paginated query history for a user.
     */
    @Transactional(readOnly = true)
    public Page<QueryHistory> getQueryHistoryPage(Long userId, int page, int size) {
        return queryHistoryRepository.findByUserIdOrderByCreatedAtDesc(
                userId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    /**
     * Get query history for a user (legacy, returns all).
     */
    public List<QueryHistory> getQueryHistory(Long userId) {
        return queryHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * Execute a report query using the report template's SQL.
     */
    public Map<String, Object> executeReportQuery(String sql, Long userId) {
        QueryExecuteDTO dto = new QueryExecuteDTO();
        dto.setSql(sql);
        dto.setMaxRows(maxExportRows);
        dto.setTimeoutSeconds(defaultTimeout * 5); // Reports get more time
        return executeQuery(dto, userId);
    }
}
