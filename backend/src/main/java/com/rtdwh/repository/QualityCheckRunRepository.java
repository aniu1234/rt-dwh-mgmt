package com.rtdwh.repository;

import com.rtdwh.entity.QualityCheckRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QualityCheckRunRepository extends JpaRepository<QualityCheckRun, Long> {
    List<QualityCheckRun> findTop100ByOrderByStartedAtDesc();
    List<QualityCheckRun> findTop100ByRuleIdOrderByStartedAtDesc(Long ruleId);
}
