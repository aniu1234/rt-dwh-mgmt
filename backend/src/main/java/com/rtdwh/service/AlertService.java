package com.rtdwh.service;

import com.rtdwh.entity.AlertRule;
import com.rtdwh.entity.AlertRecord;
import com.rtdwh.repository.AlertRuleRepository;
import com.rtdwh.repository.AlertRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertRuleRepository ruleRepository;
    private final AlertRecordRepository recordRepository;

    @Transactional(readOnly = true)
    public List<AlertRule> listRules() {
        return ruleRepository.findAll();
    }

    @Transactional
    public AlertRule createRule(AlertRule rule) {
        rule.setId(null);
        LocalDateTime now = LocalDateTime.now();
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        return ruleRepository.save(rule);
    }

    @Transactional
    public AlertRule updateRule(Long id, AlertRule rule) {
        AlertRule existing = ruleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("告警规则不存在: " + id));
        rule.setId(existing.getId());
        rule.setCreatedAt(existing.getCreatedAt());
        rule.setUpdatedAt(LocalDateTime.now());
        return ruleRepository.save(rule);
    }

    @Transactional
    public void deleteRule(Long id) {
        ruleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("告警规则不存在: " + id));
        ruleRepository.deleteById(id);
    }

    @Transactional
    public AlertRule toggleRule(Long id) {
        AlertRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("告警规则不存在: " + id));
        rule.setEnabled(!rule.getEnabled());
        rule.setUpdatedAt(LocalDateTime.now());
        return ruleRepository.save(rule);
    }

    @Transactional(readOnly = true)
    public List<AlertRecord> listRecords(String level, Boolean resolved) {
        return recordRepository.searchRecords(level, resolved, null);
    }

    @Transactional
    public AlertRecord resolveRecord(Long id) {
        AlertRecord record = recordRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("告警记录不存在: " + id));
        record.setResolved(true);
        record.setResolvedAt(LocalDateTime.now());
        return recordRepository.save(record);
    }
}
