package com.rtdwh.controller;

import com.rtdwh.dto.ApiResponse;
import com.rtdwh.entity.DwhColumnMeta;
import com.rtdwh.entity.DwhTableMeta;
import com.rtdwh.entity.DwhTableMeta.TableLayer;
import com.rtdwh.entity.TableMaintenanceLog;
import com.rtdwh.entity.TableMaintenanceLog.Operation;
import com.rtdwh.entity.TableMaintenanceLog.Status;
import com.rtdwh.service.DwhMetaService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/dwh")
@RequiredArgsConstructor
public class DwhMetaController {

    private final DwhMetaService dwhMetaService;

    @GetMapping("/tables")
    public ApiResponse<List<DwhTableMeta>> listTables(
            @RequestParam(required = false) String layer,
            @RequestParam(required = false) String database,
            @RequestParam(required = false) String keyword) {

        TableLayer layerEnum = layer != null ? TableLayer.valueOf(layer) : null;
        return ApiResponse.success(dwhMetaService.listTables(layerEnum, database, keyword));
    }

    @GetMapping("/tables/{id}")
    public ApiResponse<DwhTableMeta> getTableDetail(@PathVariable Long id) {
        return ApiResponse.success(dwhMetaService.getTableDetail(id));
    }

    @GetMapping("/tables/{id}/columns")
    public ApiResponse<List<DwhColumnMeta>> getTableColumns(@PathVariable Long id) {
        return ApiResponse.success(dwhMetaService.getTableColumns(id));
    }

    @PutMapping("/tables/{id}/metadata")
    public ApiResponse<DwhTableMeta> updateBusinessDesc(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        return ApiResponse.success(dwhMetaService.updateBusinessDesc(id, body.get("businessDesc")));
    }

    @PostMapping("/sync-metadata")
    public ApiResponse<Integer> syncMetadataFromPaimon() {
        return ApiResponse.success("Metadata synced", dwhMetaService.syncMetadataFromPaimon());
    }

    @PostMapping("/tables/{id}/compact")
    public ApiResponse<Map<String, Object>> triggerCompact(
            @PathVariable Long id,
            @RequestParam(defaultValue = "minor") String compactStrategy) {
        return ApiResponse.success(dwhMetaService.triggerCompact(id, compactStrategy));
    }

    @PostMapping("/tables/{id}/expire-snapshots")
    public ApiResponse<Map<String, Object>> triggerExpireSnapshots(
            @PathVariable Long id,
            @RequestParam(defaultValue = "10") int retainLast) {
        return ApiResponse.success(dwhMetaService.triggerExpireSnapshots(id, retainLast));
    }

    @PostMapping("/tables/{id}/orphan-cleanup")
    public ApiResponse<Map<String, Object>> triggerOrphanCleanup(@PathVariable Long id) {
        return ApiResponse.success(dwhMetaService.triggerOrphanCleanup(id));
    }

    // ======== Maintenance Logs ========

    @GetMapping("/maintenance/logs")
    public ApiResponse<List<TableMaintenanceLog>> getMaintenanceLogs(
            @RequestParam(required = false) Long tableMetaId,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String status) {
        Operation opEnum = operation != null ? Operation.valueOf(operation) : null;
        Status statusEnum = status != null ? Status.valueOf(status) : null;
        return ApiResponse.success(dwhMetaService.getMaintenanceLogs(tableMetaId, opEnum, statusEnum));
    }
}
