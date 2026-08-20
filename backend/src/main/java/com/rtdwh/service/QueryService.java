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

import java.net.URI;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryService {
    private static final Set<String> ALLOWED = Set.of("SELECT", "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "WITH");
    private static final Pattern WRITE = Pattern.compile("\\b(INSERT|UPDATE|DELETE|MERGE|UPSERT|CREATE|ALTER|DROP|TRUNCATE|GRANT|REVOKE|CALL|SET|USE)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern METADATA = Pattern.compile("\\bpaimon_catalog_[a-z0-9_]+\\b", Pattern.CASE_INSENSITIVE);

    private final QueryHistoryRepository queryHistoryRepository;
    @Value("${paimon.warehouse-path}") private String warehousePath;
    @Value("${paimon.jdbc-uri}") private String paimonJdbcUri;
    @Value("${paimon.jdbc-user}") private String paimonJdbcUser;
    @Value("${paimon.jdbc-password}") private String paimonJdbcPassword;
    @Value("${paimon.catalog-key}") private String paimonCatalogKey;
    @Value("${flink.sql-gateway.url}") private String sqlGatewayUrl;
    @Value("${flink.sql-gateway.enabled}") private boolean sqlGatewayEnabled;
    @Value("${query.max-rows}") private int defaultMaxRows;
    @Value("${query.max-export-rows}") private int maxExportRows;
    @Value("${query.timeout-seconds}") private int defaultTimeout;

    private final ConcurrentHashMap<Long, ActiveQuery> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> requests = new ConcurrentHashMap<>();

    private static final class ActiveQuery {
        final Long userId;
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile Statement statement;
        ActiveQuery(Long userId) { this.userId = userId; }
        void cancel() { cancelled.set(true); try { if (statement != null) statement.cancel(); } catch (SQLException ignored) {} }
    }

    @jakarta.annotation.PostConstruct
    public void initCatalog() {
        if (!sqlGatewayEnabled) return;
        try (Connection c = DriverManager.getConnection(buildJdbcUrl(), "anonymous", ""); Statement s = c.createStatement()) {
            s.execute(buildCatalogStatement()); s.execute("USE CATALOG paimon");
        } catch (Exception e) { log.warn("Paimon catalog pre-registration skipped: {}", e.getMessage()); }
    }

    public Map<String, Object> executeQuery(QueryExecuteDTO dto, Long userId) {
        return execute(dto, userId, QueryType.adhoc, defaultMaxRows);
    }

    public String exportToCsv(QueryExecuteDTO dto, Long userId) {
        Map<String, Object> result = execute(dto, userId, QueryType.adhoc, maxExportRows);
        if (!"success".equals(result.get("status"))) throw new IllegalStateException("查询失败: " + result.get("errorMsg"));
        @SuppressWarnings("unchecked") List<String> columns = (List<String>) result.get("columns");
        @SuppressWarnings("unchecked") List<List<Object>> rows = (List<List<Object>>) result.get("rows");
        StringBuilder out = new StringBuilder("\uFEFF");
        out.append(columns.stream().map(this::csv).reduce((a,b) -> a + "," + b).orElse("")).append('\n');
        for (List<Object> row : rows) out.append(row.stream().map(this::csv).reduce((a,b) -> a + "," + b).orElse("")).append('\n');
        return out.toString();
    }

    private Map<String, Object> execute(QueryExecuteDTO dto, Long userId, QueryType type, int rowLimit) {
        String sql = validateSql(dto.getSql());
        if (!sqlGatewayEnabled && !sql.toUpperCase(Locale.ROOT).startsWith("SHOW ") && !METADATA.matcher(sql).find()) {
            throw new IllegalStateException("Flink SQL Gateway 未启用；当前仅允许查询 Paimon 元数据表");
        }
        int maxRows = Math.max(1, Math.min(dto.getMaxRows() == null ? defaultMaxRows : dto.getMaxRows(), rowLimit));
        int timeout = Math.max(1, Math.min(dto.getTimeoutSeconds() == null ? defaultTimeout : dto.getTimeoutSeconds(), 1800));
        String requestId = dto.getRequestId() == null || dto.getRequestId().isBlank() ? UUID.randomUUID().toString() : dto.getRequestId();
        QueryHistory history = queryHistoryRepository.save(QueryHistory.builder().userId(userId).sqlText(sql).queryType(type).status(QueryStatus.running).build());
        long historyId = history.getId();
        ActiveQuery q = new ActiveQuery(userId); active.put(historyId, q); requests.put(requestId, historyId);
        long started = System.currentTimeMillis();
        List<String> columns = new ArrayList<>(); List<List<Object>> rows = new ArrayList<>();
        QueryStatus status = QueryStatus.success; String error = null; boolean truncated = false;
        try {
            String url = sqlGatewayEnabled ? buildJdbcUrl() : paimonJdbcUri;
            String user = sqlGatewayEnabled ? "anonymous" : paimonJdbcUser;
            String password = sqlGatewayEnabled ? "" : paimonJdbcPassword;
            try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                q.statement = s; s.setQueryTimeout(timeout); s.setMaxRows(maxRows + 1);
                if (q.cancelled.get()) throw new SQLException("查询已取消", "57014");
                if (sqlGatewayEnabled) useCatalog(s);
                if (q.cancelled.get()) throw new SQLException("查询已取消", "57014");
                try (ResultSet rs = s.executeQuery(sql)) {
                    ResultSetMetaData md = rs.getMetaData();
                    for (int i = 1; i <= md.getColumnCount(); i++) columns.add(md.getColumnLabel(i));
                    while (rs.next()) {
                        if (q.cancelled.get()) break;
                        if (rows.size() >= maxRows) { truncated = true; break; }
                        List<Object> row = new ArrayList<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) row.add(value(rs.getObject(i)));
                        rows.add(row);
                    }
                }
                if (q.cancelled.get()) status = QueryStatus.cancelled;
            }
        } catch (Exception e) {
            if (q.cancelled.get() || (e instanceof SQLException se && "57014".equals(se.getSQLState()))) { status = QueryStatus.cancelled; error = "查询已取消"; }
            else { status = QueryStatus.failed; error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); log.error("Query failed: {}", error); }
        } finally { active.remove(historyId); requests.remove(requestId, historyId); q.statement = null; }
        long duration = System.currentTimeMillis() - started;
        history.setResultRowCount(rows.size()); history.setDurationMs(duration); history.setStatus(status); history.setErrorMsg(error); queryHistoryRepository.save(history);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("columns", columns); result.put("rows", rows); result.put("rowCount", rows.size()); result.put("durationMs", duration); result.put("status", status.name()); result.put("errorMsg", error); result.put("historyId", historyId); result.put("requestId", requestId); result.put("truncated", truncated);
        return result;
    }

    public void cancelQuery(Long historyId, Long userId) {
        ActiveQuery q = active.get(historyId);
        if (q == null) throw new IllegalStateException("查询已结束，无法取消");
        if (!Objects.equals(q.userId, userId)) throw new IllegalArgumentException("无权取消该查询");
        q.cancel();
    }
    public void cancelQueryByRequestId(String requestId, Long userId) {
        Long id = requests.get(requestId); if (id == null) throw new IllegalStateException("查询尚未开始或已结束"); cancelQuery(id, userId);
    }

    @Transactional(readOnly = true)
    public Page<QueryHistory> getQueryHistoryPage(Long userId, int page, int size) {
        return queryHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(Math.max(0,page), Math.max(1, Math.min(size,100)), Sort.by(Sort.Direction.DESC, "createdAt")));
    }
    public List<QueryHistory> getQueryHistory(Long userId) { return queryHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId); }
    public Map<String,Object> executeReportQuery(String sql, Long userId) { QueryExecuteDTO d = new QueryExecuteDTO(); d.setSql(sql); d.setMaxRows(maxExportRows); d.setTimeoutSeconds(Math.min(defaultTimeout * 5, 1800)); return execute(d, userId, QueryType.report, maxExportRows); }

    private String validateSql(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("SQL 不能为空");
        String clean = strip(raw); List<String> statements = Arrays.stream(clean.split(";", -1)).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (statements.size() != 1) throw new IllegalArgumentException("每次只能执行一条查询语句");
        String first = statements.get(0).split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
        if (!ALLOWED.contains(first) || WRITE.matcher(statements.get(0)).find()) throw new IllegalArgumentException("仅支持安全的查询类 SQL");
        return raw.trim().replaceFirst(";\\s*$", "");
    }
    private String strip(String sql) {
        StringBuilder out = new StringBuilder(); boolean quote = false; boolean line = false; boolean block = false;
        for (int i=0;i<sql.length();i++) { char c=sql.charAt(i), n=i+1<sql.length()?sql.charAt(i+1):'\0';
            if (line) { if (c=='\n') { line=false; out.append('\n'); } else out.append(' '); continue; }
            if (block) { if (c=='*'&&n=='/') { block=false; out.append("  "); i++; } else out.append(' '); continue; }
            if (!quote&&c=='-'&&n=='-') { line=true; out.append("  "); i++; continue; }
            if (!quote&&c=='/'&&n=='*') { block=true; out.append("  "); i++; continue; }
            if (c=='\'') { if (quote&&n=='\'') { out.append("  "); i++; } else { quote=!quote; out.append(' '); } continue; }
            out.append(quote?' ':c);
        }
        if (quote||block) throw new IllegalArgumentException("SQL 引号或注释未闭合"); return out.toString();
    }
    private void useCatalog(Statement s) throws SQLException { try { s.execute("USE CATALOG paimon"); } catch (SQLException e) { s.execute(buildCatalogStatement()); s.execute("USE CATALOG paimon"); } }
    private String buildJdbcUrl() { URI u=URI.create(sqlGatewayUrl); return "jdbc:hive2://"+u.getHost()+":"+(u.getPort()>0?u.getPort():80)+"/default;transportMode=http;httpPath=flink/sql-gateway"; }
    private String buildCatalogStatement() { return String.format("CREATE CATALOG IF NOT EXISTS paimon WITH ('type'='paimon','metastore'='jdbc','uri'='%s','jdbc.user'='%s','jdbc.password'='%s','catalog-key'='%s','warehouse'='%s')", esc(paimonJdbcUri),esc(paimonJdbcUser),esc(paimonJdbcPassword),esc(paimonCatalogKey),esc(warehousePath)); }
    private String esc(String s) { return s == null ? "" : s.replace("'", "''"); }
    private Object value(Object v) { if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) return v; if (v instanceof byte[] b) return Base64.getEncoder().encodeToString(b); return String.valueOf(v); }
    private String csv(Object v) { return "\""+(v==null?"":String.valueOf(v).replace("\"","\"\""))+"\""; }
}
