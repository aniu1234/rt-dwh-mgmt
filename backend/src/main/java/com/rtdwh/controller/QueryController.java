package com.rtdwh.controller;

import com.rtdwh.dto.ApiResponse;
import com.rtdwh.dto.QueryExecuteDTO;
import com.rtdwh.entity.QueryHistory;
import com.rtdwh.service.QueryService;
import com.rtdwh.util.SecurityContextUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/query")
@RequiredArgsConstructor
public class QueryController {

    private final QueryService queryService;
    private final SecurityContextUtil securityContextUtil;

    /**
     * Execute an ad-hoc SQL query.
     */
    @PostMapping("/execute")
    public ApiResponse<Map<String, Object>> executeQuery(@Valid @RequestBody QueryExecuteDTO dto) {
        Long userId = securityContextUtil.getCurrentUserId();
        Map<String, Object> result = queryService.executeQuery(dto, userId);
        return ApiResponse.success(result);
    }

    /**
     * Export query results to CSV.
     */
    @PostMapping("/export")
    public ApiResponse<String> exportQuery(@Valid @RequestBody QueryExecuteDTO dto) {
        Long userId = securityContextUtil.getCurrentUserId();
        try {
            String csv = queryService.exportToCsv(dto, userId);
            return ApiResponse.success(csv);
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /**
     * Cancel a running query.
     */
    @PostMapping("/cancel/{historyId}")
    public ApiResponse<Void> cancelQuery(@PathVariable Long historyId) {
        queryService.cancelQuery(historyId);
        return ApiResponse.success("查询已取消", null);
    }

    /**
     * Get paginated query history.
     */
    @GetMapping("/history")
    public ApiResponse<Page<QueryHistory>> getQueryHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = securityContextUtil.getCurrentUserId();
        return ApiResponse.success(queryService.getQueryHistoryPage(userId, page, size));
    }

    /**
     * Execute a report query using the report's SQL template.
     */
    @PostMapping("/report/{reportId}")
    public ApiResponse<Map<String, Object>> executeReport(
            @PathVariable Long reportId,
            @RequestBody(required = false) Map<String, Object> params) {
        Long userId = securityContextUtil.getCurrentUserId();
        // TODO: Fetch report template SQL from DB and execute
        // For now, return a placeholder
        return ApiResponse.error(501, "报告查询功能待接入");
    }
}
