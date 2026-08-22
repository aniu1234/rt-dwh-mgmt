package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.dto.WorkflowDTO;
import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskScheduleServiceTest {
    private final TaskScheduleRepository repository = mock(TaskScheduleRepository.class);
    private final SyncTaskRepository taskRepository = mock(SyncTaskRepository.class);
    private final TaskDefinitionVersionRepository versionRepository = mock(TaskDefinitionVersionRepository.class);
    private final WorkflowService workflowService = mock(WorkflowService.class);
    private final TaskScheduleService service = new TaskScheduleService(repository, taskRepository,
            versionRepository, workflowService, new ObjectMapper());

    @Test
    void configuresPublishedBatchTaskWithNextRun() {
        when(taskRepository.findById(8L)).thenReturn(Optional.of(SyncTask.builder().id(8L).taskType(SyncTask.TaskType.etl).build()));
        when(versionRepository.findFirstByTaskIdOrderByVersionNoDesc(8L)).thenReturn(Optional.of(TaskDefinitionVersion.builder().build()));
        when(repository.findByTaskId(8L)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        WorkflowDTO.ScheduleRequest request = new WorkflowDTO.ScheduleRequest();
        request.setCronExpression("0 0 2 * * *"); request.setTimezone("Asia/Shanghai");
        request.setBusinessDateOffset(-1); request.setParametersJson("{\"region\":\"east\"}");

        TaskSchedule schedule = service.configure(8L, request, 7L);

        assertTrue(schedule.getEnabled());
        assertNotNull(schedule.getNextRunAt());
        assertEquals("{\"region\":\"east\"}", schedule.getParametersJson());
    }

    @Test
    void createsDueInstanceAndAdvancesSchedule() {
        Instant due = Instant.parse("2026-08-22T00:00:00Z");
        TaskSchedule schedule = TaskSchedule.builder().id(3L).taskId(8L).cronExpression("0 0 * * * *")
                .timezone("Asia/Shanghai").businessDateOffset(-1).parametersJson("{}").enabled(true)
                .nextRunAt(due).createdBy(7L).build();
        when(repository.findDueForUpdate(any(), any())).thenReturn(List.of(schedule), List.of());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals(1, service.runDue());
        assertEquals(due, schedule.getLastRunAt());
        assertTrue(schedule.getNextRunAt().isAfter(due));
        verify(workflowService).createScheduledInstance(eq(8L), eq(3L), eq(due),
                eq(LocalDate.of(2026, 8, 21)), eq("{}"), eq(7L));
    }
}
