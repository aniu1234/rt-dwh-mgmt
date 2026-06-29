package com.rtdwh.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class QueryExecuteDTO {

    @NotBlank(message = "SQL语句不能为空")
    private String sql;

    private Integer maxRows = 1000;

    private Integer timeoutSeconds = 60;
}
