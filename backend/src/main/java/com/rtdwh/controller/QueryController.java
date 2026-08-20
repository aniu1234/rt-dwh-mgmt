package com.rtdwh.controller;

import com.rtdwh.dto.ApiResponse;
import com.rtdwh.dto.QueryExecuteDTO;
import com.rtdwh.entity.QueryHistory;
import com.rtdwh.service.QueryService;
import com.rtdwh.service.ReportService;
import com.rtdwh.util.SecurityContextUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/query")
@RequiredArgsConstructor
public class QueryController {

    private final QueryService queryService;
    private final ReportService reportService;
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
    public ResponseEntity<byte[]> exportQuery(@Valid @RequestBody QueryExecuteDTO dto) {
        Long userId = securityContextUtil.getCurrentUserId();
        byte[] csv = queryService.exportToCsv(dto, userId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=query-result.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv);
    }

    /**
     * Cancel a running query.
     */
    @PostMapping("/cancel/{historyId}")
    public ApiResponse<Void> cancelQuery(@PathVariable Long historyId) {
        Long userId = securityContextUtil.getCurrentUserId();
        queryService.cancelQuery(historyId, userId);
        return ApiResponse.success("查询已取消", null);
    }

    @PostMapping("/cancel-request/{requestId}")
    public ApiResponse<Void> cancelQueryByRequestId(@PathVariable String requestId) {
        Long userId = securityContextUtil.getCurrentUserId();
        queryService.cancelQueryByRequestId(requestId, userId);
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
        com.rtdwh.entity.ReportTemplate report = reportService.getReport(reportId);
        if (!Boolean.TRUE.equals(report.getIsPublished())) {
            throw new IllegalStateException("报告尚未发布，无法查询");
        }
        // Report filters are deliberately not concatenated into SQL. Templates must
        // contain safe, read-only SQL; parameter binding can be added per connector.
        return ApiResponse.success(queryService.executeReportQuery(report.getSqlQuery(), userId));
    }
}
