package com.rtdwh.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class SyncTaskCreateDTO {

    @NotBlank(message = "任务名称不能为空")
    @Size(min = 1, max = 128, message = "任务名称长度必须在1-128之间")
    private String taskName;

    private String description;

    @NotNull(message = "任务类型不能为空")
    private String taskType; // cdc_sync, etl, materialized

    @NotNull(message = "源数据源配置ID不能为空")
    private Long sourceConfigId;

    @NotNull(message = "目标数据源配置ID不能为空")
    private Long targetConfigId;

    @NotBlank(message = "Flink SQL不能为空")
    private String flinkSql;

    @NotNull(message = "同步策略不能为空")
    private String syncStrategy; // full_then_incremental, incremental_only

    private String tableMappings; // JSON

    @Min(value = 1, message = "并行度必须大于0")
    private Integer parallelism; // default 1

    @Min(value = 1000, message = "Checkpoint间隔必须≥1000ms")
    private Long checkpointIntervalMs; // default 60000
}
