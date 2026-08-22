package com.rtdwh.controller;

import com.rtdwh.dto.ApiResponse;
import com.rtdwh.entity.OperationAudit;
import com.rtdwh.repository.OperationAuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/audit")
@RequiredArgsConstructor
public class AuditController {
    private final OperationAuditRepository repository;

    @GetMapping
    @PreAuthorize("hasAuthority('audit:view')")
    public ApiResponse<Page<OperationAudit>> list(@RequestParam(defaultValue = "") String username,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 100)),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.success(username.isBlank() ? repository.findAll(pageable)
                : repository.findByUsernameContainingIgnoreCase(username, pageable));
    }
}
