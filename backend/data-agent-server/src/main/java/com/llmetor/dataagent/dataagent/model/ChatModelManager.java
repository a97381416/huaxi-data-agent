package com.llmetor.dataagent.dataagent.model;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.llmetor.dataagent.dataagent.model.mapper.AgentxChatModelMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话模型管理器（对齐 McpServerManager 模式）——agentx_chat_model 表生命周期、
 * yml 存量种子迁移、ChatModel 实例缓存、CRUD 编排、连通测试。
 *
 * <p>生效语义：<b>变更即清实例缓存，下一请求热生效</b>；运行中的会话用旧实例跑完，
 * 不做插拔。实例按 code+配置指纹惰性构建并缓存（配置指纹参与键，编辑后自动换键重建，
 * 旧键条目由 {@link #invalidate} 兜底清空）。
 *
 * <p>与 spring-ai 自动装配的关系：OpenAiChatModel 实例全部由本类手工构建，
 * <b>刻意不注册为 Spring bean</b>——自动配置产出的唯一 ChatModel bean 保持 by-type
 * 注入不被破坏（同 EvalChatModels 的纪律）。该 bean 作为库空/无可用模型时的兜底
 * 由 {@link #resolve(String, ChatModel)} 的 fallback 参数传入。
 *
 * <p>默认模型不变式：有且仅有一行 is_default=1。新建强制默认（首行）、设默认/删除/停用
 * 默认行时自动移交 sort 最小的启用行；code 未传或失效的请求都落到默认（启用行里的）。
 */
@Slf4j
@Component
public class ChatModelManager {

    public static final String SOURCE_YML = "yml";
    public static final String SOURCE_MANUAL = "manual";

    /** 温度允许范围（OpenAI 兼容端点通用口径） */
    private static final double MIN_TEMPERATURE = 0.0;
    private static final double MAX_TEMPERATURE = 2.0;
    private static final double DEFAULT_TEMPERATURE = 0.7;

    /** 读超时允许范围（毫秒） */
    private static final int MIN_TIMEOUT_MS = 1000;
    private static final int MAX_TIMEOUT_MS = 600_000;
    private static final int DEFAULT_TIMEOUT_MS = 300_000;

    /** chat/completions 路径默认值（Spring AI OpenAiApi 缺省拼 {base_url}/v1/chat/completions） */
    static final String DEFAULT_COMPLETIONS_PATH = "/v1/chat/completions";
    /** completions_path 长度上限（VARCHAR(200)） */
    private static final int MAX_COMPLETIONS_PATH_LENGTH = 200;

    /** 关思考请求参数默认值（Qwen/DashScope 风格；GLM 等在管理端按模型覆盖为 {"thinking":{"type":"disabled"}}） */
    public static final String DEFAULT_THINKING_OFF_PARAM = "{\"enable_thinking\":false}";
    /** thinking_off_param 长度上限（VARCHAR(200) 留余量） */
    private static final int MAX_THINKING_OFF_PARAM_LENGTH = 180;

    /** last_error 截断长度（VARCHAR(2000) 留余量） */
    private static final int MAX_LAST_ERROR_LENGTH = 1500;

    /** code 允许字符集（作为前端可见的稳定键，限制为 URL/JSON 安全字符） */
    private static final String CODE_PATTERN = "[A-Za-z0-9._-]+";

    private final JdbcTemplate jdbcTemplate;
    private final AgentxChatModelMapper mapper;

    /** yml 种子配置（仅首次启动空表时种入，此后以表为准） */
    private final String seedApiKey;
    private final String seedBaseUrl;
    private final String seedModel;
    private final Double seedTemperature;

    /** ChatModel 实例缓存：code|配置指纹 → 实例（惰性构建，写操作清空） */
    private final Map<String, ChatModel> instances = new ConcurrentHashMap<>();

    public ChatModelManager(javax.sql.DataSource dataSource,
                            AgentxChatModelMapper mapper,
                            @Value("${spring.ai.openai.api-key:}") String seedApiKey,
                            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String seedBaseUrl,
                            @Value("${spring.ai.openai.chat.options.model:}") String seedModel,
                            @Value("${spring.ai.openai.chat.options.temperature:0.7}") Double seedTemperature) {
        // M5 踩坑固化的纪律：主库注入点一律拿 DataSource 自建 JdbcTemplate（见 README M5 不变量 6）
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.mapper = mapper;
        this.seedApiKey = seedApiKey;
        this.seedBaseUrl = seedBaseUrl;
        this.seedModel = seedModel;
        this.seedTemperature = seedTemperature;
    }

    @PostConstruct
    void init() {
        ensureTableExists();
        seedFromYmlIfNeeded();
    }

    // ==================== 建表与种子迁移 ====================

    /** 启动自动建表（M-H1 PG 版：原生 IF NOT EXISTS 幂等；updated_at 由应用层维护） */
    private void ensureTableExists() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agentx_chat_model (
                    id              BIGINT            NOT NULL,
                    code            VARCHAR(64)       NOT NULL,
                    name            VARCHAR(64)       NOT NULL,
                    provider        VARCHAR(64)       DEFAULT NULL,
                    base_url        VARCHAR(500)      NOT NULL,
                    completions_path VARCHAR(200)     DEFAULT '/v1/chat/completions',
                    api_key         VARCHAR(1000)     NOT NULL,
                    model           VARCHAR(128)      NOT NULL,
                    temperature     DOUBLE PRECISION  NOT NULL DEFAULT 0.7,
                    timeout_ms      INT               NOT NULL DEFAULT 300000,
                    enabled         SMALLINT          NOT NULL DEFAULT 1,
                    is_default      SMALLINT          NOT NULL DEFAULT 0,
                    sort            INT               NOT NULL DEFAULT 0,
                    remark          VARCHAR(500)      DEFAULT NULL,
                    thinking_off_param VARCHAR(200)   DEFAULT '{"enable_thinking":false}',
                    source          VARCHAR(20)       DEFAULT NULL,
                    last_error      VARCHAR(2000)     DEFAULT NULL,
                    last_check_time TIMESTAMP         DEFAULT NULL,
                    created_at      TIMESTAMP         DEFAULT CURRENT_TIMESTAMP,
                    updated_at      TIMESTAMP         DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id)
                )
                """);
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_agentx_chat_model_code ON agentx_chat_model (code)");
        // 存量表迁移（PG 幂等：ADD COLUMN IF NOT EXISTS，DEFAULT 对已有行回填）
        jdbcTemplate.execute("ALTER TABLE agentx_chat_model ADD COLUMN IF NOT EXISTS completions_path VARCHAR(200) DEFAULT '/v1/chat/completions'");
        jdbcTemplate.execute("ALTER TABLE agentx_chat_model ADD COLUMN IF NOT EXISTS thinking_off_param VARCHAR(200) DEFAULT '{\"enable_thinking\":false}'");
        log.info("[ChatModelManager] agentx_chat_model 表就绪");
    }

    /**
     * 首次启动（表空）把 yml 存量主模型配置种入（source=yml，enabled=1，强制默认）：
     * spring.ai.openai（MiniMax OpenAI 兼容端点）。此后 DB 为权威源，yml 配置仅作种子。
     * yml 未配 model 时跳过（没有任何可用的种子语义）。
     */
    private void seedFromYmlIfNeeded() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agentx_chat_model", Long.class);
        if (count != null && count > 0) {
            return;
        }
        if (seedModel == null || seedModel.isBlank()) {
            log.warn("[ChatModelManager] 首次启动但 spring.ai.openai 未配置 model，跳过种子（对话将走 yml 兜底 bean）");
            return;
        }
        AgentxChatModel row = new AgentxChatModel();
        row.setCode(seedModel.trim());
        row.setName(seedModel.trim());
        row.setProvider(null);
        row.setBaseUrl(seedBaseUrl);
        row.setApiKey(seedApiKey == null ? "" : seedApiKey.trim());
        row.setModel(seedModel.trim());
        row.setTemperature(seedTemperature == null ? DEFAULT_TEMPERATURE : seedTemperature);
        row.setTimeoutMs(DEFAULT_TIMEOUT_MS);
        row.setEnabled(1);
        row.setIsDefault(1);
        row.setSort(0);
        row.setThinkingOffParam(DEFAULT_THINKING_OFF_PARAM);
        row.setRemark("首次启动从 spring.ai.openai 种子迁移，此后以表为准");
        row.setSource(SOURCE_YML);
        try {
            mapper.insert(row);
            log.info("[ChatModelManager] 首次启动：yml 存量主模型种子入库（code={}），此后以表为准", row.getCode());
        } catch (Exception e) {
            log.warn("[ChatModelManager] 种子行插入失败（可能已存在同 code）: {} - {}", row.getCode(), e.getMessage());
        }
    }

    // ==================== 解析（DodoAgent 每请求入口） ====================

    /**
     * 按请求解析 ChatModel：code 空 → 默认启用行；code 失效（不存在/停用）→ 警告后落默认；
     * 无任何启用行 → yml 兜底 bean（老请求/清库零破坏）。
     */
    public ChatModel resolve(String code, ChatModel fallback) {
        AgentxChatModel picked = pickRow(code);
        if (picked == null) {
            return fallback;
        }
        return instanceOf(picked);
    }

    /**
     * 与 {@link #resolve} 同一套挑选逻辑返回库行（不构建实例）：
     * DodoAgent 据此取 thinking_off_param（关思考请求参数随请求注入，不进实例缓存键）。
     * 返回 null = 无启用行（走 yml 兜底 bean，不支持请求级关思考）。
     */
    public AgentxChatModel pickRow(String code) {
        List<AgentxChatModel> enabled = listEnabled();
        if (enabled.isEmpty()) {
            return null;
        }
        AgentxChatModel picked = null;
        if (code != null && !code.isBlank()) {
            String wanted = code.trim();
            picked = enabled.stream().filter(r -> r.getCode().equals(wanted)).findFirst().orElse(null);
            if (picked == null) {
                log.warn("[ChatModelManager] 请求模型不可用（不存在或已停用），回退默认: code={}", wanted);
            }
        }
        if (picked == null) {
            picked = enabled.stream().filter(r -> r.getIsDefault() != null && r.getIsDefault() == 1)
                    .findFirst().orElse(enabled.get(0));
        }
        return picked;
    }

    /** 对话页选择器选项（启用行按 sort,id） */
    public List<ChatModelOption> listEnabledOptions() {
        return listEnabled().stream()
                .map(r -> new ChatModelOption(r.getCode(), r.getName(), r.getProvider(),
                        r.getIsDefault() != null && r.getIsDefault() == 1, r.getRemark()))
                .toList();
    }

    private List<AgentxChatModel> listEnabled() {
        return mapper.selectList(new LambdaQueryWrapper<AgentxChatModel>()
                .eq(AgentxChatModel::getEnabled, 1)
                .orderByAsc(AgentxChatModel::getSort)
                .orderByAsc(AgentxChatModel::getId));
    }

    /** 实例惰性构建（配置指纹参与键，配置变更自动换键重建） */
    private ChatModel instanceOf(AgentxChatModel row) {
        return instances.computeIfAbsent(instanceKey(row), k -> buildChatModel(row));
    }

    private String instanceKey(AgentxChatModel row) {
        return row.getCode() + "|" + row.getBaseUrl() + "|" + normalizeCompletionsPath(row.getCompletionsPath())
                + "|" + row.getApiKey()
                + "|" + row.getModel() + "|" + row.getTemperature() + "|" + row.getTimeoutMs();
    }

    /** 手工构建 OpenAI 兼容 ChatModel（EvalChatModels.build 泛化：温度/超时可配 + streamUsage 对齐 yml 主模型） */
    private ChatModel buildChatModel(AgentxChatModel row) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofMillis(normalizeTimeout(row.getTimeoutMs())));
        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(row.getApiKey())
                .baseUrl(row.getBaseUrl())
                // completions 路径可配：GLM 等 /v4 风格端点没有 /v1 段，默认 /v1/chat/completions 会拼出 404
                .completionsPath(normalizeCompletionsPath(row.getCompletionsPath()))
                .restClientBuilder(RestClient.builder().requestFactory(factory))
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(row.getModel())
                        .temperature(row.getTemperature() == null ? DEFAULT_TEMPERATURE : row.getTemperature())
                        // 流式返回 usage——agentx_trace 的 token 统计依赖（对齐 yml stream-usage: true）
                        .streamUsage(true)
                        .build())
                .build();
    }

    // ==================== CRUD 编排 ====================

    /** 列表（凭据脱敏；按 sort,id 稳定排序） */
    public List<ChatModelInfo> list() {
        List<AgentxChatModel> rows = mapper.selectList(new LambdaQueryWrapper<AgentxChatModel>()
                .orderByAsc(AgentxChatModel::getSort)
                .orderByAsc(AgentxChatModel::getId));
        return rows.stream().map(this::toInfo).toList();
    }

    /**
     * 新建：校验（含 code 唯一）→ 落库。请求设默认则默认移交；表空强制默认。
     */
    public ChatModelInfo create(ChatModelUpsertRequest req) {
        AgentxChatModel row = new AgentxChatModel();
        if (req.code() == null || req.code().isBlank()) {
            throw new IllegalArgumentException("模型标识（code）不能为空");
        }
        row.setCode(req.code().trim());
        applyRequest(row, req);
        mergeSecret(row, null, req);
        // 创建时空值落默认关思考参数（编辑空值 = 不支持请求级关闭，语义见 applyRequest）
        if (row.getThinkingOffParam() == null) {
            row.setThinkingOffParam(DEFAULT_THINKING_OFF_PARAM);
        }
        validate(row);
        if (mapper.selectOne(new LambdaQueryWrapper<AgentxChatModel>()
                .eq(AgentxChatModel::getCode, row.getCode())) != null) {
            throw new IllegalArgumentException("模型标识已存在: " + row.getCode());
        }
        row.setEnabled(Boolean.FALSE.equals(req.enabled()) ? 0 : 1);
        row.setSource(SOURCE_MANUAL);
        long total = mapper.selectCount(null);
        if (Boolean.TRUE.equals(req.isDefault()) || total == 0) {
            // 首行 / 显式设默认：清其他行的默认标记
            row.setIsDefault(1);
            if (total > 0) {
                mapper.update(null, new LambdaUpdateWrapper<AgentxChatModel>()
                        .set(AgentxChatModel::getIsDefault, 0));
            }
        } else {
            row.setIsDefault(0);
        }
        mapper.insert(row);
        invalidate();
        log.info("[ChatModelManager] 新建对话模型: {} ({}, enabled={}, default={})",
                row.getCode(), row.getModel(), row.getEnabled(), row.getIsDefault());
        return toInfo(requireRow(row.getId()));
    }

    /**
     * 编辑（code 不可改）：合并凭据 → 校验 → 全量更新配置字段。enabled 不在此改（走 /toggle）。
     * 请求 isDefault=true 时默认移交到本模型。
     */
    public ChatModelInfo update(Long id, ChatModelUpsertRequest req) {
        AgentxChatModel existing = requireRow(id);
        AgentxChatModel target = shallowCopy(existing);
        applyRequest(target, req);
        mergeSecret(target, existing, req);
        validate(target);

        mapper.update(null, new LambdaUpdateWrapper<AgentxChatModel>()
                .eq(AgentxChatModel::getId, id)
                .set(AgentxChatModel::getName, target.getName())
                .set(AgentxChatModel::getProvider, target.getProvider())
                .set(AgentxChatModel::getBaseUrl, target.getBaseUrl())
                .set(AgentxChatModel::getCompletionsPath, target.getCompletionsPath())
                .set(AgentxChatModel::getApiKey, target.getApiKey())
                .set(AgentxChatModel::getModel, target.getModel())
                .set(AgentxChatModel::getTemperature, target.getTemperature())
                .set(AgentxChatModel::getTimeoutMs, target.getTimeoutMs())
                .set(AgentxChatModel::getSort, target.getSort())
                .set(AgentxChatModel::getRemark, target.getRemark())
                .set(AgentxChatModel::getThinkingOffParam, target.getThinkingOffParam())
                .set(AgentxChatModel::getUpdatedAt, LocalDateTime.now()));

        if (Boolean.TRUE.equals(req.isDefault()) && !(existing.getIsDefault() != null && existing.getIsDefault() == 1)) {
            moveDefaultTo(id);
        }
        invalidate();
        log.info("[ChatModelManager] 编辑对话模型: {} ({})", target.getCode(), target.getModel());
        return toInfo(requireRow(id));
    }

    /** 删除：删行；删的是默认行则自动移交 sort 最小的启用行（无启用行则允许无默认） */
    public boolean delete(Long id) {
        AgentxChatModel row = requireRow(id);
        boolean wasDefault = row.getIsDefault() != null && row.getIsDefault() == 1;
        int deleted = mapper.deleteById(id);
        if (deleted > 0 && wasDefault) {
            reassignDefault();
        }
        invalidate();
        log.info("[ChatModelManager] 删除对话模型: {} (db={}, wasDefault={})", row.getCode(), deleted > 0, wasDefault);
        return deleted > 0;
    }

    /** 启停：停用默认行时自动移交默认（保证解析永远有落点） */
    public ChatModelInfo toggle(Long id, boolean enabled) {
        AgentxChatModel row = requireRow(id);
        int newValue = enabled ? 1 : 0;
        if (row.getEnabled() != null && row.getEnabled() == newValue) {
            return toInfo(row);
        }
        mapper.update(null, new LambdaUpdateWrapper<AgentxChatModel>()
                .eq(AgentxChatModel::getId, id)
                .set(AgentxChatModel::getEnabled, newValue)
                .set(AgentxChatModel::getUpdatedAt, LocalDateTime.now()));
        if (!enabled && row.getIsDefault() != null && row.getIsDefault() == 1) {
            reassignDefault();
        }
        invalidate();
        log.info("[ChatModelManager] 对话模型切换: {} → enabled={}", row.getCode(), enabled);
        return toInfo(requireRow(id));
    }

    /** 设为默认（须已启用）：清其他行默认标记后指向本行 */
    public ChatModelInfo setDefault(Long id) {
        AgentxChatModel row = requireRow(id);
        if (row.getEnabled() == null || row.getEnabled() != 1) {
            throw new IllegalArgumentException("模型已停用，请先启用再设为默认");
        }
        moveDefaultTo(id);
        invalidate();
        log.info("[ChatModelManager] 默认模型移交: {}", row.getCode());
        return toInfo(requireRow(id));
    }

    /**
     * 连通测试（不落缓存）：按请求体（编辑场景 id 非空时用库中行兜底缺省字段与凭据）
     * 独立构建 ChatModel → 发一条极小对话补全 → 关闭；id 非空时把结果落到 last_check_time/last_error。
     */
    public ChatModelTestResult test(Long id, ChatModelUpsertRequest req) {
        AgentxChatModel probe = id == null ? new AgentxChatModel() : shallowCopy(requireRow(id));
        applyRequest(probe, req);
        mergeSecret(probe, id == null ? null : requireRow(id), req);
        validate(probe);

        long start = System.currentTimeMillis();
        String reply = null;
        String error = null;
        try {
            ChatModel model = buildChatModel(probe);
            String answer = model.call("ping");
            reply = answer == null ? "" : answer.trim();
            if (reply.length() > 200) {
                reply = reply.substring(0, 200);
            }
        } catch (Exception e) {
            error = truncateError(e);
        }
        long elapsed = System.currentTimeMillis() - start;

        if (id != null) {
            try {
                mapper.update(null, new LambdaUpdateWrapper<AgentxChatModel>()
                        .eq(AgentxChatModel::getId, id)
                        .set(AgentxChatModel::getLastCheckTime, LocalDateTime.now())
                        .set(AgentxChatModel::getLastError, error));
            } catch (Exception e) {
                log.debug("[ChatModelManager] 测试结果落库失败（不影响测试本身）: {}", e.getMessage());
            }
        }
        if (error != null) {
            log.warn("[ChatModelManager] 连通测试失败: code={} ({} ms): {}", probe.getCode(), elapsed, error);
        } else {
            log.info("[ChatModelManager] 连通测试成功: code={} ({} ms)", probe.getCode(), elapsed);
        }
        return new ChatModelTestResult(error == null, reply, elapsed, error);
    }

    // ==================== 私有辅助 ====================

    /** 请求体 → 实体（code 仅创建语义由调用方处理；非凭据字段 null 保持目标态原值） */
    private void applyRequest(AgentxChatModel row, ChatModelUpsertRequest req) {
        if (req == null) {
            return;
        }
        if (req.name() != null && !req.name().isBlank()) {
            row.setName(req.name().trim());
        }
        if (req.provider() != null) {
            row.setProvider(blankToNull(req.provider().trim()));
        }
        if (req.baseUrl() != null) {
            row.setBaseUrl(blankToNull(req.baseUrl().trim()));
        }
        if (req.completionsPath() != null) {
            row.setCompletionsPath(blankToNull(req.completionsPath().trim()));
        }
        if (req.model() != null) {
            row.setModel(blankToNull(req.model().trim()));
        }
        if (req.temperature() != null) {
            row.setTemperature(req.temperature());
        }
        if (req.timeoutMs() != null) {
            row.setTimeoutMs(req.timeoutMs());
        }
        if (req.sort() != null) {
            row.setSort(req.sort());
        }
        if (req.remark() != null) {
            row.setRemark(blankToNull(req.remark().trim()));
        }
        if (req.thinkingOffParam() != null) {
            row.setThinkingOffParam(blankToNull(req.thinkingOffParam().trim()));
        }
    }

    /**
     * 凭据字段落值（实现"回传含 *** = 未修改"契约）：
     * apiKey null/空串/含掩码 → 用库中原值（编辑回显原样回传即保持不变）；明文 → 覆盖。
     * 必须在 validate 之前调用。
     */
    private void mergeSecret(AgentxChatModel row, AgentxChatModel existing, ChatModelUpsertRequest req) {
        if (req == null) {
            return;
        }
        if (req.apiKeyUnchanged()) {
            row.setApiKey(existing == null ? null : existing.getApiKey());
        } else {
            row.setApiKey(req.apiKey().trim());
        }
    }

    /** 全字段校验；非法抛 IllegalArgumentException → Controller 转 R.fail */
    static void validate(AgentxChatModel row) {
        if (row.getCode() == null || row.getCode().isBlank()) {
            throw new IllegalArgumentException("模型标识（code）不能为空");
        }
        if (!row.getCode().matches(CODE_PATTERN)) {
            throw new IllegalArgumentException("模型标识只能包含字母/数字/点/下划线/中划线: " + row.getCode());
        }
        if (row.getCode().length() > 64) {
            throw new IllegalArgumentException("模型标识过长（最多 64 字符）");
        }
        if (row.getName() == null || row.getName().isBlank()) {
            throw new IllegalArgumentException("显示名不能为空");
        }
        if (row.getName().length() > 64) {
            throw new IllegalArgumentException("显示名过长（最多 64 字符）");
        }
        if (row.getBaseUrl() == null || !row.getBaseUrl().matches("^https?://.+")) {
            throw new IllegalArgumentException("Base URL 必须以 http:// 或 https:// 开头");
        }
        if (row.getBaseUrl().length() > 500) {
            throw new IllegalArgumentException("Base URL 过长（最多 500 字符）");
        }
        row.setCompletionsPath(normalizeCompletionsPath(row.getCompletionsPath()));
        if (!row.getCompletionsPath().matches("^/\\S+$") || row.getCompletionsPath().length() > MAX_COMPLETIONS_PATH_LENGTH) {
            throw new IllegalArgumentException("补全路径必须以 / 开头且不含空格（默认 " + DEFAULT_COMPLETIONS_PATH
                    + "，智谱 GLM 等 /v4 风格端点用 /chat/completions）");
        }
        if (row.getApiKey() == null || row.getApiKey().isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }
        if (row.getModel() == null || row.getModel().isBlank()) {
            throw new IllegalArgumentException("模型名不能为空");
        }
        if (row.getModel().length() > 128) {
            throw new IllegalArgumentException("模型名过长（最多 128 字符）");
        }
        double temperature = row.getTemperature() == null ? DEFAULT_TEMPERATURE : row.getTemperature();
        if (temperature < MIN_TEMPERATURE || temperature > MAX_TEMPERATURE) {
            throw new IllegalArgumentException("温度必须在 " + MIN_TEMPERATURE + "~" + MAX_TEMPERATURE + " 之间");
        }
        row.setTemperature(temperature);
        row.setTimeoutMs(normalizeTimeout(row.getTimeoutMs()));
        if (row.getRemark() != null && row.getRemark().length() > 500) {
            throw new IllegalArgumentException("备注过长（最多 500 字符）");
        }
        if (row.getThinkingOffParam() != null) {
            if (row.getThinkingOffParam().length() > MAX_THINKING_OFF_PARAM_LENGTH) {
                throw new IllegalArgumentException("关思考参数过长（最多 " + MAX_THINKING_OFF_PARAM_LENGTH + " 字符）");
            }
            // 必须是 JSON 对象：运行时将原样注入请求体 extra_body，坏配置越早暴露越好
            try {
                Map<String, ?> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(row.getThinkingOffParam(), Map.class);
                if (parsed == null) {
                    throw new IllegalArgumentException("空值");
                }
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("关思考参数必须是 JSON 对象，如 " + DEFAULT_THINKING_OFF_PARAM);
            }
        }
    }

    static int normalizeTimeout(Integer timeoutMs) {
        int timeout = timeoutMs == null ? DEFAULT_TIMEOUT_MS : timeoutMs;
        if (timeout < MIN_TIMEOUT_MS || timeout > MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException("超时必须在 " + MIN_TIMEOUT_MS + "~" + MAX_TIMEOUT_MS + " 毫秒之间");
        }
        return timeout;
    }

    /** completions 路径归一化：空 → 默认 /v1/chat/completions；trim 后原样返回 */
    static String normalizeCompletionsPath(String path) {
        return path == null || path.isBlank() ? DEFAULT_COMPLETIONS_PATH : path.trim();
    }

    /** 默认标记移交到指定行（清其他行） */
    private void moveDefaultTo(Long id) {
        mapper.update(null, new LambdaUpdateWrapper<AgentxChatModel>()
                .set(AgentxChatModel::getIsDefault, 0));
        mapper.update(null, new LambdaUpdateWrapper<AgentxChatModel>()
                .eq(AgentxChatModel::getId, id)
                .set(AgentxChatModel::getIsDefault, 1)
                .set(AgentxChatModel::getUpdatedAt, LocalDateTime.now()));
    }

    /** 默认自动移交：sort 最小的启用行（无启用行则允许暂时无默认，resolve 落第一个启用行） */
    private void reassignDefault() {
        List<AgentxChatModel> enabled = listEnabled();
        if (enabled.isEmpty()) {
            return;
        }
        moveDefaultTo(enabled.get(0).getId());
    }

    /** 写操作后清实例缓存（下一请求按新配置重建，运行中会话用旧实例跑完） */
    private void invalidate() {
        instances.clear();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** 浅拷贝（test/update 用：目标态 = 库中行 + 请求覆盖） */
    private AgentxChatModel shallowCopy(AgentxChatModel src) {
        AgentxChatModel copy = new AgentxChatModel();
        copy.setId(src.getId());
        copy.setCode(src.getCode());
        copy.setName(src.getName());
        copy.setProvider(src.getProvider());
        copy.setBaseUrl(src.getBaseUrl());
        copy.setCompletionsPath(src.getCompletionsPath());
        copy.setApiKey(src.getApiKey());
        copy.setModel(src.getModel());
        copy.setTemperature(src.getTemperature());
        copy.setTimeoutMs(src.getTimeoutMs());
        copy.setEnabled(src.getEnabled());
        copy.setIsDefault(src.getIsDefault());
        copy.setSort(src.getSort());
        copy.setRemark(src.getRemark());
        copy.setSource(src.getSource());
        copy.setLastError(src.getLastError());
        copy.setLastCheckTime(src.getLastCheckTime());
        copy.setCreatedAt(src.getCreatedAt());
        copy.setUpdatedAt(src.getUpdatedAt());
        return copy;
    }

    private AgentxChatModel requireRow(Long id) {
        AgentxChatModel row = id == null ? null : mapper.selectById(id);
        if (row == null) {
            throw new IllegalArgumentException("对话模型不存在: " + id);
        }
        return row;
    }

    private static String truncateError(Exception e) {
        String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
        return msg.length() > MAX_LAST_ERROR_LENGTH ? msg.substring(0, MAX_LAST_ERROR_LENGTH) : msg;
    }

    /** 实体 → VO（凭据脱敏） */
    private ChatModelInfo toInfo(AgentxChatModel row) {
        return new ChatModelInfo(
                row.getId(),
                row.getCode(),
                row.getName(),
                row.getProvider(),
                row.getBaseUrl(),
                normalizeCompletionsPath(row.getCompletionsPath()),
                maskSecret(row.getApiKey()),
                row.getModel(),
                row.getTemperature(),
                row.getTimeoutMs(),
                row.getEnabled() != null && row.getEnabled() == 1,
                row.getIsDefault() != null && row.getIsDefault() == 1,
                row.getSort(),
                row.getRemark(),
                row.getThinkingOffParam(),
                row.getSource() == null ? SOURCE_MANUAL : row.getSource(),
                row.getLastError(),
                row.getLastCheckTime(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    /** 脱敏：保留前 3 后 4（长度 ≤8 全掩码）；空返回空串（对齐 McpServerManager 口径） */
    static String maskSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        String s = secret.trim();
        if (s.length() <= 8) {
            return "***";
        }
        return s.substring(0, 3) + "***" + s.substring(s.length() - 4);
    }
}
