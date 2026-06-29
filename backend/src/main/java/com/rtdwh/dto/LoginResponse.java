package com.rtdwh.dto;

import lombok.Data;

@Data
public class LoginResponse {
    private String token;
    private Long id;
    private String username;
    private String realName;
    private String email;
    private String role;
}
