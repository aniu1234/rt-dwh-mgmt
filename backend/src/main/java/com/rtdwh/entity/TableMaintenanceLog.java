package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "table_maintenance_log")
public class TableMaintenanceLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long tableMetaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Operation operation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TriggerType triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(columnDefinition = "JSON")
    private String beforeMetrics;

    @Column(columnDefinition = "JSON")
    private String afterMetrics;

    private Long durationMs;

    @Column(columnDefinition = "TEXT")
    private String errorMsg;

    @Column(length = 64)
    private String operationId;

    @Column(columnDefinition = "TEXT", name = "sql_content")
    private String sqlContent;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    public enum Operation {
        compact, expire_snapshots, orphan_cleanup
    }

    public enum TriggerType {
        manual, scheduled
    }

    public enum Status {
        running, success, failed, pending
    }
}
