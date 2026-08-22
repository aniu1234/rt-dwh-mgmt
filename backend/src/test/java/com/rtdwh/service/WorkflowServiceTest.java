package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.dto.WorkflowDTO;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.entity.TaskDependency;
import com.rtdwh.entity.TaskRunInstance;
import com.rtdwh.repository.SyncTaskRepository;
import com.rtdwh.repository.TaskDefinitionVersionRepository;
import com.rtdwh.repository.TaskDependencyRepository;
import com.rtdwh.repository.TaskRunInstanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkflowServiceTest {
    private final SyncTaskRepository taskRepository = mock(SyncTaskRepository.class);
    private final TaskDependencyRepository dependencyRepository = mock(TaskDependencyRepository.class);
    private final TaskDefinitionVersionRepository versionRepository = mock(TaskDefinitionVersionRepository.class);
    private final TaskRunInstanceRepository instanceRepository = mock(TaskRunInstanceRepository.class);
    private final WorkflowService service = new WorkflowService(taskRepository, dependencyRepository,
            versionRepository, instanceRepository, new ObjectMapper().findAndRegisterModules());

    @Test
    void rejectsDependencyCycle() {
        when(taskRepository.findById(anyLong())).thenAnswer(invocation -> Optional.of(task(invocation.getArgument(0), SyncTask.TaskType.etl)));
        when(dependencyRepository.findAll()).thenReturn(List.of(TaskDependency.builder()
                .upstreamTaskId(2L).downstreamTaskId(1L).build()));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.addDependency(1L, 2L, 7L));

        assertEquals("新增依赖会形成环路", exception.getMessage());
        verify(dependencyRepository, never()).save(any());
    }

    @Test
    void createsOneBackfillInstancePerBusinessDate() {
        when(taskRepository.findById(8L)).thenReturn(Optional.of(task(8L, SyncTask.TaskType.etl)));
        when(dependencyRepository.findByDownstreamTaskId(8L)).thenReturn(List.of());
        when(instanceRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        WorkflowDTO.BackfillRequest request = new WorkflowDTO.BackfillRequest();
        request.setStartDate(LocalDate.of(2026, 8, 1));
        request.setEndDate(LocalDate.of(2026, 8, 3));
        request.setParametersJson("{\"partition\":\"dt\"}");

        List<TaskRunInstance> instances = service.createBackfill(8L, request, 7L);

        assertEquals(3, instances.size());
        assertTrue(instances.stream().allMatch(item -> item.getStatus() == TaskRunInstance.RunStatus.queued));
        assertEquals(LocalDate.of(2026, 8, 3), instances.get(2).getBusinessDate());
    }

    @Test
    void promotesDownstreamOnlyAfterUpstreamSuccess() {
        TaskRunInstance waiting = TaskRunInstance.builder().id(3L).taskId(20L)
                .businessDate(LocalDate.of(2026, 8, 20)).status(TaskRunInstance.RunStatus.waiting)
                .createdAt(LocalDateTime.now()).build();
        when(instanceRepository.findByStatusOrderByCreatedAtAsc(eq(TaskRunInstance.RunStatus.waiting), any(Pageable.class)))
                .thenReturn(List.of(waiting));
        when(dependencyRepository.findByDownstreamTaskId(20L)).thenReturn(List.of(TaskDependency.builder()
                .upstreamTaskId(10L).downstreamTaskId(20L).build()));
        when(instanceRepository.findFirstByTaskIdAndBusinessDateAndStatusOrderByCreatedAtDesc(
                10L, waiting.getBusinessDate(), TaskRunInstance.RunStatus.success))
                .thenReturn(Optional.of(TaskRunInstance.builder().id(2L).build()));

        assertEquals(1, service.promoteReadyInstances());
        assertEquals(TaskRunInstance.RunStatus.queued, waiting.getStatus());
        verify(instanceRepository).save(waiting);
    }

    private SyncTask task(Long id, SyncTask.TaskType type) {
        return SyncTask.builder().id(id).taskName("task-" + id).taskType(type)
                .status(SyncTask.TaskStatus.draft).parallelism(1).checkpointIntervalMs(60000L).build();
    }
}
