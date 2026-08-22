package com.rtdwh.repository;

import com.rtdwh.entity.DatasetProduction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DatasetProductionRepository extends JpaRepository<DatasetProduction, Long> {
    List<DatasetProduction> findByOutputDatasetIdOrderByProducedAtDesc(Long outputDatasetId, Pageable pageable);
}
