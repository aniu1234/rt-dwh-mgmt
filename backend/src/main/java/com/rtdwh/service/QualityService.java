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
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class QualityService {

    private static final Set<String> LAYERS = Set.of("ods", "dwd", "dws", "ads");
    private static final Set<String> RULE_TYPES = Set.of(
            "null_rate", "uniqueness", "volume_compare", "range_check");
    private static final Set<String> COLUMN_RULE_TYPES = Set.of(
            "null_rate", "uniqueness", "range_check");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SAFE_EXPRESSION = Pattern.compile(
            "^(?!.*(?:;|--|/\\*|\\*/|\\b(?:select|union|insert|update|delete|drop|alter|create|grant|revoke|sleep|benchmark|load_file|outfile)\\b)).+$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final QualityRuleRepository ruleRepository;
    private final QualityAlertRepository alertRepository;
    private final QualityCheckPersistenceService persistenceService;
    private final QueryAccessScopeService accessScopeService;
    private final DorisConnectionService dorisConnectionService;

    @Transactional(readOnly = true)
    public List<QualityRule> listRules(String layer, String ruleType, Long userId) {
        return filterAllowed(userId, ruleRepository.searchRules(layer, ruleType, null));
    }

    @Transactional
    public QualityRule createRule(QualityRule rule, Long userId) {
        rule.setId(null);
        rule.setVersion(null);
        normalizeAndValidate(rule);
        assertAccess(userId, rule);
        LocalDateTime now = LocalDateTime.now();
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        return ruleRepository.save(rule);
    }

    @Transactional
    public QualityRule updateRule(Long id, QualityRule input, Long userId) {
        QualityRule rule = ruleRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("质量规则不存在: " + id));
        assertAccess(userId, rule);
        rule.setRuleName(input.getRuleName());
        rule.setRuleType(input.getRuleType());
        rule.setLayer(input.getLayer());
        rule.setTargetTable(input.getTargetTable());
        rule.setTargetColumn(input.getTargetColumn());
        rule.setThreshold(input.getThreshold());
        rule.setExpression(input.getExpression());
        if (input.getEnabled() != null) {
            rule.setEnabled(input.getEnabled());
        }
        normalizeAndValidate(rule);
        assertAccess(userId, rule);
        resolveOpenAlerts(id, "suppressed");
        rule.setUpdatedAt(LocalDateTime.now());
        return ruleRepository.save(rule);
    }

    @Transactional
    public QualityRule setRuleEnabled(Long id, boolean enabled, Long userId) {
        QualityRule rule = ruleRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("质量规则不存在: " + id));
        assertAccess(userId, rule);
        rule.setEnabled(enabled);
        if (!enabled) resolveOpenAlerts(id, "suppressed");
        rule.setUpdatedAt(LocalDateTime.now());
        return ruleRepository.save(rule);
    }

    @Transactional
    public void deleteRule(Long id, Long userId) {
        QualityRule rule = ruleRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("质量规则不存在: " + id));
        assertAccess(userId, rule);
        resolveOpenAlerts(id, "suppressed");
        ruleRepository.delete(rule);
    }

    @Transactional(readOnly = true)
    public List<QualityAlert> listAlerts(String level, Boolean resolved, Long userId) {
        return alertRepository.searchAlerts(level, resolved, null).stream()
                .filter(alert -> canAccessAlert(userId, alert))
                .toList();
    }

    public QualityAlert resolveAlert(Long id, Long userId) {
        QualityAlert snapshot = alertRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("质量告警不存在: " + id));
        if (!canAccessAlert(userId, snapshot)) throw new IllegalArgumentException("无权处置该质量告警");
        return persistenceService.resolveAlertManually(id, snapshot.getRuleId());
    }

    public List<QualityRule> filterAllowed(Long userId, List<QualityRule> rules) {
        return rules.stream().filter(rule -> canAccess(userId, rule)).toList();
    }

    public void assertAccess(Long userId, QualityRule rule) {
        if (!canAccess(userId, rule)) throw new IllegalArgumentException("无权访问质量规则涉及的数据表");
    }

    private boolean canAccess(Long userId, QualityRule rule) {
        if (rule == null || rule.getTargetTable() == null) return false;
        String database = rule.getLayer() == null || rule.getLayer().isBlank()
                ? dorisConnectionService.getDatabase() : rule.getLayer();
        try {
            return accessScopeService.allowedReference(userId, rule.getTargetTable(),
                    dorisConnectionService.getCatalog(), database);
        } catch (IllegalArgumentException invalidReference) {
            return false;
        }
    }

    private boolean canAccessAlert(Long userId, QualityAlert alert) {
        if (alert.getRuleId() != null) {
            var rule = ruleRepository.findById(alert.getRuleId());
            if (rule.isPresent()) return canAccess(userId, rule.get());
        }
        try {
            return accessScopeService.allowedReference(userId, alert.getTargetTable(),
                    dorisConnectionService.getCatalog(), dorisConnectionService.getDatabase());
        } catch (IllegalArgumentException invalidReference) {
            return false;
        }
    }

    private void resolveOpenAlerts(Long ruleId, String reason) {
        List<QualityAlert> openAlerts = alertRepository
                .findByRuleIdAndResolvedFalseOrderByTriggeredAtDesc(ruleId);
        if (openAlerts.isEmpty()) return;
        LocalDateTime resolvedAt = LocalDateTime.now();
        openAlerts.forEach(alert -> {
            alert.setResolved(true);
            alert.setResolvedAt(resolvedAt);
            alert.setResolutionReason(reason);
        });
        alertRepository.saveAll(openAlerts);
    }

    private void normalizeAndValidate(QualityRule rule) {
        rule.setRuleName(requiredText(rule.getRuleName(), "规则名称", 100));

        String ruleType = requiredText(rule.getRuleType(), "规则类型", 50).toLowerCase(Locale.ROOT);
        if (!RULE_TYPES.contains(ruleType)) throw new IllegalArgumentException("不支持的质量规则类型: " + ruleType);
        rule.setRuleType(ruleType);

        String layer = requiredText(rule.getLayer(), "数仓分层", 20).toLowerCase(Locale.ROOT);
        if (!LAYERS.contains(layer)) throw new IllegalArgumentException("数仓分层仅支持 ODS、DWD、DWS、ADS");
        rule.setLayer(layer);

        String targetTable = requiredText(rule.getTargetTable(), "目标表", 100).replace("`", "");
        String[] tableParts = targetTable.split("\\.", -1);
        if (tableParts.length > 3 || java.util.Arrays.stream(tableParts).anyMatch(part -> !IDENTIFIER.matcher(part).matches())) {
            throw new IllegalArgumentException("目标表必须是 table、database.table 或 catalog.database.table");
        }
        rule.setTargetTable(targetTable);

        Double threshold = rule.getThreshold();
        if (threshold == null || !Double.isFinite(threshold)) throw new IllegalArgumentException("阈值不能为空");
        if ("volume_compare".equals(ruleType)) {
            if (threshold < 0 || threshold != Math.rint(threshold)) {
                throw new IllegalArgumentException("数据量下限必须是非负整数");
            }
            rule.setTargetColumn(null);
            rule.setExpression(null);
        } else {
            if (threshold < 0 || threshold > 1) throw new IllegalArgumentException("比率阈值必须在 0 到 1 之间");
            String targetColumn = requiredText(rule.getTargetColumn(), "目标字段", 100).replace("`", "");
            if (!IDENTIFIER.matcher(targetColumn).matches()) throw new IllegalArgumentException("目标字段格式不正确");
            rule.setTargetColumn(targetColumn);
            if ("range_check".equals(ruleType)) {
                String expression = requiredText(rule.getExpression(), "范围检查表达式", 500);
                if (!SAFE_EXPRESSION.matcher(expression).matches()) {
                    throw new IllegalArgumentException("范围检查表达式包含不安全内容");
                }
                rule.setExpression(expression);
            } else {
                rule.setExpression(null);
            }
        }

        if (!COLUMN_RULE_TYPES.contains(ruleType)) rule.setTargetColumn(null);
        if (rule.getEnabled() == null) rule.setEnabled(true);
    }

    private String requiredText(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException(label + "不能超过 " + maxLength + " 个字符");
        return normalized;
    }
}
