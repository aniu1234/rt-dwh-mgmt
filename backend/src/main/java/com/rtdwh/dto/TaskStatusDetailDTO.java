package com.rtdwh.dto;

import lombok.Data;

@Data
public class TaskStatusDetailDTO implements java.io.Serializable{

    private String taskStatus;
    private String flinkJobId;
    private String flinkJobStatus; // Flink's own status: RUNNING, CANCELED, FAILED, FINISHED, etc.
    private Long currentLagMs;
    private Double throughputQps;
    private Object checkpointInfo;
    private String lastErrorMsg;
    private Long checkpointCount;
    private String lastCheckpointTime;
    private String savepointTriggerId;
    private String savepointProgress; // PENDING, COMPLETED, FAILED
}
