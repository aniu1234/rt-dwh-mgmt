package com.rtdwh.service;

import com.rtdwh.entity.DatasourceConfig;
import com.rtdwh.entity.DatasourceConfig.DbType;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.util.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CdcSqlGenerator {

    private final CdcTableIntrospector introspector;
    private final EncryptionUtil encryptionUtil;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @Value("${cdc.flink-cdc-version:3.3.0}")
    private String flinkCdcVersion;

    @Value("${cdc.debezium-version:2.5.0}")
    private String debeziumVersion;

    @Value("${cdc.default-start-mode:initial}")
    private String defaultStartMode;

    @Value("${paimon.warehouse-path}")
    private String warehousePath;

    /**
     * Generate CDC SQL for a sync task based on its table mappings.
     */
    public String generateCdcSql(SyncTask task, DatasourceConfig sourceConfig, DatasourceConfig targetConfig) {
        List<Map<String, String>> mappings = parseTableMappings(task.getTableMappings());
        if (mappings.isEmpty()) {
            throw new IllegalArgumentException("表映射配置为空");
        }
        if (sourceConfig == null || targetConfig == null) {
            throw new IllegalArgumentException("源/目标数据源配置不存在");
        }

        StringBuilder sql = new StringBuilder();
        sql.append("-- Flink CDC 同步任务: ").append(task.getTaskName()).append("\n");
        sql.append("-- 源: ").append(sourceConfig.getConfigName()).append(" -> 目标: ").append(targetConfig.getConfigName()).append("\n");
        sql.append("-- 策略: ").append(task.getSyncStrategy() != null ? task.getSyncStrategy() : defaultStartMode).append("\n\n");

        // Generate SQL for each table mapping
        for (int i = 0; i < mappings.size(); i++) {
            Map<String, String> mapping = mappings.get(i);
            String sourceTable = mapping.get("sourceTable");
            String targetDb = mapping.get("targetDb");
            String targetTable = mapping.get("targetTable");

            if (i > 0) sql.append("\n");
            sql.append(generateTableCdcSql(sourceConfig, sourceTable, targetConfig, targetDb, targetTable, task));
        }

        return sql.toString();
    }

    /**
     * Generate CDC SQL for a single table mapping.
     */
    private String generateTableCdcSql(DatasourceConfig sourceConfig, String sourceTable,
                                        DatasourceConfig targetConfig, String targetDb,
                                        String targetTable, SyncTask task) {
        StringBuilder sql = new StringBuilder();

        // 1. Create CDC source table
        sql.append("CREATE TABLE source_").append(sourceTable).append(" (\n");

        // Introspect source table structure
        CdcTableIntrospector.TableSchema schema;
        try {
            schema = introspector.introspectTable(sourceConfig, sourceTable);
        } catch (Exception e) {
            log.warn("Failed to introspect table {}, using fallback schema: {}", sourceTable, e.getMessage());
            schema = buildFallbackSchema(sourceTable);
        }

        // Generate column definitions
        List<String> pkCols = new ArrayList<>();
        for (int i = 0; i < schema.columns().size(); i++) {
            CdcTableIntrospector.ColumnSchema col = schema.columns().get(i);
            String flinkType = toFlinkType(col.type());
            sql.append("  ").append(col.name()).append(" ").append(flinkType);
            if (i < schema.columns().size() - 1) sql.append(",");
            sql.append("\n");
            if (col.primaryKey()) pkCols.add(col.name());
        }

        // Add watermark for event time
        sql.append(") WITH (\n");
        sql.append(generateSourceWithClause(sourceConfig, sourceTable));
        sql.append(");\n\n");

        // 2. Create Paimon target table
        sql.append("CREATE TABLE IF NOT EXISTS ").append(targetDb).append(".").append(targetTable).append(" (\n");

        // Mirror source columns to target
        for (int i = 0; i < schema.columns().size(); i++) {
            CdcTableIntrospector.ColumnSchema col = schema.columns().get(i);
            String flinkType = toFlinkType(col.type());
            sql.append("  ").append(col.name()).append(" ").append(flinkType);
            if (i < schema.columns().size() - 1) sql.append(",");
            sql.append("\n");
        }

        // Add partition columns if sync strategy is full_then_incremental
        if ("full_then_incremental".equals(task.getSyncStrategy())) {
            sql.append("  ,dt STRING\n");
        }

        // Add primary key for Paimon
        if (!pkCols.isEmpty()) {
            sql.append("  ,PRIMARY KEY (").append(String.join(", ", pkCols)).append(") NOT ENFORCED\n");
        }

        sql.append(") WITH (\n");
        sql.append(generateSinkWithClause(targetDb, targetTable, task));
        sql.append(");\n\n");

        // 3. INSERT INTO statement
        List<String> columnNames = schema.columns().stream().map(CdcTableIntrospector.ColumnSchema::name).toList();
        sql.append("INSERT INTO ").append(targetDb).append(".").append(targetTable).append(" (\n");
        sql.append("  ").append(String.join(",\n  ", columnNames));
        if ("full_then_incremental".equals(task.getSyncStrategy())) {
            sql.append(",\n  dt");
        }
        sql.append("\n) SELECT\n");
        sql.append("  ").append(String.join(",\n  ", columnNames));
        if ("full_then_incremental".equals(task.getSyncStrategy())) {
            sql.append(",\n  DATE_FORMAT(").append(pkCols.get(0)).append(", 'yyyy-MM-dd') AS dt");
        }
        sql.append("\nFROM source_").append(sourceTable).append(";\n");

        return sql.toString();
    }

    /**
     * Generate source table WITH clause for CDC connector.
     */
    private String generateSourceWithClause(DatasourceConfig sourceConfig, String sourceTable) {
        StringBuilder sb = new StringBuilder();
        DbType dbType = sourceConfig.getDbType();

        if (dbType == DbType.mysql) {
            sb.append("  'connector' = 'mysql-cdc',\n");
            sb.append("  'hostname' = '").append(sourceConfig.getHost()).append("',\n");
            sb.append("  'port' = '").append(sourceConfig.getPort()).append("',\n");
            sb.append("  'username' = '").append(sourceConfig.getUsername()).append("',\n");
            sb.append("  'password' = '").append(decryptPassword(sourceConfig.getPasswordEncrypted())).append("',\n");
            sb.append("  'database-name' = '").append(sourceConfig.getDatabase()).append("',\n");
            sb.append("  'table-name' = '").append(sourceTable).append("',\n");
            sb.append("  'server-time-zone' = 'Asia/Shanghai',\n");
            sb.append("  'scan.startup.mode' = '").append(defaultStartMode).append("',\n");
            sb.append("  'debezium.snapshot.lock.mode' = 'none'\n");
        } else if (dbType == DbType.postgresql) {
            sb.append("  'connector' = 'postgres-cdc',\n");
            sb.append("  'hostname' = '").append(sourceConfig.getHost()).append("',\n");
            sb.append("  'port' = '").append(sourceConfig.getPort()).append("',\n");
            sb.append("  'username' = '").append(sourceConfig.getUsername()).append("',\n");
            sb.append("  'password' = '").append(decryptPassword(sourceConfig.getPasswordEncrypted())).append("',\n");
            sb.append("  'database-name' = '").append(sourceConfig.getDatabase()).append("',\n");
            sb.append("  'schema-name' = 'public',\n");
            sb.append("  'table-name' = '").append(sourceTable).append("',\n");
            sb.append("  'decoding.plugin.name' = 'pgoutput'\n");
        } else {
            throw new IllegalArgumentException("Unsupported source database type: " + dbType);
        }

        return sb.toString();
    }

    /**
     * Generate sink table WITH clause for Paimon connector.
     */
    private String generateSinkWithClause(String targetDb, String targetTable, SyncTask task) {
        StringBuilder sb = new StringBuilder();
        sb.append("  'connector' = 'paimon',\n");
        sb.append("  'path' = '").append(warehousePath).append("/").append(targetDb).append("/").append(targetTable).append("',\n");
        sb.append("  'metastore' = 'jdbc',\n");
        sb.append("  'warehouse' = '").append(warehousePath).append("',\n");
        sb.append("  'sink.auto-create' = 'true',\n");
        sb.append("  'changelog-producer' = 'input',\n");
        sb.append("  'lookup.cache.max-rows' = '10000',\n");
        sb.append("  'lookup.cache.ttl' = '10min'\n");

        return sb.toString();
    }

    /**
     * Convert JDBC type to Flink SQL type.
     */
    private String toFlinkType(String jdbcType) {
        String upper = jdbcType.toUpperCase();
        return switch (upper) {
            case "VARCHAR", "CHAR", "TEXT", "LONGTEXT" -> "STRING";
            case "INT", "INTEGER", "SMALLINT", "TINYINT" -> "INT";
            case "BIGINT" -> "BIGINT";
            case "DECIMAL", "NUMERIC" -> "DECIMAL(38, 18)";
            case "FLOAT" -> "FLOAT";
            case "DOUBLE" -> "DOUBLE";
            case "BOOLEAN", "BOOL" -> "BOOLEAN";
            case "DATE" -> "DATE";
            case "TIME" -> "TIME(0)";
            case "DATETIME", "TIMESTAMP", "TIMESTAMP(6)", "TIMESTAMP(3)" -> "TIMESTAMP(3)";
            case "YEAR" -> "INT";
            default -> "STRING";
        };
    }

    private String decryptPassword(String encryptedPassword) {
        try {
            return encryptionUtil.decrypt(encryptedPassword);
        } catch (Exception e) {
            log.warn("Failed to decrypt password: {}", e.getMessage());
            return encryptedPassword;
        }
    }

    /**
     * Parse table mappings from JSON string.
     */
    private List<Map<String, String>> parseTableMappings(String mappingsJson) {
        List<Map<String, String>> mappings = new ArrayList<>();
        if (mappingsJson == null || mappingsJson.isBlank()) return mappings;

        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(mappingsJson);
            if (root.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode item : root) {
                    Map<String, String> map = new LinkedHashMap<>();
                    map.put("sourceTable", item.path("sourceTable").asText(""));
                    map.put("targetDb", item.path("targetDb").asText("ods"));
                    map.put("targetTable", item.path("targetTable").asText(""));
                    map.put("syncMode", item.path("syncMode").asText("full+incremental"));
                    mappings.add(map);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse table mappings: {}", e.getMessage());
        }
        return mappings;
    }

    /**
     * Build a fallback schema when introspection fails.
     */
    private CdcTableIntrospector.TableSchema buildFallbackSchema(String tableName) {
        List<CdcTableIntrospector.ColumnSchema> columns = List.of(
                CdcTableIntrospector.ColumnSchema.builder().name("id").type("BIGINT").primaryKey(true).build(),
                CdcTableIntrospector.ColumnSchema.builder().name("created_at").type("TIMESTAMP(3)").build(),
                CdcTableIntrospector.ColumnSchema.builder().name("updated_at").type("TIMESTAMP(3)").build()
        );
        return new CdcTableIntrospector.TableSchema(tableName, columns, List.of("id"));
    }
}
