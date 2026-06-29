package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "quality_alert")
public class QualityAlert {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String ruleType;

    @Column(length = 100)
    private String targetTable;

    @Column(length = 100)
    private String targetColumn;

    private Double actualValue;

    private Double thresholdValue;

    @Column(length = 500)
    private String message;

    @Column(length = 20)
    private String level; // info, warn, error

    private Long ruleId;

    @Builder.Default
    @Column(nullable = false)
    private Boolean resolved = false;

    private LocalDateTime resolvedAt;

    private LocalDateTime triggeredAt;
}
