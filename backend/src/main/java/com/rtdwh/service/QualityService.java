package com.rtdwh.service;

import com.rtdwh.entity.QualityRule;
import com.rtdwh.entity.QualityAlert;
import com.rtdwh.repository.QualityRuleRepository;
import com.rtdwh.repository.QualityAlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QualityService {

    private final QualityRuleRepository ruleRepository;
    private final QualityAlertRepository alertRepository;

    @Transactional(readOnly = true)
    public List<QualityRule> listRules(String layer, String ruleType) {
        return ruleRepository.searchRules(layer, ruleType, null);
    }

    @Transactional
    public QualityRule createRule(QualityRule rule) {
        rule.setId(null);
        LocalDateTime now = LocalDateTime.now();
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        return ruleRepository.save(rule);
    }

    @Transactional(readOnly = true)
    public List<QualityAlert> listAlerts(String level, Boolean resolved) {
        return alertRepository.searchAlerts(level, resolved, null);
    }
}
