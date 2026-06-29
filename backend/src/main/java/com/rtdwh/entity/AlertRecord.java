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

    @Column(nullable = false, length = 50)
    private String ruleType;

    @Column(length = 500)
    private String message;

    @Column(length = 20)
    private String level; // info, warn, error

    @Column(nullable = false)
    private Boolean resolved = false;

    private LocalDateTime resolvedAt;

    private LocalDateTime triggeredAt;
}
