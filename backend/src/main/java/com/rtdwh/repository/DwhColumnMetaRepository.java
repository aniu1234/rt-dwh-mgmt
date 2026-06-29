package com.rtdwh.repository;

import com.rtdwh.entity.DwhColumnMeta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DwhColumnMetaRepository extends JpaRepository<DwhColumnMeta, Long> {

    List<DwhColumnMeta> findByTableMetaIdOrderBySortOrder(Long tableMetaId);
}
