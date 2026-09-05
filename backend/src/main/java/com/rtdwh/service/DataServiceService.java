package com.rtdwh.service;

import com.rtdwh.dto.DataServiceDTO;
import com.rtdwh.entity.*;
import com.rtdwh.exception.DataServiceAuthException;
import com.rtdwh.exception.DataServiceRateLimitException;
import com.rtdwh.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class DataServiceService {
    private final DataServiceDefinitionRepository definitionRepository;
    private final DataServiceAppRepository appRepository;
    private final DataServiceGrantRepository grantRepository;
    private final DataServiceInvocationLogRepository logRepository;
    private final ReportParameterRenderer parameterRenderer;
    private final QueryService queryService;
    private final PasswordEncoder passwordEncoder;
    private final QueryAccessScopeService accessScopeService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, RateWindow> rateWindows = new ConcurrentHashMap<>();

    public List<DataServiceDefinition> definitions(Long userId) {
        return definitionRepository.findAll().stream().filter(value -> canAccess(value, userId)).toList();
    }
    public List<DataServiceApp> apps(Long userId) {
        return appRepository.findAll().stream().filter(app -> accessScopeService.isAdmin(userId)
                || Objects.equals(app.getCreatedBy(), userId)).toList();
    }
    public List<DataServiceGrant> grants(Long appId, Long userId) {
        assertAppAccess(appId, userId);
        Set<Long> allowed = new HashSet<>();
        definitions(userId).forEach(value -> allowed.add(value.getId()));
        return grantRepository.findByAppId(appId).stream().filter(value -> allowed.contains(value.getServiceId())).toList();
    }
    public List<DataServiceInvocationLog> logs(int limit, Long userId) {
        Set<Long> allowed = new HashSet<>();
        definitions(userId).forEach(value -> allowed.add(value.getId()));
        return logRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 500))))
                .stream().filter(value -> allowed.contains(value.getServiceId())).toList();
    }

    @Transactional
    public DataServiceDefinition createDefinition(DataServiceDTO.DefinitionRequest request, Long userId) {
        if (definitionRepository.existsByServiceCode(request.getServiceCode())) throw new IllegalStateException("服务编码已存在");
        validate(request);
        assertSqlAccess(request, userId);
        return definitionRepository.save(apply(new DataServiceDefinition(), request, userId));
    }

    @Transactional
    public DataServiceDefinition updateDefinition(Long id, DataServiceDTO.DefinitionRequest request, Long userId) {
        DataServiceDefinition definition = requireDefinition(id);
        assertDefinitionAccess(definition, userId);
        if (!definition.getServiceCode().equals(request.getServiceCode())) throw new IllegalArgumentException("服务编码发布后不可修改");
        validate(request);
        assertSqlAccess(request, userId);
        definition.setApiVersion(definition.getApiVersion() + 1);
        return definitionRepository.save(apply(definition, request, definition.getCreatorId()));
    }

    @Transactional
    public DataServiceDefinition publish(Long id, boolean published, Long userId) {
        DataServiceDefinition definition = requireDefinition(id);
        assertDefinitionAccess(definition, userId);
        definition.setStatus(published ? DataServiceDefinition.ServiceStatus.published : DataServiceDefinition.ServiceStatus.offline);
        definition.setPublishedAt(published ? LocalDateTime.now() : definition.getPublishedAt());
        return definitionRepository.save(definition);
    }

    @Transactional
    public void deleteDefinition(Long id, Long userId) {
        DataServiceDefinition definition = requireDefinition(id);
        assertDefinitionAccess(definition, userId);
        if (definition.getStatus() == DataServiceDefinition.ServiceStatus.published) throw new IllegalStateException("请先下线数据服务");
        definitionRepository.delete(definition);
    }

    @Transactional
    public DataServiceDTO.AppCredential createApp(DataServiceDTO.AppRequest request, Long userId) {
        String secret = token(32);
        DataServiceApp app = appRepository.save(DataServiceApp.builder().appName(request.getAppName().trim())
                .appKey("dsa_" + token(12)).secretHash(passwordEncoder.encode(secret)).enabled(true)
                .expiresAt(request.getExpiresAt()).createdBy(userId).build());
        return credential(app, secret);
    }

    @Transactional
    public DataServiceDTO.AppCredential rotateSecret(Long appId, Long userId) {
        DataServiceApp app = assertAppAccess(appId, userId);
        String secret = token(32);
        app.setSecretHash(passwordEncoder.encode(secret));
        appRepository.save(app);
        return credential(app, secret);
    }

    @Transactional
    public DataServiceApp toggleApp(Long appId, Long userId) {
        DataServiceApp app = assertAppAccess(appId, userId);
        app.setEnabled(!Boolean.TRUE.equals(app.getEnabled()));
        return appRepository.save(app);
    }

    @Transactional
    public DataServiceGrant grant(Long appId, Long serviceId, Long userId) {
        assertAppAccess(appId, userId); assertDefinitionAccess(requireDefinition(serviceId), userId);
        if (grantRepository.existsByAppIdAndServiceId(appId, serviceId)) throw new IllegalStateException("应用已获得该服务授权");
        return grantRepository.save(DataServiceGrant.builder().appId(appId).serviceId(serviceId).createdBy(userId).build());
    }

    @Transactional
    public void revoke(Long appId, Long serviceId, Long userId) {
        assertAppAccess(appId, userId);
        assertDefinitionAccess(requireDefinition(serviceId), userId);
        grantRepository.deleteByAppIdAndServiceId(appId, serviceId);
    }

    public Map<String, Object> invoke(String code, String appKey, String appSecret,
                                      Map<String, Object> parameters, String clientIp) {
        long started = System.currentTimeMillis();
        DataServiceDefinition definition = definitionRepository.findByServiceCode(code).orElse(null);
        DataServiceApp app = null;
        try {
            if (definition == null || definition.getStatus() != DataServiceDefinition.ServiceStatus.published) {
                throw new IllegalArgumentException("数据服务不存在或未发布");
            }
            app = authenticate(appKey, appSecret);
            if (!grantRepository.existsByAppIdAndServiceId(app.getId(), definition.getId())) {
                throw new DataServiceAuthException("应用未获得该数据服务授权");
            }
            checkRate(app.getId(), definition);
            String sql = parameterRenderer.render(definition.getSqlTemplate(), definition.getParameterConfig(), parameters);
            Map<String, Object> result = queryService.executeDataServiceQuery(sql, definition.getCreatorId(),
                    definition.getCatalogName(), definition.getDatabaseName(), definition.getMaxRows(), definition.getTimeoutSeconds());
            boolean success = "success".equals(result.get("status"));
            saveLog(definition, app, success ? "success" : "failed", success ? 200 : 500,
                    number(result.get("rowCount")), System.currentTimeMillis() - started,
                    success ? null : String.valueOf(result.get("errorMsg")), clientIp);
            if (!success) throw new IllegalStateException("数据服务查询失败: " + result.get("errorMsg"));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("serviceCode", code); response.put("apiVersion", definition.getApiVersion());
            response.put("columns", result.get("columns")); response.put("rows", result.get("rows"));
            response.put("rowCount", result.get("rowCount")); response.put("truncated", result.get("truncated"));
            response.put("durationMs", result.get("durationMs")); response.put("requestId", result.get("requestId"));
            return response;
        } catch (RuntimeException exception) {
            if (definition != null && !(exception instanceof IllegalStateException && exception.getMessage().startsWith("数据服务查询失败"))) {
                int status = exception instanceof DataServiceAuthException ? 401 : exception instanceof DataServiceRateLimitException ? 429 : 400;
                saveLog(definition, app, "failed", status, null, System.currentTimeMillis() - started, exception.getMessage(), clientIp);
            }
            throw exception;
        }
    }

    private boolean canAccess(DataServiceDefinition definition, Long userId) {
        try {
            return accessScopeService.canAccessDorisSql(userId,
                    parameterRenderer.sqlForAccessCheck(definition.getSqlTemplate(), definition.getParameterConfig()),
                    definition.getCatalogName(), definition.getDatabaseName());
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private void assertDefinitionAccess(DataServiceDefinition definition, Long userId) {
        if (!canAccess(definition, userId)) throw new org.springframework.security.access.AccessDeniedException("无权访问该数据服务");
    }

    private void assertSqlAccess(DataServiceDTO.DefinitionRequest request, Long userId) {
        accessScopeService.validateDoris(userId, parameterRenderer.sqlForAccessCheck(request.getSqlTemplate(), request.getParameterConfig()),
                request.getCatalogName(), request.getDatabaseName());
    }

    private DataServiceApp assertAppAccess(Long appId, Long userId) {
        DataServiceApp app = requireApp(appId);
        if (!accessScopeService.isAdmin(userId) && !Objects.equals(app.getCreatedBy(), userId)) {
            throw new org.springframework.security.access.AccessDeniedException("无权管理该调用应用");
        }
        return app;
    }

    private DataServiceDefinition apply(DataServiceDefinition value, DataServiceDTO.DefinitionRequest request, Long creatorId) {
        value.setServiceCode(request.getServiceCode()); value.setServiceName(request.getServiceName().trim());
        value.setDescription(trim(request.getDescription())); value.setCreatorId(creatorId);
        value.setSqlTemplate(request.getSqlTemplate().trim()); value.setParameterConfig(trim(request.getParameterConfig()));
        value.setCatalogName(identifier(request.getCatalogName())); value.setDatabaseName(identifier(request.getDatabaseName()));
        value.setMaxRows(request.getMaxRows()); value.setTimeoutSeconds(request.getTimeoutSeconds());
        value.setRateLimitPerMinute(request.getRateLimitPerMinute());
        if (value.getStatus() == null) value.setStatus(DataServiceDefinition.ServiceStatus.draft);
        if (value.getApiVersion() == null) value.setApiVersion(1);
        return value;
    }
    private void validate(DataServiceDTO.DefinitionRequest request) {
        queryService.validateReadOnlySql(request.getSqlTemplate());
        parameterRenderer.validateTemplate(request.getSqlTemplate(), request.getParameterConfig());
    }
    private DataServiceApp authenticate(String key, String secret) {
        if (key == null || secret == null) throw new DataServiceAuthException("缺少 X-App-Key 或 X-App-Secret");
        DataServiceApp app = appRepository.findByAppKey(key).orElseThrow(() -> new DataServiceAuthException("应用凭证无效"));
        if (!Boolean.TRUE.equals(app.getEnabled()) || app.getExpiresAt() != null && app.getExpiresAt().isBefore(LocalDateTime.now())
                || !passwordEncoder.matches(secret, app.getSecretHash())) throw new DataServiceAuthException("应用凭证无效或已过期");
        return app;
    }
    private void checkRate(Long appId, DataServiceDefinition definition) {
        String key = appId + ":" + definition.getId();
        long minute = Instant.now().getEpochSecond() / 60;
        RateWindow window = rateWindows.compute(key, (ignored, current) -> current == null || current.minute != minute
                ? new RateWindow(minute, new AtomicInteger(1)) : new RateWindow(minute, new AtomicInteger(current.count.incrementAndGet())));
        if (window.count.get() > definition.getRateLimitPerMinute()) throw new DataServiceRateLimitException("接口调用频率超过每分钟限制");
    }
    private void saveLog(DataServiceDefinition definition, DataServiceApp app, String status, int httpStatus,
                         Integer rows, long duration, String error, String ip) {
        logRepository.save(DataServiceInvocationLog.builder().serviceId(definition.getId())
                .appId(app == null ? null : app.getId()).serviceCode(definition.getServiceCode()).status(status)
                .httpStatus(httpStatus).rowCount(rows).durationMs(duration).clientIp(ip).errorMessage(trimError(error)).build());
    }
    private String token(int bytes) { byte[] value = new byte[bytes]; secureRandom.nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private DataServiceDTO.AppCredential credential(DataServiceApp app, String secret) { return new DataServiceDTO.AppCredential(app.getId(), app.getAppName(), app.getAppKey(), secret, app.getEnabled(), app.getExpiresAt()); }
    private DataServiceDefinition requireDefinition(Long id) { return definitionRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("数据服务不存在: " + id)); }
    private DataServiceApp requireApp(Long id) { return appRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("调用应用不存在: " + id)); }
    private String identifier(String value) { String result=value.trim(); if(!result.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) throw new IllegalArgumentException("Catalog 或 Database 名称格式不正确"); return result; }
    private String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String trimError(String value) { if(value==null)return null; return value.length()>2000?value.substring(0,2000):value; }
    private Integer number(Object value) { return value instanceof Number number ? number.intValue() : null; }
    private record RateWindow(long minute, AtomicInteger count) {}
}
