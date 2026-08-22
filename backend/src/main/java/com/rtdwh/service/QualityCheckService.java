package com.rtdwh.service;

import com.rtdwh.entity.QualityAlert;
import com.rtdwh.entity.QualityCheckRun;
import com.rtdwh.entity.QualityRule;
import com.rtdwh.repository.QualityAlertRepository;
import com.rtdwh.repository.QualityCheckRunRepository;
import com.rtdwh.repository.QualityRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class QualityCheckService {
    private static final Pattern SAFE_EXPRESSION = Pattern.compile(
            "^(?!.*(?:;|--|/\\*|\\*/|\\b(?:insert|update|delete|drop|alter|create|grant|revoke|outfile)\\b)).+$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final QualityRuleRepository ruleRepository;
    private final QualityAlertRepository alertRepository;
    private final QualityCheckRunRepository runRepository;
    private final AlertNotifyService alertNotifyService;
    private final DorisConnectionService dorisConnectionService;

    @Transactional
    public int runAllChecks() {
        return runAllChecks("manual");
    }

    @Transactional
    public int runAllChecks(String triggerType) {
        String batchId = UUID.randomUUID().toString();
        List<QualityRule> rules = ruleRepository.findByEnabled(true);
        int alertCount = 0;
        for (QualityRule rule : rules) {
            alertCount += checkRule(rule, batchId, normalizeTrigger(triggerType));
        }
        log.info("Quality batch [{}] completed: rules={}, alerts={}", batchId, rules.size(), alertCount);
        return alertCount;
    }

    @Transactional
    public int runChecksByLayer(String layer) {
        String batchId = UUID.randomUUID().toString();
        return ruleRepository.findByLayerAndRuleType(layer, null).stream()
                .filter(rule -> Boolean.TRUE.equals(rule.getEnabled()))
                .mapToInt(rule -> checkRule(rule, batchId, "manual"))
                .sum();
    }

    @Transactional
    public int runCheck(Long ruleId) {
        QualityRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("质量规则不存在: " + ruleId));
        if (!Boolean.TRUE.equals(rule.getEnabled())) {
            throw new IllegalStateException("质量规则未启用: " + ruleId);
        }
        return checkRule(rule, UUID.randomUUID().toString(), "manual");
    }

    @Transactional
    public int runChecksForTable(String database, String table) {
        String batchId = UUID.randomUUID().toString();
        return ruleRepository.findByEnabled(true).stream()
                .filter(rule -> matchesTable(rule.getTargetTable(), database, table))
                .mapToInt(rule -> checkRule(rule, batchId, "production"))
                .sum();
    }

    @Transactional(readOnly = true)
    public List<QualityCheckRun> listRuns(Long ruleId) {
        return ruleId == null
                ? runRepository.findTop100ByOrderByStartedAtDesc()
                : runRepository.findTop100ByRuleIdOrderByStartedAtDesc(ruleId);
    }

    private int checkRule(QualityRule rule, String batchId, String triggerType) {
        String sql = generateCheckSql(rule);
        QualityCheckRun run = runRepository.save(QualityCheckRun.builder()
                .batchId(batchId)
                .ruleId(rule.getId())
                .ruleName(rule.getRuleName())
                .triggerType(triggerType)
                .engine("doris")
                .status("running")
                .checkSql(sql)
                .thresholdValue(rule.getThreshold())
                .startedAt(LocalDateTime.now())
                .build());
        long started = System.currentTimeMillis();
        try {
            double actualValue = executeViaDoris(sql);
            double threshold = rule.getThreshold() == null ? 0.0 : rule.getThreshold();
            boolean exceeded = isThresholdExceeded(rule.getRuleType(), actualValue, threshold);
            run.setActualValue(actualValue);
            run.setStatus(exceeded ? "failed" : "passed");
            if (exceeded) {
                String message = buildAlertMessage(rule, actualValue, threshold);
                createAlert(rule, actualValue, threshold, determineAlertLevel(actualValue, threshold), message);
                alertNotifyService.sendQualityAlert(rule, actualValue, threshold, message);
                return 1;
            }
            return 0;
        } catch (Exception exception) {
            String error = conciseError(exception);
            run.setStatus("error");
            run.setErrorMessage(error);
            createAlert(rule, -1.0, rule.getThreshold(), "error", "质量检查执行失败: " + error);
            log.error("Quality rule [{}] execution failed: {}", rule.getId(), error);
            return 1;
        } finally {
            run.setDurationMs(System.currentTimeMillis() - started);
            run.setFinishedAt(LocalDateTime.now());
            runRepository.save(run);
        }
    }

    String generateCheckSql(QualityRule rule) {
        String table = qualifiedTable(rule);
        String column = rule.getTargetColumn() == null || rule.getTargetColumn().isBlank()
                ? null : DorisConnectionService.quoteIdentifier(stripQuotes(rule.getTargetColumn()));
        return switch (rule.getRuleType()) {
            case "null_rate" -> {
                requireColumn(column, rule.getRuleType());
                yield "SELECT CAST(SUM(CASE WHEN " + column + " IS NULL THEN 1 ELSE 0 END) AS DOUBLE) "
                        + "/ NULLIF(COUNT(*), 0) FROM " + table;
            }
            case "uniqueness" -> {
                requireColumn(column, rule.getRuleType());
                yield "SELECT CAST(COUNT(DISTINCT " + column + ") AS DOUBLE) / NULLIF(COUNT(*), 0) FROM " + table;
            }
            case "volume_compare" -> "SELECT CAST(COUNT(*) AS DOUBLE) FROM " + table;
            case "range_check" -> {
                requireColumn(column, rule.getRuleType());
                String expression = validateExpression(rule.getExpression());
                yield "SELECT CAST(SUM(CASE WHEN NOT (" + expression + ") THEN 1 ELSE 0 END) AS DOUBLE) "
                        + "/ NULLIF(COUNT(*), 0) FROM " + table;
            }
            default -> throw new IllegalArgumentException("不支持的质量规则类型: " + rule.getRuleType());
        };
    }

    private double executeViaDoris(String sql) throws Exception {
        try (Connection connection = dorisConnectionService.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(120);
            statement.execute("SWITCH " + DorisConnectionService.quoteIdentifier(dorisConnectionService.getCatalog()));
            statement.execute("SET query_timeout = 120");
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                if (!resultSet.next()) throw new IllegalStateException("Doris 未返回质量指标");
                double value = resultSet.getDouble(1);
                return resultSet.wasNull() ? 0.0 : value;
            }
        }
    }

    private String qualifiedTable(QualityRule rule) {
        String raw = stripQuotes(rule.getTargetTable());
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("表名不能为空");
        String[] parts = raw.split("\\.");
        String catalog = dorisConnectionService.getCatalog();
        String database = rule.getLayer() == null || rule.getLayer().isBlank()
                ? dorisConnectionService.getDatabase() : stripQuotes(rule.getLayer()).toLowerCase(Locale.ROOT);
        return switch (parts.length) {
            case 1 -> quotePath(catalog, database, parts[0]);
            case 2 -> quotePath(catalog, parts[0], parts[1]);
            case 3 -> quotePath(parts[0], parts[1], parts[2]);
            default -> throw new IllegalArgumentException("表名必须是 table、database.table 或 catalog.database.table");
        };
    }

    private String quotePath(String... parts) {
        return Arrays.stream(parts)
                .map(DorisConnectionService::quoteIdentifier)
                .reduce((left, right) -> left + "." + right)
                .orElseThrow();
    }

    private String stripQuotes(String value) {
        return value == null ? null : value.trim().replace("`", "");
    }

    private String validateExpression(String expression) {
        if (expression == null || expression.isBlank()) throw new IllegalArgumentException("范围检查表达式不能为空");
        if (!SAFE_EXPRESSION.matcher(expression.trim()).matches()) {
            throw new IllegalArgumentException("范围检查表达式包含不安全内容");
        }
        return expression.trim();
    }

    private void requireColumn(String column, String ruleType) {
        if (column == null) throw new IllegalArgumentException(ruleType + " 规则必须配置目标字段");
    }

    private boolean isThresholdExceeded(String ruleType, double actual, double threshold) {
        return switch (ruleType) {
            case "null_rate", "range_check" -> actual > threshold;
            case "uniqueness", "volume_compare" -> actual < threshold;
            default -> throw new IllegalArgumentException("不支持的质量规则类型: " + ruleType);
        };
    }

    private String determineAlertLevel(double actual, double threshold) {
        double deviation = Math.abs(actual - threshold) / Math.max(Math.abs(threshold), 0.01);
        if (deviation > 2.0) return "error";
        if (deviation > 1.0) return "warn";
        return "info";
    }

    private String buildAlertMessage(QualityRule rule, double actual, double threshold) {
        String direction = List.of("null_rate", "range_check").contains(rule.getRuleType()) ? "超过" : "低于";
        return String.format("质量检查异常: 表 %s 列 %s 的 %s 实际值 %.4f %s阈值 %.4f",
                rule.getTargetTable(), rule.getTargetColumn() == null ? "(全表)" : rule.getTargetColumn(),
                rule.getRuleType(), actual, direction, threshold);
    }

    private void createAlert(QualityRule rule, double actualValue, Double thresholdValue,
                             String level, String message) {
        alertRepository.save(QualityAlert.builder()
                .ruleType(rule.getRuleType())
                .targetTable(rule.getTargetTable())
                .targetColumn(rule.getTargetColumn())
                .actualValue(actualValue)
                .thresholdValue(thresholdValue)
                .message(message)
                .level(level)
                .ruleId(rule.getId())
                .resolved(false)
                .triggeredAt(LocalDateTime.now())
                .build());
    }

    private String normalizeTrigger(String triggerType) {
        if ("production".equalsIgnoreCase(triggerType)) return "production";
        return "scheduled".equalsIgnoreCase(triggerType) ? "scheduled" : "manual";
    }

    private boolean matchesTable(String target, String database, String table) {
        if (target == null) return false;
        String normalized = stripQuotes(target).toLowerCase(Locale.ROOT);
        String dbTable = database.toLowerCase(Locale.ROOT) + "." + table.toLowerCase(Locale.ROOT);
        return normalized.equals(table.toLowerCase(Locale.ROOT)) || normalized.equals(dbTable)
                || normalized.endsWith("." + dbTable);
    }

    private String conciseError(Exception exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return message.length() > 1800 ? message.substring(0, 1800) + "..." : message;
    }
}
