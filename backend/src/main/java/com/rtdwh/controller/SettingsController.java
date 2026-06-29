package com.rtdwh.controller;

import com.rtdwh.dto.ApiResponse;
import com.rtdwh.service.HealthCheckService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final HealthCheckService healthCheckService;

    @Value("${flink.rest-api.url}")
    private String flinkRestUrl;

    @Value("${flink.submission.mode}")
    private String submissionMode;

    @GetMapping("/flink-cluster")
    public ApiResponse<Map<String, Object>> getFlinkClusterConfig() {
        return ApiResponse.success(Map.of(
                "restApiUrl", flinkRestUrl,
                "submissionMode", submissionMode
        ));
    }

    @GetMapping("/health-check")
    public ApiResponse<Map<String, Object>> healthCheck() {
        Map<String, Object> flinkHealth = healthCheckService.checkFlink();
        Map<String, Object> paimonHealth = healthCheckService.checkPaimon();
        Map<String, Object> mysqlHealth = healthCheckService.checkMySQL();

        String overall = healthCheckService.determineOverallStatus(flinkHealth, paimonHealth, mysqlHealth);

        return ApiResponse.success(Map.of(
                "flink", flinkHealth,
                "paimon", paimonHealth,
                "mysql", mysqlHealth,
                "overall", overall
        ));
    }
}
