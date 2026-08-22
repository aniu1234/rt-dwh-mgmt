package com.rtdwh.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.time.LocalDateTime;

public final class DataServiceDTO {
    private DataServiceDTO() {}
    @Data public static class DefinitionRequest {
        @NotBlank @Pattern(regexp="^[a-z][a-z0-9_-]{2,63}$") private String serviceCode;
        @NotBlank @Size(max=128) private String serviceName;
        @Size(max=512) private String description;
        @NotBlank private String sqlTemplate;
        private String parameterConfig;
        @NotBlank private String catalogName = "rtdwh_paimon";
        @NotBlank private String databaseName = "ods";
        @Min(1) @Max(50000) private Integer maxRows = 1000;
        @Min(1) @Max(1800) private Integer timeoutSeconds = 30;
        @Min(1) @Max(100000) private Integer rateLimitPerMinute = 60;
    }
    @Data public static class AppRequest {
        @NotBlank @Size(max=128) private String appName;
        @Future private LocalDateTime expiresAt;
    }
    @Data public static class GrantRequest { @NotNull private Long serviceId; }
    public record AppCredential(Long id, String appName, String appKey, String appSecret,
                                Boolean enabled, LocalDateTime expiresAt) {}
}
