package com.rtdwh.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class DorisConnectionService {

    @Value("${doris.enabled:true}")
    private boolean enabled;

    @Value("${doris.jdbc-url:jdbc:mysql://localhost:9030}")
    private String jdbcUrl;

    @Value("${doris.username:root}")
    private String username;

    @Value("${doris.password:}")
    private String password;

    @Value("${doris.catalog:rtdwh_paimon}")
    private String catalog;

    @Value("${doris.database:ods}")
    private String database;

    @Value("${doris.http-url:http://localhost:8030}")
    private String httpUrl;

    @Value("${doris.pool.maximum-size:10}")
    private int maximumPoolSize;

    @Value("${doris.pool.connection-timeout-ms:5000}")
    private long connectionTimeoutMs;

    @Value("${doris.workload-group:rtdwh_adhoc}")
    private String workloadGroup;

    @Value("${doris.exec-mem-limit-bytes:2147483648}")
    private long execMemLimitBytes;

    private volatile HikariDataSource dataSource;

    @PostConstruct
    void initialize() {
        rebuildPool();
    }

    @PreDestroy
    void close() {
        HikariDataSource current = dataSource;
        if (current != null) current.close();
    }

    public Connection getConnection() throws java.sql.SQLException {
        if (!enabled) throw new IllegalStateException("Doris 即席查询引擎未启用");
        HikariDataSource current = dataSource;
        if (current == null) throw new IllegalStateException("Doris 连接池尚未初始化");
        return current.getConnection();
    }

    public synchronized void updateRuntimeConfig(
            boolean enabled,
            String jdbcUrl,
            String username,
            String password,
            String httpUrl,
            String catalog,
            String database
    ) {
        this.enabled = enabled;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password == null ? "" : password;
        this.httpUrl = httpUrl;
        this.catalog = catalog;
        this.database = database;
        rebuildPool();
    }

    public Map<String, Object> testConnection(
            String jdbcUrl,
            String username,
            String password,
            String catalog,
            String database
    ) {
        long started = System.currentTimeMillis();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            String version;
            try (ResultSet resultSet = statement.executeQuery("SELECT VERSION()")) {
                resultSet.next();
                version = resultSet.getString(1);
            }
            statement.execute("SWITCH " + quoteIdentifier(catalog));
            statement.execute("USE " + quoteIdentifier(database));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "healthy");
            result.put("endpoint", jdbcUrl);
            result.put("catalog", catalog);
            result.put("database", database);
            result.put("dorisVersion", version);
            result.put("responseTimeMs", System.currentTimeMillis() - started);
            result.put("checkedAt", Instant.now().toString());
            return result;
        } catch (Exception exception) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "unreachable");
            result.put("endpoint", jdbcUrl);
            result.put("catalog", catalog);
            result.put("database", database);
            result.put("error", safeMessage(exception));
            result.put("suggestion", "确认 Doris FE 9030 端口可用、查询账号有 Catalog 权限，并检查 Paimon Catalog 是否已创建");
            result.put("responseTimeMs", System.currentTimeMillis() - started);
            result.put("checkedAt", Instant.now().toString());
            return result;
        }
    }

    public Map<String, Object> healthCheck() {
        long started = System.currentTimeMillis();
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            String version;
            try (ResultSet resultSet = statement.executeQuery("SELECT VERSION()")) {
                resultSet.next();
                version = resultSet.getString(1);
            }
            int aliveBackends = -1;
            try {
                aliveBackends = countAlive(statement, "SHOW BACKENDS");
            } catch (Exception permissionOrVersionError) {
                log.debug("Doris backend details are not visible to query user: {}",
                        permissionOrVersionError.getMessage());
            }
            statement.execute("SWITCH " + quoteIdentifier(catalog));
            try (ResultSet ignored = statement.executeQuery("SHOW DATABASES")) {
                // Opening the result verifies that the configured external Catalog is readable.
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", aliveBackends == 0 ? "degraded" : "healthy");
            result.put("endpoint", jdbcUrl);
            result.put("httpEndpoint", httpUrl);
            result.put("catalog", catalog);
            result.put("database", database);
            result.put("dorisVersion", version);
            result.put("aliveBackends", aliveBackends);
            result.put("responseTimeMs", System.currentTimeMillis() - started);
            result.put("checkedAt", Instant.now().toString());
            if (aliveBackends == 0) result.put("error", "Doris FE 可连接，但没有可用 BE 节点");
            return result;
        } catch (Exception exception) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", enabled ? "unreachable" : "disabled");
            result.put("endpoint", jdbcUrl);
            result.put("catalog", catalog);
            result.put("database", database);
            result.put("error", enabled ? safeMessage(exception) : "Doris 即席查询引擎未启用");
            result.put("responseTimeMs", System.currentTimeMillis() - started);
            result.put("checkedAt", Instant.now().toString());
            return result;
        }
    }

    public boolean isEnabled() { return enabled; }
    public String getJdbcUrl() { return jdbcUrl; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getCatalog() { return catalog; }
    public String getDatabase() { return database; }
    public String getHttpUrl() { return httpUrl; }
    public String getWorkloadGroup() { return workloadGroup; }
    public long getExecMemLimitBytes() { return execMemLimitBytes; }

    private synchronized void rebuildPool() {
        HikariDataSource previous = dataSource;
        if (!enabled) {
            dataSource = null;
            if (previous != null) previous.close();
            return;
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("rtdwh-doris-query");
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setMaximumPoolSize(Math.max(1, maximumPoolSize));
        config.setMinimumIdle(0);
        config.setConnectionTimeout(Math.max(1000L, connectionTimeoutMs));
        config.setInitializationFailTimeout(-1L);
        config.addDataSourceProperty("useUnicode", "true");
        config.addDataSourceProperty("characterEncoding", "UTF-8");
        config.addDataSourceProperty("useSSL", "false");
        config.addDataSourceProperty("allowPublicKeyRetrieval", "true");
        dataSource = new HikariDataSource(config);
        if (previous != null) previous.close();
        log.info("Doris query pool configured: endpoint={}, catalog={}, database={}", jdbcUrl, catalog, database);
    }

    private int countAlive(Statement statement, String sql) throws Exception {
        int alive = 0;
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            ResultSetMetaData metadata = resultSet.getMetaData();
            int aliveColumn = -1;
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                if ("alive".equalsIgnoreCase(metadata.getColumnLabel(index))) aliveColumn = index;
            }
            while (resultSet.next()) {
                if (aliveColumn < 0 || truthy(resultSet.getObject(aliveColumn))) alive++;
            }
        }
        return alive;
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        return value != null && Set.of("true", "yes", "1").contains(String.valueOf(value).toLowerCase(Locale.ROOT));
    }

    public static String quoteIdentifier(String identifier) {
        if (identifier == null || !identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("非法 Doris 标识符: " + identifier);
        }
        return "`" + identifier + "`";
    }

    public static String quoteLiteral(String value) {
        if (value == null) throw new IllegalArgumentException("Doris 字符串参数不能为空");
        return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
