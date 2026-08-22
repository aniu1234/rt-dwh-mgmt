package com.rtdwh.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

public final class WorkflowDTO {
    private WorkflowDTO() {}

    @Data
    public static class DependencyRequest {
        @NotNull private Long upstreamTaskId;
        @NotNull private Long downstreamTaskId;
    }

    @Data
    public static class PublishRequest {
        @NotBlank private String changeSummary;
    }

    @Data
    public static class BackfillRequest {
        @NotNull private LocalDate startDate;
        @NotNull private LocalDate endDate;
        private String parametersJson;
    }

    @Data
    public static class CompleteRequest {
        @NotNull private Boolean success;
        private String errorMessage;
    }
}
