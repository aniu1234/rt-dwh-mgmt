package com.rtdwh.repository;

import com.rtdwh.entity.QualityRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QualityRuleRepository extends JpaRepository<QualityRule, Long> {

    List<QualityRule> findByEnabled(Boolean enabled);
    long countByEnabledTrue();

    List<QualityRule> findByRuleType(String ruleType);

    List<QualityRule> findByLayer(String layer);

    List<QualityRule> findByLayerAndRuleType(String layer, String ruleType);

    @Query("SELECT r FROM QualityRule r WHERE " +
           "(:layer IS NULL OR r.layer = :layer) AND " +
           "(:ruleType IS NULL OR r.ruleType = :ruleType) AND " +
           "(:enabled IS NULL OR r.enabled = :enabled)")
    List<QualityRule> searchRules(@Param("layer") String layer,
                                   @Param("ruleType") String ruleType,
                                   @Param("enabled") Boolean enabled);
}
