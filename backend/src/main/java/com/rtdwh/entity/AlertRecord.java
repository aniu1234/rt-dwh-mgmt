package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "alert_record")
public class AlertRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long ruleId;

    @Column(length = 160)
    private String dedupKey;

    @Column(nullable = false, length = 50)
    private String ruleType;

    @Column(length = 500)
    private String message;

    @Column(length = 20)
    private String level; // info, warn, error

    @Column(nullable = false)
    private Boolean resolved = false;

    private LocalDateTime resolvedAt;

    private LocalDateTime recoveredAt;

    private LocalDateTime lastEvaluatedAt;

    @Column(length = 20)
    private String notificationStatus;

    private LocalDateTime triggeredAt;
}
