package com.bookkeeping.monthly;

import com.bookkeeping.constant.BookkeepingResp;
import com.bookkeeping.dao.entity.AiConfigEntity;
import com.bookkeeping.dao.mapper.AiConfigMapper;
import com.bookkeeping.exception.BusinessException;
import org.springframework.stereotype.Service;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import com.bookkeeping.monthly.MonthlyAiModelClient.AiFailure;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.bookkeeping.monthly.MonthlyReportModels.*;

/**
 * 月报 AI 解读配置服务：负责 OpenAI 兼容接口配置的校验、存储与「配置版本」失效语义。
 * <p>
 * 设计沿用原桌面端 ai-reports 的安全约束：
 * 1) baseUrl 仅允许 https、不得携带账号信息/查询串/锚点，长度受限；
 * 2) 关键字段（baseUrl/model/includeNames）变更即重算 configVersion，
 *    使进行中的请求与已预览的确认信息失效，避免跨配置误发送；
 * 3) 明文密钥只存本地库，任何对外视图都不返回。
 *
 * @author zhuxiao
 */
@Service
public class AiConfigService {
    /**
     * 单行配置表固定主键
     */
    private static final int CONFIG_ID = 1;

    private final AiConfigMapper mapper;
    private final MonthlyAiRequestGuard guard;

    public AiConfigService(AiConfigMapper mapper, MonthlyAiRequestGuard guard) {
        this.mapper = mapper;
        this.guard = guard;
    }

    /**
     * 载入当前配置；不存在时返回空配置和稳定的未配置版本，不自动授权。
     */
    public AiConfigEntity load() {
        AiConfigEntity entity = database(() -> mapper.selectById(CONFIG_ID));
        if (entity != null) return entity;
        AiConfigEntity fresh = new AiConfigEntity();
        fresh.setId(CONFIG_ID);
        fresh.setBaseUrl("");
        fresh.setModel("");
        fresh.setApiKey("");
        fresh.setIncludeNames(false);
        fresh.setConfigVersion("unconfigured");
        fresh.setUpdateTime(now());
        return fresh;
    }

    /**
     * 对外回显视图：隐藏明文密钥，仅以 hasKey 标识是否已配置；available 表示是否具备发起调用的条件。
     */
    public AiConfigView view() {
        AiConfigEntity entity = load();
        boolean hasKey = notBlank(entity.getApiKey());
        boolean available = usable(entity);
        return new AiConfigView(entity.getBaseUrl(), entity.getModel(), entity.isIncludeNames(),
                hasKey, available, entity.getConfigVersion());
    }

    /**
     * 保存配置。apiKey 缺省仅在同一规范化地址下保留，显式空串清除；关键字段变更使旧任务失效。
     */
    public AiConfigView save(AiConfigRequest request) {
        return database(() -> guard.locked(() -> {
            String before = load().getConfigVersion();
            AiConfigView result = guard.mutate(() -> saveInternal(request));
            if (!Objects.equals(before, result.configVersion())) guard.cancelCurrent("CONFIG_CHANGED");
            return result;
        }));
    }

    private AiConfigView saveInternal(AiConfigRequest request) {
        guard.requireAvailable();
        String baseUrl = normalizeBaseUrl(request.baseUrl());
        validateModel(request.model());
        AiConfigEntity entity = mapper.selectById(CONFIG_ID);
        boolean created = entity == null;
        if (created) {
            entity = new AiConfigEntity();
            entity.setId(CONFIG_ID);
            entity.setApiKey("");
        }
        String oldAddress;
        try { oldAddress = normalizeBaseUrl(entity.getBaseUrl()); }
        catch (BusinessException e) { oldAddress = ""; }
        boolean addressChanged = !baseUrl.equals(oldAddress);
        String key = request.apiKey() != null ? request.apiKey() : addressChanged ? "" : entity.getApiKey();
        if (key == null) key = "";
        validateApiKey(key);
        boolean keyChanged = !key.equals(entity.getApiKey());
        boolean coreChanged = created || addressChanged || !request.model().equals(entity.getModel())
                || request.includeNames() != entity.isIncludeNames();
        entity.setApiKey(key);
        entity.setBaseUrl(baseUrl);
        entity.setModel(request.model());
        entity.setIncludeNames(request.includeNames());
        // 只有配置确实改变才使旧预览失效；事务提交后再取消当前模型订阅。
        if (coreChanged || keyChanged) entity.setConfigVersion(newVersion());
        entity.setUpdateTime(now());
        if (created) mapper.insert(entity);
        else mapper.updateById(entity);
        return view();
    }

    /**
     * 使当前确认失效并停止当前调用；不禁止将来重新确认后的手动发送。
     */
    public AiConfigView revoke(boolean clearKey) {
        return database(() -> guard.locked(() -> {
            AiConfigView result = guard.mutate(() -> {
                guard.requireAvailable();
                AiConfigEntity entity = load();
                if (clearKey) entity.setApiKey("");
                entity.setConfigVersion(newVersion());
                entity.setUpdateTime(now());
                persist(entity);
                return view();
            });
            guard.cancelCurrent("CANCELLED");
            return result;
        }));
    }

    /** 配置 SQL 失败只暴露固定错误，不将可能带绑定值的异常交给全局日志处理器。 */
    private <T> T database(Supplier<T> action) {
        try { return action.get(); }
        catch (DataAccessException | TransactionException e) { throw new AiFailure("STORAGE_FAILED"); }
    }

    private void persist(AiConfigEntity entity) {
        if (mapper.selectById(CONFIG_ID) == null) mapper.insert(entity);
        else mapper.updateById(entity);
    }

    // ==================== 校验（移植自桌面端 ai-reports/ai-client 的等价约束） ====================

    static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank() || baseUrl.length() > 500) throw invalid();
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        // 仅允许纯净的 https 根地址：不得带账号信息、查询串或锚点，避免把密钥泄漏到非预期目标
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getRawAuthority().endsWith(":")) throw invalid();
        String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
        return "https://" + uri.getHost().toLowerCase(Locale.ROOT)
                + (uri.getPort() == -1 || uri.getPort() == 443 ? "" : ":" + uri.getPort()) + path;
    }

    static boolean usable(AiConfigEntity entity) {
        try {
            normalizeBaseUrl(entity.getBaseUrl());
            validateModel(entity.getModel());
            if (!notBlank(entity.getApiKey())) return false;
            validateApiKey(entity.getApiKey());
            return true;
        } catch (BusinessException e) { return false; }
    }

    private static void validateModel(String model) {
        if (model == null || model.isBlank() || model.length() > 200 || model.chars().anyMatch(c -> c == '\r' || c == '\n'))
            throw invalid();
    }

    private static void validateApiKey(String apiKey) {
        // 允许空串表示清空密钥；非空时限定可见 ASCII，长度 1~4096
        if (apiKey.isEmpty()) return;
        if (apiKey.length() > 4096
                || apiKey.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) throw invalid();
    }

    private static BusinessException invalid() {
        return new BusinessException(BookkeepingResp.AI_CONFIG_INVALID);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String newVersion() {
        return UUID.randomUUID().toString();
    }

    private static String now() {
        return LocalDateTime.now().toString();
    }
}
