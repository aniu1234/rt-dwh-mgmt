package com.rtdwh.controller;

import com.rtdwh.dto.ApiResponse;
import com.rtdwh.dto.QualityCheckSummary;
import com.rtdwh.dto.QualityOverviewSummary;
import com.rtdwh.entity.QualityRule;
import com.rtdwh.entity.QualityAlert;
import com.rtdwh.entity.QualityCheckRun;
import com.rtdwh.service.QualityCheckService;
import com.rtdwh.service.QualityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/quality")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('quality:view')")
public class QualityController {

    private final QualityService qualityService;
    private final QualityCheckService qualityCheckService;

    @GetMapping("/rules")
    public ApiResponse<List<QualityRule>> getRules(
            @RequestParam(required = false) String layer,
            @RequestParam(required = false) String ruleType) {
        return ApiResponse.success(qualityService.listRules(layer, ruleType));
    }

    @GetMapping("/overview")
    public ApiResponse<QualityOverviewSummary> overview() {
        return ApiResponse.success(qualityCheckService.getOverview());
    }

    @PostMapping("/rules")
    @PreAuthorize("hasAuthority('quality:manage')")
    public ApiResponse<QualityRule> createRule(@RequestBody QualityRule rule) {
        return ApiResponse.success("规则创建成功", qualityService.createRule(rule));
    }

    @PutMapping("/rules/{id}")
    @PreAuthorize("hasAuthority('quality:manage')")
    public ApiResponse<QualityRule> updateRule(@PathVariable Long id, @RequestBody QualityRule rule) {
        return ApiResponse.success("规则更新成功", qualityService.updateRule(id, rule));
    }

    @PostMapping("/rules/{id}/toggle")
    @PreAuthorize("hasAuthority('quality:manage')")
    public ApiResponse<QualityRule> toggleRule(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> body) {
        if (body == null || !body.containsKey("enabled") || body.get("enabled") == null) {
            throw new IllegalArgumentException("enabled 不能为空");
        }
        return ApiResponse.success("规则状态已更新",
                qualityService.setRuleEnabled(id, body.get("enabled")));
    }

    @DeleteMapping("/rules/{id}")
    @PreAuthorize("hasAuthority('quality:manage')")
    public ApiResponse<Void> deleteRule(@PathVariable Long id) {
        qualityService.deleteRule(id);
        return ApiResponse.success("规则已删除", null);
    }

    @PostMapping("/run-check/all")
    @PreAuthorize("hasAuthority('quality:manage')")
    public ApiResponse<QualityCheckSummary> runAllChecks() {
        QualityCheckSummary summary = qualityCheckService.runAllChecksWithSummary();
        return checkResponse(summary);
    }

    @PostMapping("/rules/{id}/run")
    @PreAuthorize("hasAuthority('quality:manage')")
    public ApiResponse<QualityCheckSummary> runRule(@PathVariable Long id) {
        return checkResponse(qualityCheckService.runCheckWithSummary(id));
    }

    private ApiResponse<QualityCheckSummary> checkResponse(QualityCheckSummary summary) {
        return ApiResponse.success("质量检查完成：通过 " + summary.passed()
                + " 条，未通过 " + summary.failed() + " 条，执行异常 " + summary.errorCount() + " 条", summary);
    }

    @GetMapping("/alerts")
    public ApiResponse<List<QualityAlert>> getAlerts(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) Boolean resolved) {
        return ApiResponse.success(qualityService.listAlerts(level, resolved));
    }

    @GetMapping("/runs")
    public ApiResponse<List<QualityCheckRun>> getRuns(@RequestParam(required = false) Long ruleId) {
        return ApiResponse.success(qualityCheckService.listRuns(ruleId));
    }

    @PostMapping("/alerts/{id}/resolve")
    @PreAuthorize("hasAuthority('quality:manage')")
    public ApiResponse<QualityAlert> resolveAlert(@PathVariable Long id) {
        return ApiResponse.success("告警已解决", qualityService.resolveAlert(id));
    }
}
