package com.rtdwh.repository;

import com.rtdwh.entity.DwhDataLineage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DwhDataLineageRepository extends JpaRepository<DwhDataLineage, Long> {

    List<DwhDataLineage> findBySourceTableId(Long sourceTableId);

    List<DwhDataLineage> findByTargetTableId(Long targetTableId);

    List<DwhDataLineage> findBySyncTaskId(Long syncTaskId);
}
