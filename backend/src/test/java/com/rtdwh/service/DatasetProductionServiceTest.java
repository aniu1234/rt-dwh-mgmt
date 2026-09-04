package com.rtdwh.service;

import com.rtdwh.dto.QualityCheckSummary;
import com.rtdwh.dto.WorkflowDTO;
import com.rtdwh.entity.DatasetProduction;
import com.rtdwh.entity.DwhTableMeta;
import com.rtdwh.entity.TaskOutputDataset;
import com.rtdwh.entity.TaskRunInstance;
import com.rtdwh.repository.DatasetProductionRepository;
import com.rtdwh.repository.DwhTableMetaRepository;
import com.rtdwh.repository.TaskOutputDatasetRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatasetProductionServiceTest {
    private final TaskOutputDatasetRepository outputs = mock(TaskOutputDatasetRepository.class);
    private final DatasetProductionRepository productions = mock(DatasetProductionRepository.class);
    private final DwhTableMetaRepository tables = mock(DwhTableMetaRepository.class);
    private final QualityCheckService quality = mock(QualityCheckService.class);
    private final QueryAccessScopeService access = mock(QueryAccessScopeService.class);
    private final DatasetProductionService service = new DatasetProductionService(outputs, productions, tables, quality, access);

    @Test
    void preservesProductionHistoryWhenOutputConfigurationIsRemovedAndRestored() {
        TaskOutputDataset existing = output(9L, true);
        existing.setLastProducedAt(LocalDateTime.parse("2026-08-21T02:00:00"));
        when(outputs.findByTaskIdOrderById(3L)).thenReturn(List.of(existing));
        when(outputs.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        assertTrue(service.replaceOutputs(3L, List.of()).isEmpty());
        assertFalse(existing.getEnabled());
        verify(outputs, never()).deleteByTaskId(anyLong());

        WorkflowDTO.OutputDatasetRequest request = request();
        List<TaskOutputDataset> restored = service.replaceOutputs(3L, List.of(request));

        assertEquals(List.of(existing), restored);
        assertTrue(existing.getEnabled());
        assertEquals(LocalDateTime.parse("2026-08-21T02:00:00"), existing.getLastProducedAt());
    }

    @Test
    void blocksProducedDatasetWhenQualityGateFails() {
        TaskOutputDataset output = output(9L, true);
        when(outputs.findByTaskIdAndEnabledTrueOrderById(3L)).thenReturn(List.of(output));
        when(quality.runChecksForTableWithSummary("rtdwh_paimon", "ads", "daily_sales"))
                .thenReturn(summary(1, 0, 1, 0));

        service.recordSuccess(instance());

        verify(productions).save(argThat(value -> "blocked".equals(value.getStatus())));
        verify(outputs, never()).save(any());
        verify(tables, never()).save(any());
        assertNull(output.getLastProducedAt());
    }

    @Test
    void registersAvailableDatasetAsDataAsset() {
        TaskOutputDataset output = output(9L, true);
        when(outputs.findByTaskIdAndEnabledTrueOrderById(3L)).thenReturn(List.of(output));
        when(quality.runChecksForTableWithSummary("rtdwh_paimon", "ads", "daily_sales"))
                .thenReturn(summary(1, 1, 0, 0));
        when(tables.findByPaimonDbAndPaimonTable("ads", "daily_sales")).thenReturn(Optional.empty());

        service.recordSuccess(instance());

        verify(productions).save(argThat(value -> "available".equals(value.getStatus())));
        verify(tables).save(argThat(table -> table.getLayer() == DwhTableMeta.TableLayer.ads
                && "daily_sales".equals(table.getPaimonTable())));
        assertNotNull(output.getLastProducedAt());
        assertEquals(21L, output.getLastInstanceId());
    }

    @Test
    void blocksQualityGateWhenNoRuleMatchesTheDataset() {
        TaskOutputDataset output = output(9L, true);
        when(outputs.findByTaskIdAndEnabledTrueOrderById(3L)).thenReturn(List.of(output));
        when(quality.runChecksForTableWithSummary("rtdwh_paimon", "ads", "daily_sales"))
                .thenReturn(summary(0, 0, 0, 0));

        service.recordSuccess(instance());

        verify(productions).save(argThat(value -> "blocked".equals(value.getStatus())));
        verify(tables, never()).save(any());
    }

    private TaskOutputDataset output(Long id, boolean qualityGate) {
        return TaskOutputDataset.builder().id(id).taskId(3L).catalogName("rtdwh_paimon")
                .databaseName("ads").tableName("daily_sales").layer(DwhTableMeta.TableLayer.ads)
                .slaMinutes(60).qualityGateEnabled(qualityGate).enabled(true).build();
    }

    private WorkflowDTO.OutputDatasetRequest request() {
        WorkflowDTO.OutputDatasetRequest request = new WorkflowDTO.OutputDatasetRequest();
        request.setCatalogName("rtdwh_paimon"); request.setDatabaseName("ads"); request.setTableName("daily_sales");
        request.setLayer("ads"); request.setSlaMinutes(60); request.setQualityGateEnabled(true);
        return request;
    }

    private TaskRunInstance instance() {
        return TaskRunInstance.builder().id(21L).taskId(3L).businessDate(LocalDate.of(2026, 8, 21)).build();
    }

    private QualityCheckSummary summary(int total, int passed, int failed, int errors) {
        LocalDateTime now = LocalDateTime.now();
        return new QualityCheckSummary("batch", total, passed, failed, errors, failed + errors, now, now, 1L);
    }
}
