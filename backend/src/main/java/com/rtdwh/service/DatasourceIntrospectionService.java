package com.rtdwh.service;

import com.rtdwh.entity.DatasourceConfig;
import com.rtdwh.repository.DatasourceConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourceIntrospectionService {

    private final DatasourceConfigRepository datasourceConfigRepository;
    private final CdcTableIntrospector cdcTableIntrospector;

    /**
     * List all tables from a datasource.
     */
    @Transactional(readOnly = true)
    public List<String> listTables(Long datasourceId) {
        DatasourceConfig config = datasourceConfigRepository.findById(datasourceId)
                .orElseThrow(() -> new IllegalArgumentException("数据源不存在: " + datasourceId));
        return cdcTableIntrospector.listTables(config);
    }

    /**
     * Get table column structure from a datasource.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> introspectTable(Long datasourceId, String tableName) {
        DatasourceConfig config = datasourceConfigRepository.findById(datasourceId)
                .orElseThrow(() -> new IllegalArgumentException("数据源不存在: " + datasourceId));

        CdcTableIntrospector.TableSchema schema = cdcTableIntrospector.introspectTable(config, tableName);

        return Map.of(
                "tableName", schema.tableName(),
                "columns", schema.columns().stream().map(col -> Map.of(
                        "name", col.name(),
                        "type", col.type(),
                        "comment", col.comment(),
                        "nullable", col.nullable(),
                        "primaryKey", col.primaryKey()
                )).toList(),
                "primaryKeys", schema.primaryKeys()
        );
    }
}
