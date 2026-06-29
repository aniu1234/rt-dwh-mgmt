package com.rtdwh.controller;

import com.rtdwh.dto.ApiResponse;
import com.rtdwh.entity.QualityRule;
import com.rtdwh.entity.QualityAlert;
import com.rtdwh.service.QualityCheckService;
import com.rtdwh.service.QualityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/quality")
@RequiredArgsConstructor
public class QualityController {

    private final QualityService qualityService;
    private final QualityCheckService qualityCheckService;

    @GetMapping("/rules")
    public ApiResponse<List<QualityRule>> getRules(
            @RequestParam(required = false) String layer,
            @RequestParam(required = false) String ruleType) {
        return ApiResponse.success(qualityService.listRules(layer, ruleType));
    }

    @PostMapping("/rules")
    public ApiResponse<QualityRule> createRule(@RequestBody QualityRule rule) {
        return ApiResponse.success("规则创建成功", qualityService.createRule(rule));
    }

    @PostMapping("/run-check")
    public ApiResponse<Integer> runCheck(@RequestBody(required = false) String body) {
        int alertCount = qualityCheckService.runAllChecks();
        return ApiResponse.success("质量检查完成，发现 " + alertCount + " 个异常", alertCount);
    }

    @GetMapping("/alerts")
    public ApiResponse<List<QualityAlert>> getAlerts(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) Boolean resolved) {
        return ApiResponse.success(qualityService.listAlerts(level, resolved));
    }
}
