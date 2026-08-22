package com.rtdwh.service;

import com.rtdwh.entity.AlertRule;
import com.rtdwh.entity.QualityRule;
import com.rtdwh.entity.ReportRun;
import com.rtdwh.entity.ReportTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertNotifyService {

    private final JavaMailSender mailSender;
    private final RestTemplate restTemplate;

    @Value("${alert.dingtalk-webhook:}")
    private String dingtalkWebhook;

    @Value("${alert.wecom-webhook:}")
    private String wecomWebhook;

    @Value("${alert.email-recipients:admin@example.com}")
    private String emailRecipients;

    @Value("${alert.email-from:rtdwh-alert@example.com}")
    private String emailFrom;

    @Value("${spring.mail.host:}")
    private String mailHost;

    /**
     * Send an alert notification based on AlertRule's notify channel.
     * This is called when a task fails, data lag exceeds threshold, etc.
     */
    public void sendAlert(AlertRule rule, String message, String level) {
        sendAlertWithResult(rule, message, level);
    }

    /** Sends to every comma-separated channel and reports whether at least one delivery succeeded. */
    public boolean sendAlertWithResult(AlertRule rule, String message, String level) {
        String channel = rule.getNotifyChannel();
        if (channel == null || channel.isEmpty()) {
            log.warn("Alert rule [{}] has no notify channel configured, skipping notification", rule.getRuleName());
            return false;
        }

        String formattedMessage = formatAlertMessage(rule.getRuleName(), rule.getRuleType(), message, level);

        boolean delivered = false;
        for (String item : channel.split(",")) {
            delivered |= switch (item.trim().toLowerCase()) {
                case "dingtalk" -> sendDingtalk(formattedMessage, level);
                case "wecom" -> sendWecom(formattedMessage, level);
                case "email" -> sendEmail(formattedMessage, level, rule.getRuleName());
                default -> {
                    log.warn("Unknown notify channel: {}", item);
                    yield false;
                }
            };
        }
        return delivered;
    }

    /**
     * Send a quality check alert notification.
     * Uses the quality rule's target table/column info to build the message.
     */
    public void sendQualityAlert(QualityRule rule, double actualValue, double thresholdValue, String message) {
        // Determine which channels to send to based on available configuration
        // Quality alerts go to all configured channels
        String formattedMessage = formatQualityAlertMessage(rule, actualValue, thresholdValue, message);

        if (!dingtalkWebhook.isEmpty()) {
            sendDingtalk(formattedMessage, "warn");
        }
        if (!wecomWebhook.isEmpty()) {
            sendWecom(formattedMessage, "warn");
        }
        if (!mailHost.isEmpty()) {
            sendEmail(formattedMessage, "warn", "质量检查异常: " + rule.getTargetTable());
        }
    }

    /**
     * Send a task failure alert.
     * Called from SyncTaskService when a Flink job fails.
     */
    public void sendTaskFailureAlert(String taskName, String errorMsg) {
        String message = String.format("【实时数仓告警】同步任务失败\n" +
                "任务: %s\n时间: %s\n错误: %s",
                taskName,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                errorMsg != null ? errorMsg : "未知错误");

        if (!dingtalkWebhook.isEmpty()) {
            sendDingtalk(message, "error");
        }
        if (!wecomWebhook.isEmpty()) {
            sendWecom(message, "error");
        }
        if (!mailHost.isEmpty()) {
            sendEmail(message, "error", "同步任务失败: " + taskName);
        }
    }

    /**
     * Send a data lag exceeded alert.
     */
    public void sendLagAlert(String taskName, long lagMs, long thresholdMs) {
        String message = String.format("【实时数仓告警】数据延迟超标\n" +
                "任务: %s\n当前延迟: %d ms\n阈值: %d ms\n时间: %s",
                taskName, lagMs, thresholdMs,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        if (!dingtalkWebhook.isEmpty()) {
            sendDingtalk(message, "warn");
        }
        if (!wecomWebhook.isEmpty()) {
            sendWecom(message, "warn");
        }
        if (!mailHost.isEmpty()) {
            sendEmail(message, "warn", "数据延迟超标: " + taskName);
        }
    }

    public DeliveryResult sendReportResult(ReportTemplate report, ReportRun run, ReportScheduleConfig config) {
        String subject = "报表运行" + ("success".equals(run.getStatus()) ? "成功" : "失败") + ": " + report.getReportName();
        String content = String.format("【实时数仓报表】\n报表: %s\n状态: %s\n触发方式: %s\n返回行数: %s\n耗时: %s ms\n错误: %s\n完成时间: %s",
                report.getReportName(), run.getStatus(), run.getTriggerType(),
                run.getRowCount() == null ? "—" : run.getRowCount(),
                run.getDurationMs() == null ? "—" : run.getDurationMs(),
                run.getErrorMessage() == null ? "—" : run.getErrorMessage(),
                run.getFinishedAt() == null ? "—" : run.getFinishedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        List<String> errors = new ArrayList<>();
        int delivered = 0;
        for (String channel : config.notifyChannels()) {
            boolean success = switch (channel) {
                case "email" -> sendEmailTo(content, "success".equals(run.getStatus()) ? "info" : "error",
                        subject, config.recipients().isEmpty() ? emailRecipients.split("\\s*,\\s*")
                                : config.recipients().toArray(String[]::new));
                case "dingtalk" -> sendDingtalk(content, "success".equals(run.getStatus()) ? "info" : "error");
                case "wecom" -> sendWecom(content, "success".equals(run.getStatus()) ? "info" : "error");
                default -> false;
            };
            if (success) delivered++; else errors.add(channel + " 发送失败或未配置");
        }
        return new DeliveryResult(delivered, config.notifyChannels().size(), String.join("；", errors));
    }

    // ========================================================================
    // Notification Channels
    // ========================================================================

    /**
     * Send DingTalk (钉钉) webhook notification.
     * DingTalk API: POST webhook URL with JSON body.
     */
    private boolean sendDingtalk(String message, String level) {
        if (dingtalkWebhook.isEmpty()) {
            log.debug("DingTalk webhook not configured, skipping");
            return false;
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("msgtype", "markdown");

            String title = "实时数仓告警";
            String markdownText = String.format("# %s\n\n%s\n\n> 级别: %s | 时间: %s",
                    title, message, level,
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            body.put("markdown", Map.of("title", title, "text", markdownText));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(dingtalkWebhook, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("DingTalk alert sent successfully");
                return true;
            } else {
                log.warn("DingTalk alert failed: HTTP {}", response.getStatusCode());
                return false;
            }
        } catch (Exception e) {
            log.error("DingTalk notification error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Send WeCom (企微) webhook notification.
     * WeCom API: POST webhook URL with JSON body (similar to DingTalk).
     */
    private boolean sendWecom(String message, String level) {
        if (wecomWebhook.isEmpty()) {
            log.debug("WeCom webhook not configured, skipping");
            return false;
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("msgtype", "markdown");

            String markdownContent = String.format("【实时数仓告警】\n> 级别: <font color=\"%s\">%s</font>\n\n%s\n\n时间: %s",
                    levelColor(level), level, message,
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            body.put("markdown", Map.of("content", markdownContent));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(wecomWebhook, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("WeCom alert sent successfully");
                return true;
            } else {
                log.warn("WeCom alert failed: HTTP {}", response.getStatusCode());
                return false;
            }
        } catch (Exception e) {
            log.error("WeCom notification error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Send email notification.
     */
    private boolean sendEmail(String message, String level, String subject) {
        return sendEmailTo(message, level, subject, emailRecipients.split("\\s*,\\s*"));
    }

    private boolean sendEmailTo(String message, String level, String subject, String[] recipients) {
        if (mailHost.isEmpty()) {
            log.debug("Email not configured, skipping");
            return false;
        }

        try {
            SimpleMailMessage mailMessage = new SimpleMailMessage();
            mailMessage.setFrom(emailFrom);
            mailMessage.setTo(recipients);
            mailMessage.setSubject("[实时数仓" + levelLabel(level) + "] " + subject);
            mailMessage.setText(message);

            mailSender.send(mailMessage);
            log.info("Email alert sent successfully: {}", subject);
            return true;
        } catch (Exception e) {
            log.error("Email notification error: {}", e.getMessage());
            return false;
        }
    }

    public record DeliveryResult(int delivered, int requested, String error) {
        public boolean success() { return requested > 0 && delivered == requested; }
    }

    // ========================================================================
    // Utility
    // ========================================================================

    private String formatAlertMessage(String ruleName, String ruleType, String detail, String level) {
        return String.format("【实时数仓告警】\n规则: %s\n类型: %s\n级别: %s\n详情: %s\n时间: %s",
                ruleName, ruleType, levelLabel(level), detail,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }

    private String formatQualityAlertMessage(QualityRule rule, double actual, double threshold, String message) {
        return String.format("【质量检查告警】\n规则: %s\n类型: %s\n表: %s\n列: %s\n实际值: %.4f\n阈值: %.4f\n详情: %s\n时间: %s",
                rule.getRuleName(),
                rule.getRuleType(),
                rule.getTargetTable(),
                rule.getTargetColumn() != null ? rule.getTargetColumn() : "(全表)",
                actual,
                threshold,
                message,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }

    private String levelColor(String level) {
        return switch (level) {
            case "error", "critical" -> "warning";
            case "warn" -> "info";
            default -> "comment";
        };
    }

    private String levelLabel(String level) {
        return switch (level) {
            case "error", "critical" -> "严重告警";
            case "warn" -> "警告";
            case "info" -> "信息";
            default -> level;
        };
    }
}
