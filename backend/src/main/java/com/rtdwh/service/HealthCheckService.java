package com.rtdwh.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class HealthCheckService {

    private final DataSource dataSource; // Management DB DataSource (auto-injected by Spring)
    private final FlinkClusterService flinkClusterService;

    @Value("${paimon.jdbc-uri}")
    private String paimonJdbcUri;

    @Value("${paimon.jdbc-user}")
    private String paimonJdbcUser;

    @Value("${paimon.jdbc-password}")
    private String paimonJdbcPassword;

    @Value("${paimon.warehouse-path}")
    private String paimonWarehousePath;

    /**
     * Check Flink cluster health (delegated to FlinkClusterService).
     */
    public Map<String, Object> checkFlink() {
        return flinkClusterService.healthCheck();
    }

    /**
     * Check Paimon metastore connectivity.
     * Verifies the Paimon JDBC metastore MySQL is reachable.
     */
    public Map<String, Object> checkPaimon() {
        long startTime = System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection(paimonJdbcUri, paimonJdbcUser, paimonJdbcPassword)) {
            // Simple ping: execute a lightweight query
            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT 1")) {
                rs.next(); // consume result
            }

            long durationMs = System.currentTimeMillis() - startTime;

            // Try to get some basic info from Paimon metastore
            int dbCount = 0;
            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SHOW DATABASES")) {
                while (rs.next()) dbCount++;
            }

            return Map.of(
                "status", "healthy",
                "warehousePath", paimonWarehousePath,
                "metastoreUri", paimonJdbcUri,
                "responseTimeMs", durationMs,
                "databaseCount", dbCount
            );
        } catch (Exception e) {
            log.warn("Paimon health check failed: {}", e.getMessage());
            return Map.of(
                "status", "unreachable",
                "error", e.getMessage(),
                "metastoreUri", paimonJdbcUri
            );
        }
    }

    /**
     * Check MySQL management DB connectivity.
     * Uses the already-configured Spring DataSource (Druid pool).
     */
    public Map<String, Object> checkMySQL() {
        long startTime = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection()) {
            // Ping with validation query
            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT 1")) {
                rs.next();
            }

            long durationMs = System.currentTimeMillis() - startTime;

            // Get basic DB info
            String dbName = conn.getCatalog();
            var meta = conn.getMetaData();

            return Map.of(
                "status", "healthy",
                "database", dbName != null ? dbName : "unknown",
                "responseTimeMs", durationMs,
                "driver", meta.getDriverName(),
                "dbVersion", meta.getDatabaseProductVersion()
            );
        } catch (Exception e) {
            log.warn("MySQL health check failed: {}", e.getMessage());
            return Map.of(
                "status", "unhealthy",
                "error", e.getMessage()
            );
        }
    }

    /**
     * Determine overall system health status.
     */
    public String determineOverallStatus(Map<String, Object> flink, Map<String, Object> paimon, Map<String, Object> mysql) {
        String flinkStatus = (String) flink.getOrDefault("status", "unknown");
        String paimonStatus = (String) paimon.getOrDefault("status", "unknown");
        String mysqlStatus = (String) mysql.getOrDefault("status", "unknown");

        // If any component is unreachable or unhealthy, overall is degraded
        if ("unreachable".equals(flinkStatus) || "unreachable".equals(paimonStatus) || "unhealthy".equals(mysqlStatus)) {
            return "unhealthy";
        }
        if ("unhealthy".equals(flinkStatus)) {
            return "degraded"; // Flink down but data layer OK
        }
        return "healthy";
    }
}
