package com.rtdwh.service;

import com.rtdwh.entity.AlertRule;
import com.rtdwh.entity.AlertRecord;
import com.rtdwh.repository.AlertRuleRepository;
import com.rtdwh.repository.AlertRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private static final Set<String> RULE_TYPES = Set.of("task_failure", "data_delay", "quality_failure");
    private static final Set<String> CHANNELS = Set.of("dingtalk", "wecom", "email");

    private final AlertRuleRepository ruleRepository;
    private final AlertRecordRepository recordRepository;

    @Transactional(readOnly = true)
    public List<AlertRule> listRules() {
        return ruleRepository.findAll();
    }

    @Transactional
    public AlertRule createRule(AlertRule rule) {
        validateAndNormalize(rule);
        rule.setId(null);
        LocalDateTime now = LocalDateTime.now();
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        return ruleRepository.save(rule);
    }

    @Transactional
    public AlertRule updateRule(Long id, AlertRule rule) {
        validateAndNormalize(rule);
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

    private void validateAndNormalize(AlertRule rule) {
        if (rule.getRuleName() == null || rule.getRuleName().isBlank()) {
            throw new IllegalArgumentException("规则名称不能为空");
        }
        rule.setRuleName(rule.getRuleName().trim());
        if (!RULE_TYPES.contains(rule.getRuleType())) {
            throw new IllegalArgumentException("不支持的告警类型: " + rule.getRuleType());
        }
        if ("data_delay".equals(rule.getRuleType())) {
            AlertRuleExpressionParser.delayThresholdMs(rule.getExpression());
        }
        if (rule.getEnabled() == null) rule.setEnabled(true);
        String normalizedChannels = Arrays.stream(
                        (rule.getNotifyChannel() == null ? "" : rule.getNotifyChannel()).split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .peek(value -> {
                    if (!CHANNELS.contains(value)) {
                        throw new IllegalArgumentException("不支持的通知渠道: " + value);
                    }
                })
                .distinct()
                .collect(Collectors.joining(","));
        rule.setNotifyChannel(normalizedChannels);
    }
}
