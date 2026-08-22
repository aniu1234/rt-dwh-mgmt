package com.rtdwh.controller;

import com.rtdwh.dto.ApiResponse;
import com.rtdwh.dto.WorkflowDTO;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.entity.TaskDefinitionVersion;
import com.rtdwh.entity.TaskDependency;
import com.rtdwh.entity.TaskRunInstance;
import com.rtdwh.service.WorkflowService;
import com.rtdwh.util.SecurityContextUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/workflow")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('task:view')")
public class WorkflowController {
    private final WorkflowService workflowService;
    private final SecurityContextUtil securityContextUtil;

    @GetMapping("/graph")
    public ApiResponse<Map<String, Object>> graph() {
        return ApiResponse.success(workflowService.graph());
    }

    @PostMapping("/dependencies")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<TaskDependency> addDependency(@Valid @RequestBody WorkflowDTO.DependencyRequest request) {
        return ApiResponse.success("依赖创建成功", workflowService.addDependency(
                request.getUpstreamTaskId(), request.getDownstreamTaskId(), securityContextUtil.getCurrentUserId()));
    }

    @DeleteMapping("/dependencies")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<Void> removeDependency(@RequestParam Long upstreamTaskId,
                                              @RequestParam Long downstreamTaskId) {
        workflowService.removeDependency(upstreamTaskId, downstreamTaskId);
        return ApiResponse.success("依赖已删除", null);
    }

    @PostMapping("/tasks/{taskId}/publish")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<TaskDefinitionVersion> publish(@PathVariable Long taskId,
                                                       @Valid @RequestBody WorkflowDTO.PublishRequest request) {
        return ApiResponse.success("任务版本已发布", workflowService.publish(
                taskId, request.getChangeSummary(), securityContextUtil.getCurrentUserId()));
    }

    @GetMapping("/tasks/{taskId}/versions")
    public ApiResponse<List<TaskDefinitionVersion>> versions(@PathVariable Long taskId) {
        return ApiResponse.success(workflowService.versions(taskId));
    }

    @PostMapping("/tasks/{taskId}/rollback/{versionNo}")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<SyncTask> rollback(@PathVariable Long taskId, @PathVariable Integer versionNo) {
        return ApiResponse.success("任务配置已回滚", workflowService.rollback(taskId, versionNo));
    }

    @PostMapping("/tasks/{taskId}/backfill")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<List<TaskRunInstance>> backfill(@PathVariable Long taskId,
                                                        @Valid @RequestBody WorkflowDTO.BackfillRequest request) {
        return ApiResponse.success("补数实例已创建", workflowService.createBackfill(
                taskId, request, securityContextUtil.getCurrentUserId()));
    }

    @GetMapping("/instances")
    public ApiResponse<List<TaskRunInstance>> instances(
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) TaskRunInstance.RunStatus status,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(workflowService.instances(taskId, status, limit));
    }

    @PostMapping("/instances/claim")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<TaskRunInstance> claim(@RequestParam String executorId) {
        return workflowService.claim(executorId)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.success("当前没有可执行实例", null));
    }

    @PostMapping("/instances/{instanceId}/complete")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<TaskRunInstance> complete(@PathVariable Long instanceId,
                                                  @Valid @RequestBody WorkflowDTO.CompleteRequest request) {
        return ApiResponse.success("实例状态已更新", workflowService.complete(
                instanceId, request.getSuccess(), request.getErrorMessage()));
    }
}
