package com.llmetor.dataagent.dataagent.mcp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.llmetor.dataagent.dataagent.mcp.mapper.AgentxMcpServerMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP 服务管理器（M6 补充，对齐 SkillManager 模式）——agentx_mcp_server 表生命周期、
 * yml 存量种子迁移、三型传输客户端装配、CRUD 编排、连接测试、连通状态落库。
 *
 * <p>职责分界：本类写 {@link McpClientRegistry}（唯一的 MCP 运行时状态），
 * DodoAgent 只读（按 version 同步工具到延迟层/联网常驻层）。
 *
 * <p>生效语义：<b>变更即时重建客户端，下一请求热生效</b>；运行中的会话用旧清单跑完，
 * 不做插拔。连接失败不阻断保存（status=ERROR 落库标红，对齐 Tavily 无 key 的优雅降级）。
 *
 * <p>三型传输（mcp-core 1.1.0）：
 * <ul>
 *   <li>STREAMABLE：{@link HttpClientStreamableHttpTransport}（存量 mcp-echarts/Tavily 形态）</li>
 *   <li>SSE：{@link HttpClientSseClientTransport}（旧版远程服务，Base URL + sseEndpoint）</li>
 *   <li>STDIO：{@link StdioClientTransport}（本地子进程，command/args/env；
 *       Windows 下填 npx.cmd；凭据走 env，接口层脱敏）</li>
 * </ul>
 */
@Slf4j
@Component
public class McpServerManager {

    // ==================== 枚举常量（表内字符串，前端同值展示） ====================

    public static final String TRANSPORT_STREAMABLE = "STREAMABLE";
    public static final String TRANSPORT_SSE = "SSE";
    public static final String TRANSPORT_STDIO = "STDIO";
    public static final Set<String> TRANSPORTS = Set.of(TRANSPORT_STREAMABLE, TRANSPORT_SSE, TRANSPORT_STDIO);

    public static final String AUTH_NONE = "NONE";
    public static final String AUTH_BEARER = "BEARER";
    public static final Set<String> AUTH_TYPES = Set.of(AUTH_NONE, AUTH_BEARER);

    /** 注入方式：延迟层（tool_search 发现）/ 联网开关常驻层（对话页 online=true 才注入） */
    public static final String INJECT_ALWAYS = "ALWAYS";
    public static final String INJECT_ONLINE_TOGGLE = "ONLINE_TOGGLE";
    public static final Set<String> INJECT_MODES = Set.of(INJECT_ALWAYS, INJECT_ONLINE_TOGGLE);

    public static final String SOURCE_YML = "yml";
    public static final String SOURCE_MANUAL = "manual";

    public static final String STATUS_OK = "OK";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    /** 超时允许范围（毫秒） */
    private static final int MIN_TIMEOUT_MS = 1000;
    private static final int MAX_TIMEOUT_MS = 300_000;
    private static final int DEFAULT_TIMEOUT_MS = 60_000;

    /** last_error 截断长度（VARCHAR(2000) 留余量） */
    private static final int MAX_LAST_ERROR_LENGTH = 1500;

    /** 默认服务名占位（种子行） */
    private static final String SEED_CHART_NAME = "mcp-echarts";
    private static final String SEED_TAVILY_NAME = "tavily";

    private final JdbcTemplate jdbcTemplate;
    private final AgentxMcpServerMapper mapper;
    private final McpClientRegistry registry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** yml 种子配置（仅首次启动空表时种入，此后以表为准） */
    private final String seedChartUrl;
    private final String seedTavilyApiKey;
    private final String seedTavilyUrl;

    public McpServerManager(javax.sql.DataSource dataSource,
                            AgentxMcpServerMapper mapper,
                            McpClientRegistry registry,
                            @Value("${data-agent.chart.mcp-url:http://localhost:3033/mcp}") String seedChartUrl,
                            @Value("${tavily.api-key:}") String seedTavilyApiKey,
                            @Value("${tavily.mcp-url:https://mcp.tavily.com/mcp/}") String seedTavilyUrl) {
        // M5 踩坑固化的纪律：主库注入点一律拿 DataSource 自建 JdbcTemplate（见 README M5 不变量 6）
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.mapper = mapper;
        this.registry = registry;
        this.seedChartUrl = seedChartUrl;
        this.seedTavilyApiKey = seedTavilyApiKey;
        this.seedTavilyUrl = seedTavilyUrl;
    }

