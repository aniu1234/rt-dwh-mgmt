package com.rtdwh.dto;

import lombok.Data;

import java.util.List;

@Data
public class SyncTaskUpdateDTO {
    private String taskName;
    private String description;
    private String flinkSql;
    private String tableMappings;
    private Integer parallelism;
    private Long checkpointIntervalMs;
}
