package com.rtdwh.service;

import com.rtdwh.dto.QualityCheckSummary;
import com.rtdwh.dto.WorkflowDTO;
import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DatasetProductionService {
    private final TaskOutputDatasetRepository outputRepository;
    private final DatasetProductionRepository productionRepository;
    private final DwhTableMetaRepository tableMetaRepository;
    private final QualityCheckService qualityCheckService;

    public List<TaskOutputDataset> outputs(Long taskId) {
        return outputRepository.findByTaskIdAndEnabledTrueOrderById(taskId);
    }

    @Transactional
    public List<TaskOutputDataset> replaceOutputs(Long taskId, List<WorkflowDTO.OutputDatasetRequest> requests) {
        List<TaskOutputDataset> existing = new ArrayList<>(outputRepository.findByTaskIdOrderById(taskId));
        existing.forEach(output -> output.setEnabled(false));
        Map<String, TaskOutputDataset> byName = new LinkedHashMap<>();
        existing.forEach(output -> byName.put(key(output.getCatalogName(), output.getDatabaseName(), output.getTableName()), output));

        List<TaskOutputDataset> enabled = new ArrayList<>();
        if (requests != null) {
            for (WorkflowDTO.OutputDatasetRequest request : requests) {
                String catalog = identifier(request.getCatalogName(), "Catalog");
                String database = identifier(request.getDatabaseName(), "Database");
                String table = identifier(request.getTableName(), "Table");
                String key = key(catalog, database, table);
                if (enabled.stream().anyMatch(output -> key(output.getCatalogName(), output.getDatabaseName(), output.getTableName()).equals(key))) {
                    throw new IllegalArgumentException("产出数据集重复: " + catalog + "." + database + "." + table);
                }
                TaskOutputDataset output = byName.getOrDefault(key, TaskOutputDataset.builder()
                        .taskId(taskId).catalogName(catalog).databaseName(database).tableName(table).build());
                output.setLayer(parseLayer(request.getLayer()));
                output.setOwner(trim(request.getOwner()));
                output.setBusinessDesc(trim(request.getBusinessDesc()));
                output.setSlaMinutes(Math.max(1, Math.min(request.getSlaMinutes() == null ? 1440 : request.getSlaMinutes(), 525600)));
                output.setQualityGateEnabled(Boolean.TRUE.equals(request.getQualityGateEnabled()));
                output.setEnabled(true);
                if (output.getId() == null) existing.add(output);
                enabled.add(output);
            }
        }
        outputRepository.saveAll(existing);
        return enabled;
    }

    public List<DatasetProduction> productions(Long outputId, int limit) {
        outputRepository.findById(outputId).orElseThrow(() -> new IllegalArgumentException("产出数据集不存在: " + outputId));
        return productionRepository.findByOutputDatasetIdOrderByProducedAtDesc(
                outputId, PageRequest.of(0, Math.max(1, Math.min(limit, 200))));
    }

    @Transactional
    public void recordSuccess(TaskRunInstance instance) {
        LocalDateTime now = LocalDateTime.now();
        for (TaskOutputDataset output : outputRepository.findByTaskIdAndEnabledTrueOrderById(instance.getTaskId())) {
            boolean available = true;
            if (Boolean.TRUE.equals(output.getQualityGateEnabled())) {
                QualityCheckSummary summary = qualityCheckService.runChecksForTableWithSummary(
                        output.getCatalogName(), output.getDatabaseName(), output.getTableName());
                available = summary.total() > 0 && summary.abnormalCount() == 0;
            }
            productionRepository.save(DatasetProduction.builder()
                    .outputDatasetId(output.getId()).taskId(instance.getTaskId()).instanceId(instance.getId())
                    .businessDate(instance.getBusinessDate()).status(available ? "available" : "blocked").producedAt(now).build());
            if (!available) continue;
            output.setLastProducedAt(now);
            output.setLastInstanceId(instance.getId());
            outputRepository.save(output);
            DwhTableMeta table = tableMetaRepository.findByPaimonDbAndPaimonTable(output.getDatabaseName(), output.getTableName())
                    .orElseGet(() -> DwhTableMeta.builder().paimonDb(output.getDatabaseName()).paimonTable(output.getTableName())
                            .sensitivityLevel("internal").lifecycleStatus("active").tags("[\"scheduled-output\"]").build());
            table.setLayer(output.getLayer());
            if (output.getOwner() != null) table.setOwner(output.getOwner());
            if (output.getBusinessDesc() != null) table.setBusinessDesc(output.getBusinessDesc());
            tableMetaRepository.save(table);
        }
    }

    private DwhTableMeta.TableLayer parseLayer(String value) {
        try { return DwhTableMeta.TableLayer.valueOf(value.toLowerCase(Locale.ROOT)); }
        catch (Exception exception) { throw new IllegalArgumentException("数据分层仅支持 ods、dwd、dws、ads"); }
    }

    private String identifier(String value, String label) {
        String result = value == null ? "" : value.trim();
        if (!result.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) throw new IllegalArgumentException(label + " 名称格式不正确");
        return result;
    }

    private String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private String key(String catalog, String database, String table) {
        return (catalog + "." + database + "." + table).toLowerCase(Locale.ROOT);
    }
}
