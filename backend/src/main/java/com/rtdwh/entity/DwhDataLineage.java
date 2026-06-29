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
@Table(name = "dwh_data_lineage")
public class DwhDataLineage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_table_id", nullable = false)
    private DwhTableMeta sourceTable;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_table_id", nullable = false)
    private DwhTableMeta targetTable;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sync_task_id")
    private SyncTask syncTask;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LineageType lineageType;

    @Column(columnDefinition = "TEXT")
    private String transformLogic;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public enum LineageType {
        cdc_sync, etl_transform, materialized
    }
}
