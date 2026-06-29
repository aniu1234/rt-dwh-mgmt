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
@Table(name = "dwh_schema_history")
public class DwhSchemaHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "table_meta_id", nullable = false)
    private DwhTableMeta tableMeta;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChangeType changeType;

    @Column(columnDefinition = "JSON")
    private String beforeSchema;

    @Column(columnDefinition = "JSON")
    private String afterSchema;

    @Column(length = 1024)
    private String changeDetail;

    @CreationTimestamp
    @Column(name = "detected_at", updatable = false)
    private LocalDateTime detectedAt;

    public enum ChangeType {
        add_column, drop_column, rename_column, alter_type
    }
}
