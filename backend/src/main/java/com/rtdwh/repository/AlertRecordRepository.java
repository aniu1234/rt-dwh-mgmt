package com.rtdwh.repository;

import com.rtdwh.entity.AlertRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AlertRecordRepository extends JpaRepository<AlertRecord, Long> {

    Optional<AlertRecord> findFirstByRuleIdAndDedupKeyAndResolvedFalseOrderByTriggeredAtDesc(
            Long ruleId, String dedupKey);

    List<AlertRecord> findByRuleIdAndResolvedFalse(Long ruleId);

    List<AlertRecord> findByResolved(Boolean resolved);

    List<AlertRecord> findByLevel(String level);

    List<AlertRecord> findByResolvedAndLevel(Boolean resolved, String level);

    @Query("SELECT r FROM AlertRecord r WHERE " +
           "(:level IS NULL OR r.level = :level) AND " +
           "(:resolved IS NULL OR r.resolved = :resolved) AND " +
           "(:ruleType IS NULL OR r.ruleType = :ruleType) " +
           "ORDER BY r.triggeredAt DESC")
    List<AlertRecord> searchRecords(@Param("level") String level,
                                     @Param("resolved") Boolean resolved,
                                     @Param("ruleType") String ruleType);
}
