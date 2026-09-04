package com.rtdwh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.dto.WorkflowDTO;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.entity.TaskSchedule;
import com.rtdwh.repository.SyncTaskRepository;
import com.rtdwh.repository.TaskScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskScheduleService {
    private final TaskScheduleRepository repository;
    private final SyncTaskRepository taskRepository;
    private final WorkflowService workflowService;
    private final ObjectMapper objectMapper;

    public List<TaskSchedule> list() { return repository.findAll(); }

    @Transactional
    public TaskSchedule configure(Long taskId, WorkflowDTO.ScheduleRequest request, Long userId) {
        SyncTask task = taskRepository.findById(taskId).orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
        if (task.getExecutionMode() != SyncTask.ExecutionMode.scheduled) {
            throw new IllegalArgumentException("只有周期任务可以配置周期调度");
        }
        if (task.getPublishedVersionId() == null) {
            throw new IllegalStateException("请先发布任务版本再启用周期调度");
        }
        ZoneId zone = zone(request.getTimezone());
        CronExpression cron = cron(request.getCronExpression());
        String parameters = normalizeJson(request.getParametersJson());
        TaskSchedule schedule = repository.findByTaskId(taskId).orElseGet(TaskSchedule::new);
        schedule.setTaskId(taskId);
        schedule.setCronExpression(request.getCronExpression().trim());
        schedule.setTimezone(zone.getId());
        schedule.setBusinessDateOffset(Math.max(-366, Math.min(request.getBusinessDateOffset() == null ? -1 : request.getBusinessDateOffset(), 366)));
        schedule.setParametersJson(parameters);
        schedule.setEnabled(!Boolean.FALSE.equals(request.getEnabled()));
        schedule.setCreatedBy(schedule.getCreatedBy() == null ? userId : schedule.getCreatedBy());
        schedule.setNextRunAt(schedule.getEnabled() ? next(cron, zone, Instant.now()) : null);
        return repository.save(schedule);
    }

    @Transactional
    public void delete(Long taskId) { repository.findByTaskId(taskId).ifPresent(repository::delete); }

    @Transactional
    public int runDue() {
        int count = 0;
        for (int i = 0; i < 100; i++) {
            if (!runOneDue()) break;
            count++;
        }
        return count;
    }

    private boolean runOneDue() {
        Instant now = Instant.now();
        TaskSchedule schedule = repository.findDueForUpdate(now, PageRequest.of(0, 1)).stream().findFirst().orElse(null);
        if (schedule == null) return false;
        Instant scheduledAt = schedule.getNextRunAt();
        ZoneId zone = zone(schedule.getTimezone());
        schedule.setLastRunAt(scheduledAt);
        schedule.setNextRunAt(next(cron(schedule.getCronExpression()), zone, now));
        repository.save(schedule);
        LocalDate businessDate = scheduledAt.atZone(zone).toLocalDate().plusDays(schedule.getBusinessDateOffset());
        workflowService.createScheduledInstance(schedule.getTaskId(), schedule.getId(), scheduledAt,
                businessDate, schedule.getParametersJson(), schedule.getCreatedBy());
        return true;
    }

    private Instant next(CronExpression cron, ZoneId zone, Instant base) {
        ZonedDateTime value = cron.next(base.atZone(zone));
        if (value == null) throw new IllegalArgumentException("Cron 无法计算下次执行时间");
        return value.toInstant();
    }
    private CronExpression cron(String value) {
        try { return CronExpression.parse(value.trim()); }
        catch (Exception exception) { throw new IllegalArgumentException("Cron 表达式格式不正确"); }
    }
    private ZoneId zone(String value) {
        try { return ZoneId.of(value == null || value.isBlank() ? "Asia/Shanghai" : value.trim()); }
        catch (Exception exception) { throw new IllegalArgumentException("时区格式不正确"); }
    }
    private String normalizeJson(String json) {
        if (json == null || json.isBlank()) return "{}";
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) throw new IllegalArgumentException();
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) { throw new IllegalArgumentException("调度参数必须是 JSON 对象"); }
    }
}