    @PostConstruct
    void init() {
        ensureTableExists();
        seedFromYmlIfNeeded();
        // 异步连通已启用服务：远程不可达时最长 timeout_ms 的握手不能拖死启动
        Thread.ofPlatform().daemon().name("mcp-init").start(this::connectAllEnabled);
    }

    @PreDestroy
    void shutdown() {
        for (McpSyncClient client : registry.clear()) {
            closeQuietly(client);
        }
    }

    // ==================== 建表与种子迁移 ====================

    /** 启动自动建表（M-H1 PG 版：原生 IF NOT EXISTS 幂等；updated_at 由应用层维护） */
    private void ensureTableExists() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agentx_mcp_server (
                    id              BIGINT        NOT NULL,
                    name            VARCHAR(64)   NOT NULL,
                    transport       VARCHAR(20)   NOT NULL DEFAULT 'STREAMABLE',
                    url             VARCHAR(500)  DEFAULT NULL,
                    sse_endpoint    VARCHAR(200)  DEFAULT NULL,
                    command         VARCHAR(200)  DEFAULT NULL,
                    args            VARCHAR(2000) DEFAULT NULL,
                    env             VARCHAR(2000) DEFAULT NULL,
                    auth_type       VARCHAR(20)   NOT NULL DEFAULT 'NONE',
                    auth_token      VARCHAR(1000) DEFAULT NULL,
                    timeout_ms      INT           NOT NULL DEFAULT 60000,
                    inject_mode     VARCHAR(20)   NOT NULL DEFAULT 'ALWAYS',
                    enabled         SMALLINT      NOT NULL DEFAULT 1,
                    source          VARCHAR(20)   DEFAULT NULL,
                    status          VARCHAR(20)   NOT NULL DEFAULT 'UNKNOWN',
                    last_error      VARCHAR(2000) DEFAULT NULL,
                    tool_count      INT           DEFAULT NULL,
                    last_check_time TIMESTAMP     DEFAULT NULL,
                    created_at      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
                    updated_at      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id)
                )
                """);
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_agentx_mcp_server_name ON agentx_mcp_server (name)");
        log.info("[McpServerManager] agentx_mcp_server 表就绪");
    }

    /**
     * 首次启动（表空）把 yml 存量两条 MCP 配置种入（source=yml，enabled=1）：
     * mcp-echarts（ALWAYS）+ tavily（ONLINE_TOGGLE，key 未配也种入，管理页补配即可用）。
     * 此后 DB 为权威源，yml 配置仅作种子。
     */
    private void seedFromYmlIfNeeded() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agentx_mcp_server", Long.class);
        if (count != null && count > 0) {
            return;
        }
        insertRow(seedRow(SEED_CHART_NAME, TRANSPORT_STREAMABLE, seedChartUrl,
                AUTH_NONE, null, INJECT_ALWAYS));
        insertRow(seedRow(SEED_TAVILY_NAME, TRANSPORT_STREAMABLE, seedTavilyUrl,
                AUTH_BEARER, seedTavilyApiKey == null ? "" : seedTavilyApiKey.trim(), INJECT_ONLINE_TOGGLE));
        log.info("[McpServerManager] 首次启动：yml 存量 MCP 配置种子入库（{}, {}），此后以表为准",
                SEED_CHART_NAME, SEED_TAVILY_NAME);
    }

    private AgentxMcpServer seedRow(String name, String transport, String url,
                                    String authType, String token, String injectMode) {
        AgentxMcpServer row = new AgentxMcpServer();
        row.setName(name);
        row.setTransport(transport);
        row.setUrl(url);
        row.setAuthType(authType);
        row.setAuthToken(token);
        row.setTimeoutMs(DEFAULT_TIMEOUT_MS);
        row.setInjectMode(injectMode);
        row.setEnabled(1);
        row.setSource(SOURCE_YML);
        row.setStatus(STATUS_UNKNOWN);
        return row;
    }

    private void insertRow(AgentxMcpServer row) {
        try {
            mapper.insert(row);
        } catch (Exception e) {
            log.warn("[McpServerManager] 种子行插入失败（可能已存在同名）: {} - {}", row.getName(), e.getMessage());
        }
    }

    // ==================== 客户端生命周期 ====================

    /** 启动异步连通全部已启用服务（互相独立，失败只落状态不互相影响） */
    void connectAllEnabled() {
        List<AgentxMcpServer> rows = mapper.selectList(
                new LambdaQueryWrapper<AgentxMcpServer>().eq(AgentxMcpServer::getEnabled, 1));
        log.info("[McpServerManager] 开始连通 {} 个已启用 MCP 服务", rows.size());
        for (AgentxMcpServer row : rows) {
            try {
                connectAndCache(row);
            } catch (Exception e) {
                log.warn("[McpServerManager] 连通流程异常（已落状态）: {}: {}", row.getName(), e.getMessage());
            }
        }
    }

    /**
     * 建客户端 → 拉工具 → 入注册表 → 状态落库。任何失败：关闭半成品、ERROR 落库、
     * 不入注册表（保存成功连接失败不抛——服务可能晚启动，可稍后手动重连）。
     * 前置约定：该服务名的旧客户端已由调用方 closeClient 移除。
     */
    void connectAndCache(AgentxMcpServer row) {
        long start = System.currentTimeMillis();
        McpSyncClient client = null;
        try {
            client = buildClient(row);
            ToolCallback[] tools = pullTools(client);
            McpClientRegistry.Entry old = registry.put(row.getName(), client, tools,
                    normalizeInjectMode(row.getInjectMode()));
            if (old != null) {
                closeQuietly(old.client());
            }
            updateStatus(row.getId(), STATUS_OK, null, tools.length);
            log.info("[McpServerManager] MCP 服务已连接: {} ({} 个工具, {} ms)",
                    row.getName(), tools.length, System.currentTimeMillis() - start);
        } catch (Exception e) {
            if (client != null) {
                closeQuietly(client);
            }
            String error = truncateError(e);
            updateStatus(row.getId(), STATUS_ERROR, error, null);
            log.warn("[McpServerManager] MCP 服务连接失败: {} ({} ms): {}",
                    row.getName(), System.currentTimeMillis() - start, error);
        }
    }

    /** 关闭并移除某服务的运行时客户端（停用/删除/热重建前置步骤） */
    void closeClient(String name) {
        McpClientRegistry.Entry old = registry.remove(name);
        if (old != null) {
            closeQuietly(old.client());
            log.info("[McpServerManager] MCP 服务客户端已关闭: {}", name);
        }
    }

    private static void closeQuietly(McpSyncClient client) {
        try {
            client.close();
        } catch (Exception e) {
            log.debug("[McpServerManager] 客户端关闭异常（忽略）: {}", e.getMessage());
        }
    }

    // ==================== 三型传输客户端装配 ====================

    /** 建客户端并完成 initialize 握手（超时用行内 timeout_ms；失败抛异常由调用方归类） */
    McpSyncClient buildClient(AgentxMcpServer row) {
        McpSyncClient client = McpClient.sync(buildTransport(row))
                .requestTimeout(Duration.ofMillis(
                        row.getTimeoutMs() == null ? DEFAULT_TIMEOUT_MS : row.getTimeoutMs()))
                .build();
        client.initialize();
        return client;
    }

    private io.modelcontextprotocol.spec.McpClientTransport buildTransport(AgentxMcpServer row) {
        String transport = normalizeTransport(row.getTransport());
        boolean bearer = AUTH_BEARER.equals(normalizeAuthType(row.getAuthType()))
                && row.getAuthToken() != null && !row.getAuthToken().isBlank();
        return switch (transport) {
            case TRANSPORT_SSE -> {
                HttpClientSseClientTransport.Builder b = HttpClientSseClientTransport.builder(row.getUrl());
                if (row.getSseEndpoint() != null && !row.getSseEndpoint().isBlank()) {
                    b.sseEndpoint(row.getSseEndpoint());
                }
                if (bearer) {
                    b.customizeRequest(rb -> rb.header("Authorization", "Bearer " + row.getAuthToken()));
                }
                yield b.build();
            }
            case TRANSPORT_STDIO -> new StdioClientTransport(
                    ServerParameters.builder(row.getCommand())
                            .args(parseArgs(row.getArgs()))
                            .env(parseEnv(row.getEnv()))
                            .build(),
                    McpJsonDefaults.getMapper());
            default -> { // STREAMABLE（存量形态：整 URL 作为 base，端点路径默认 /mcp）
                HttpClientStreamableHttpTransport.Builder b =
                        HttpClientStreamableHttpTransport.builder(row.getUrl());
                if (bearer) {
                    b.customizeRequest(rb -> rb.header("Authorization", "Bearer " + row.getAuthToken()));
                }
                yield b.build();
            }
        };
    }

    /** 拉取工具回调（SyncMcpToolCallbackProvider 包装，线程安全可跨请求复用） */
    private ToolCallback[] pullTools(McpSyncClient client) {
        return new SyncMcpToolCallbackProvider(List.of(client)).getToolCallbacks();
    }

    // ==================== JSON 参数解析（STDIO） ====================

    /** args JSON 数组 → List（非法抛 IllegalArgumentException，提示可读示例） */
    static List<String> parseArgs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> args = new ObjectMapper().readValue(json, new TypeReference<List<String>>() {
            });
            return args == null ? List.of() : args;
        } catch (Exception e) {
            throw new IllegalArgumentException("args 必须是 JSON 数组，如 [\"-y\",\"@modelcontextprotocol/server-xxx\"]");
        }
    }

    /** env JSON 对象 → Map（保持键序；非法抛 IllegalArgumentException） */
    static Map<String, String> parseEnv(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, String> env = new ObjectMapper().readValue(json,
                    new TypeReference<LinkedHashMap<String, String>>() {
                    });
            return env == null ? new LinkedHashMap<>() : env;
        } catch (Exception e) {
            throw new IllegalArgumentException("env 必须是 JSON 对象，如 {\"API_KEY\":\"xxx\"}");
        }
    }

    // ==================== 校验（static 便于单测） ====================

    /** 全字段校验；非法抛 IllegalArgumentException → Controller 转 R.fail */
    static void validate(AgentxMcpServer row) {
        if (row.getName() == null || row.getName().isBlank()) {
            throw new IllegalArgumentException("服务名不能为空");
        }
        if (row.getName().chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("服务名不能包含空格或换行: " + row.getName());
        }
        if (row.getName().length() > 64) {
            throw new IllegalArgumentException("服务名过长（最多 64 字符）");
        }
        String transport = normalizeTransport(row.getTransport());
        row.setTransport(transport);

        int timeout = row.getTimeoutMs() == null ? DEFAULT_TIMEOUT_MS : row.getTimeoutMs();
        if (timeout < MIN_TIMEOUT_MS || timeout > MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException("超时必须在 " + MIN_TIMEOUT_MS + "~" + MAX_TIMEOUT_MS + " 毫秒之间");
        }
        row.setTimeoutMs(timeout);

        String injectMode = normalizeInjectMode(row.getInjectMode());
        row.setInjectMode(injectMode);

        if (TRANSPORT_STDIO.equals(transport)) {
            // STDIO：command 必填；凭据走 env；auth 强制 NONE
            if (row.getCommand() == null || row.getCommand().isBlank()) {
                throw new IllegalArgumentException("STDIO 传输必须填写启动命令（Windows 下如 npx.cmd）");
            }
            row.setUrl(null);
            row.setSseEndpoint(null);
            row.setAuthType(AUTH_NONE);
            row.setAuthToken(null);
            parseArgs(row.getArgs());      // 只为提前校验可解析
            parseEnv(row.getEnv());
        } else {
            // HTTP 系：url 必填 http(s)；SSE 端点默认 /sse；STDIO 专属字段清空
            if (row.getUrl() == null || !row.getUrl().matches("^https?://.+")) {
                throw new IllegalArgumentException("服务 URL 必须以 http:// 或 https:// 开头");
            }
            row.setCommand(null);
            row.setArgs(null);
            row.setEnv(null);
            if (TRANSPORT_SSE.equals(transport)) {
                if (row.getSseEndpoint() == null || row.getSseEndpoint().isBlank()) {
                    row.setSseEndpoint("/sse");
                }
            } else {
                row.setSseEndpoint(null);
            }
            String authType = normalizeAuthType(row.getAuthType());
            row.setAuthType(authType);
            if (AUTH_NONE.equals(authType)) {
                row.setAuthToken(null);
            } else if (row.getAuthToken() == null || row.getAuthToken().isBlank()) {
                // BEARER 且无凭据：允许保存（远端可能匿名可访问），连接失败时 last_error 有因
                row.setAuthToken(null);
            }
        }
    }

    static String normalizeTransport(String transport) {
        if (transport == null || transport.isBlank()) {
            return TRANSPORT_STREAMABLE;
        }
        String t = transport.trim().toUpperCase();
        if (!TRANSPORTS.contains(t)) {
            throw new IllegalArgumentException("不支持的传输方式: " + transport + "（可选 STREAMABLE/SSE/STDIO）");
        }
        return t;
    }

    static String normalizeAuthType(String authType) {
        if (authType == null || authType.isBlank()) {
            return AUTH_NONE;
        }
        String a = authType.trim().toUpperCase();
        if (!AUTH_TYPES.contains(a)) {
            throw new IllegalArgumentException("不支持的认证方式: " + authType + "（可选 NONE/BEARER）");
        }
        return a;
    }

    static String normalizeInjectMode(String injectMode) {
        if (injectMode == null || injectMode.isBlank()) {
            return INJECT_ALWAYS;
        }
        String m = injectMode.trim().toUpperCase();
        if (!INJECT_MODES.contains(m)) {
            throw new IllegalArgumentException("不支持的注入方式: " + injectMode + "（可选 ALWAYS/ONLINE_TOGGLE）");
        }
        return m;
    }

    // ==================== 凭据脱敏与合并 ====================

    /** 脱敏：保留前 3 后 4（长度 ≤8 全掩码）；空返回空串 */
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

    /** 键名疑似密钥（接口层 env 值脱敏依据） */
    static boolean looksSecret(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toUpperCase();
        return k.contains("KEY") || k.contains("TOKEN") || k.contains("SECRET")
                || k.contains("PASSWORD") || k.contains("PWD");
    }

    /**
     * 凭据字段落值（token + env 全权由此方法负责，applyRequest 不碰这两个字段），
     * 实现"回传含 *** = 未修改"契约：
     * <ul>
     *   <li>authToken：null 或含掩码标记 → 用库中原值（编辑回显原样回传即保持不变）；
     *       明文（含空串=清除）→ 覆盖</li>
     *   <li>env：null → 保持 row 现值；非 null → 逐键合并，值含 *** 且原值同键存在 → 原值</li>
     * </ul>
     * 必须在 validate 之前调用（env 非法 JSON 在此即抛，validate 亦会复核最终态）。
     */
    void mergeSecrets(AgentxMcpServer row, AgentxMcpServer existing, McpServerUpsertRequest req) {
        if (req == null) {
            return;
        }
        if (req.authToken() != null && !req.tokenUnchanged()) {
            row.setAuthToken(req.authToken().trim());
        } else {
            row.setAuthToken(existing == null ? null : existing.getAuthToken());
        }
        if (req.env() == null) {
            return; // 保持 row.env 现值（update 的浅拷贝已带原值；create 为 null）
        }
        Map<String, String> incoming = parseEnv(req.env()); // 非法 JSON 抛 IAE → Controller 转 fail
        if (!incoming.isEmpty() && existing != null) {
            Map<String, String> old = parseEnv(existing.getEnv());
            for (Map.Entry<String, String> e : incoming.entrySet()) {
                String v = e.getValue();
                if (v != null && v.contains("***") && old.containsKey(e.getKey())) {
                    e.setValue(old.get(e.getKey()));
                }
            }
        }
        row.setEnv(incoming.isEmpty() ? null : writeJson(incoming));
    }

    /** Map → JSON 对象字符串（LinkedHashMap 保键序；失败返回 null，字段本就非必填） */
    String writeJson(Map<String, String> env) {
        try {
            return objectMapper.writeValueAsString(env);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== CRUD 编排 ====================

    /** 列表（凭据脱敏；按 id 稳定排序） */
    public List<McpServerInfo> list() {
        List<AgentxMcpServer> rows = mapper.selectList(
                new LambdaQueryWrapper<AgentxMcpServer>().orderByAsc(AgentxMcpServer::getId));
        return rows.stream().map(this::toInfo).toList();
    }

    /**
     * 新建：校验 → 落库（status=UNKNOWN）→ 启用则立即连通（结果落状态，失败不回滚保存）。
     * 重名抛 IllegalArgumentException。
     */
    public McpServerInfo create(McpServerUpsertRequest req) {
        AgentxMcpServer row = new AgentxMcpServer();
        applyRequest(row, req);
        mergeSecrets(row, null, req);
        validate(row);
        if (mapper.selectOne(new LambdaQueryWrapper<AgentxMcpServer>()
                .eq(AgentxMcpServer::getName, row.getName())) != null) {
            throw new IllegalArgumentException("服务名已存在: " + row.getName());
        }
        row.setEnabled(Boolean.FALSE.equals(req.enabled()) ? 0 : 1);
        row.setSource(SOURCE_MANUAL);
        row.setStatus(STATUS_UNKNOWN);
        mapper.insert(row);
        if (row.getEnabled() == 1) {
            connectAndCache(refresh(row.getId()));
        }
        log.info("[McpServerManager] 新建 MCP 服务: {} ({}, enabled={}, source=manual)",
                row.getName(), row.getTransport(), row.getEnabled());
        return toInfo(refresh(row.getId()));
    }

    /**
     * 编辑（name 不可改）：合并凭据 → 校验 → 全量更新配置字段 → 关旧客户端 → 启用则重连。
     * enabled 不在此改（走 /toggle）。
     */
    public McpServerInfo update(Long id, McpServerUpsertRequest req) {
        AgentxMcpServer existing = requireRow(id);
        AgentxMcpServer target = shallowCopy(existing);
        applyRequest(target, req);
        mergeSecrets(target, existing, req);
        validate(target);

        mapper.update(null, new LambdaUpdateWrapper<AgentxMcpServer>()
                .eq(AgentxMcpServer::getId, id)
                .set(AgentxMcpServer::getTransport, target.getTransport())
                .set(AgentxMcpServer::getUrl, target.getUrl())
                .set(AgentxMcpServer::getSseEndpoint, target.getSseEndpoint())
                .set(AgentxMcpServer::getCommand, target.getCommand())
                .set(AgentxMcpServer::getArgs, target.getArgs())
                .set(AgentxMcpServer::getEnv, target.getEnv())
                .set(AgentxMcpServer::getAuthType, target.getAuthType())
                .set(AgentxMcpServer::getAuthToken, target.getAuthToken())
                .set(AgentxMcpServer::getTimeoutMs, target.getTimeoutMs())
                .set(AgentxMcpServer::getInjectMode, target.getInjectMode()));

        // 热重建：旧状态属于旧配置 → 先重置 UNKNOWN，再关旧客户端，启用中则按新配置重连
        updateStatus(id, STATUS_UNKNOWN, null, null);
        closeClient(existing.getName());
        AgentxMcpServer reloaded = refresh(id);
        if (reloaded.getEnabled() == 1) {
            connectAndCache(reloaded);
        }
        log.info("[McpServerManager] 编辑 MCP 服务: {} ({})", reloaded.getName(), reloaded.getTransport());
        return toInfo(refresh(id));
    }

    /** 删除：关客户端 + 删行（含 yml 种子行——删即彻底移除） */
    public boolean delete(Long id) {
        AgentxMcpServer row = requireRow(id);
        closeClient(row.getName());
        int deleted = mapper.deleteById(id);
        log.info("[McpServerManager] 删除 MCP 服务: {} (db={})", row.getName(), deleted > 0);
        return deleted > 0;
    }

    /** 启停：启用 → 立即连通；停用 → 关客户端（工具即从清单消失） */
    public McpServerInfo toggle(Long id, boolean enabled) {
        AgentxMcpServer row = requireRow(id);
        int newValue = enabled ? 1 : 0;
        if (row.getEnabled() != null && row.getEnabled() == newValue) {
            return toInfo(row);
        }
        mapper.update(null, new LambdaUpdateWrapper<AgentxMcpServer>()
                .eq(AgentxMcpServer::getId, id)
                .set(AgentxMcpServer::getEnabled, newValue));
        if (enabled) {
            connectAndCache(refresh(id));
        } else {
            closeClient(row.getName());
            // 停用后状态无意义 → UNKNOWN（保留 tool_count 供展示）
            updateStatus(id, STATUS_UNKNOWN, null, row.getToolCount());
        }
        log.info("[McpServerManager] MCP 服务切换: {} → enabled={}", row.getName(), enabled);
        return toInfo(refresh(id));
    }

    /**
     * 连接测试（不落库不缓存）：按请求体（编辑场景 id 非空时用库中行兜底缺省字段与凭据）
     * 独立建客户端 → 握手 → 拉工具 → 关闭。
     */
    public McpTestResult test(Long id, McpServerUpsertRequest req) {
        AgentxMcpServer probe = id == null ? new AgentxMcpServer() : shallowCopy(requireRow(id));
        applyRequest(probe, req);
        mergeSecrets(probe, id == null ? null : requireRow(id), req);
        validate(probe);

        long start = System.currentTimeMillis();
        McpSyncClient client = null;
        try {
            client = buildClient(probe);
            ToolCallback[] tools = pullTools(client);
            List<String> names = new ArrayList<>();
            for (ToolCallback t : tools) {
                names.add(t.getToolDefinition().name());
            }
            return new McpTestResult(true, tools.length, names,
                    System.currentTimeMillis() - start, null);
        } catch (Exception e) {
            return new McpTestResult(false, 0, List.of(),
                    System.currentTimeMillis() - start, truncateError(e));
        } finally {
            if (client != null) {
                closeQuietly(client);
            }
        }
    }

    /** 工具预览（读运行时注册表；未连接返回空——前端据 status 提示先启用/重连） */
    public List<McpToolInfo> listTools(Long id) {
        AgentxMcpServer row = requireRow(id);
        McpClientRegistry.Entry entry = registry.get(row.getName());
        if (entry == null) {
            return List.of();
        }
        List<McpToolInfo> out = new ArrayList<>();
        for (ToolCallback t : entry.tools()) {
            out.add(new McpToolInfo(t.getToolDefinition().name(), t.getToolDefinition().description()));
        }
        return out;
    }

    /** 手动重连（管理页「重新拉取」）：关旧 → 启用则按当前配置重连并刷新状态 */
    public McpServerInfo reconnect(Long id) {
        AgentxMcpServer row = requireRow(id);
        closeClient(row.getName());
        if (row.getEnabled() == null || row.getEnabled() != 1) {
            throw new IllegalArgumentException("服务已停用，请先启用再重连");
        }
        connectAndCache(refresh(id));
        return toInfo(refresh(id));
    }

    // ==================== 私有辅助 ====================

    /** 请求体 → 实体（name 仅创建语义由调用方处理；非凭据字段 null 保持目标态原值） */
    private void applyRequest(AgentxMcpServer row, McpServerUpsertRequest req) {
        if (req == null) {
            return;
        }
        if (req.name() != null && !req.name().isBlank()) {
            row.setName(req.name().trim());
        }
        if (req.transport() != null) {
            row.setTransport(req.transport());
        }
        if (req.url() != null) {
            row.setUrl(blankToNull(req.url().trim()));
        }
        if (req.sseEndpoint() != null) {
            row.setSseEndpoint(blankToNull(req.sseEndpoint().trim()));
        }
        if (req.command() != null) {
            row.setCommand(blankToNull(req.command().trim()));
        }
        if (req.args() != null) {
            row.setArgs(blankToNull(req.args().trim()));
        }
        if (req.authType() != null) {
            row.setAuthType(req.authType());
        }
        // authToken / env 是"脱敏回显 + 不修改语义"的凭据字段，统一由 mergeSecrets 落值
        if (req.timeoutMs() != null) {
            row.setTimeoutMs(req.timeoutMs());
        }
        if (req.injectMode() != null) {
            row.setInjectMode(req.injectMode());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** 浅拷贝（test/update 用：目标态 = 库中行 + 请求覆盖） */
    private AgentxMcpServer shallowCopy(AgentxMcpServer src) {
        AgentxMcpServer copy = new AgentxMcpServer();
        copy.setId(src.getId());
        copy.setName(src.getName());
        copy.setTransport(src.getTransport());
        copy.setUrl(src.getUrl());
        copy.setSseEndpoint(src.getSseEndpoint());
        copy.setCommand(src.getCommand());
        copy.setArgs(src.getArgs());
        copy.setEnv(src.getEnv());
        copy.setAuthType(src.getAuthType());
        copy.setAuthToken(src.getAuthToken());
        copy.setTimeoutMs(src.getTimeoutMs());
        copy.setInjectMode(src.getInjectMode());
        copy.setEnabled(src.getEnabled());
        copy.setSource(src.getSource());
        copy.setStatus(src.getStatus());
        copy.setLastError(src.getLastError());
        copy.setToolCount(src.getToolCount());
        copy.setLastCheckTime(src.getLastCheckTime());
        copy.setCreatedAt(src.getCreatedAt());
        copy.setUpdatedAt(src.getUpdatedAt());
        return copy;
    }

    private AgentxMcpServer requireRow(Long id) {
        AgentxMcpServer row = id == null ? null : mapper.selectById(id);
        if (row == null) {
            throw new IllegalArgumentException("MCP 服务不存在: " + id);
        }
        return row;
    }

    private AgentxMcpServer refresh(Long id) {
        return requireRow(id);
    }

    private void updateStatus(Long id, String status, String error, Integer toolCount) {
        try {
            LambdaUpdateWrapper<AgentxMcpServer> uw = new LambdaUpdateWrapper<AgentxMcpServer>()
                    .eq(AgentxMcpServer::getId, id)
                    .set(AgentxMcpServer::getStatus, status)
                    .set(AgentxMcpServer::getLastError, error)
                    .set(AgentxMcpServer::getToolCount, toolCount)
                    .set(AgentxMcpServer::getLastCheckTime, LocalDateTime.now());
            mapper.update(null, uw);
        } catch (Exception e) {
            log.debug("[McpServerManager] 状态落库失败（不影响连接本身）: {}", e.getMessage());
        }
    }

    private static String truncateError(Exception e) {
        String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
        return msg.length() > MAX_LAST_ERROR_LENGTH ? msg.substring(0, MAX_LAST_ERROR_LENGTH) : msg;
    }

    /** 实体 → VO（凭据脱敏） */
    private McpServerInfo toInfo(AgentxMcpServer row) {
        return new McpServerInfo(
                row.getId(),
                row.getName(),
                normalizeTransport(row.getTransport()),
                row.getUrl(),
                row.getSseEndpoint(),
                row.getCommand(),
                row.getArgs(),
                maskedEnv(row.getEnv()),
                normalizeAuthType(row.getAuthType()),
                maskSecret(row.getAuthToken()),
                row.getTimeoutMs(),
                normalizeInjectMode(row.getInjectMode()),
                row.getEnabled() != null && row.getEnabled() == 1,
                row.getSource() == null ? SOURCE_MANUAL : row.getSource(),
                row.getStatus() == null ? STATUS_UNKNOWN : row.getStatus(),
                row.getLastError(),
                row.getToolCount(),
                row.getLastCheckTime(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    /** env JSON → 脱敏 Map（键序保持；疑似密钥键脱敏；解析失败返回空 Map） */
    private Map<String, String> maskedEnv(String envJson) {
        Map<String, String> out = new LinkedHashMap<>();
        if (envJson == null || envJson.isBlank()) {
            return out;
        }
        try {
            Map<String, String> env = parseEnv(envJson);
            for (Map.Entry<String, String> e : env.entrySet()) {
                out.put(e.getKey(), looksSecret(e.getKey()) ? maskSecret(e.getValue()) : e.getValue());
            }
        } catch (Exception ignored) {
            // 脏数据不阻断列表展示
        }
        return out;
    }
}
