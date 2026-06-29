package com.rtdwh.repository;

import com.rtdwh.entity.DwhSchemaHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DwhSchemaHistoryRepository extends JpaRepository<DwhSchemaHistory, Long> {

    List<DwhSchemaHistory> findByTableMetaIdOrderByDetectedAtDesc(Long tableMetaId);
}
