package com.llmetor.dataagent.dataagent.agent;

import com.llmetor.dataagent.dataagent.file.FileManageService;
import com.llmetor.dataagent.dataagent.mcp.McpClientRegistry;
import com.llmetor.dataagent.dataagent.mcp.McpServerManager;
import com.llmetor.dataagent.dataagent.prompt.SessionFilesPromptBuilder;
import com.llmetor.dataagent.dataagent.prompt.SystemPrompt;
import com.llmetor.dataagent.dataagent.prompt.UserContextBuilder;
import com.llmetor.dataagent.dataagent.skill.SkillManager;
import com.llmetor.miniagentx.agent.AgentTaskManager;
import com.llmetor.miniagentx.agent.ReactAgent;
import com.llmetor.miniagentx.interrupt.PauseStateStore;
import com.llmetor.miniagentx.memory.AgentxSessionStore;
import com.llmetor.miniagentx.model.AgentStreamEvent;
import com.llmetor.miniagentx.model.RunnableParams;
import com.llmetor.miniagentx.model.ThinkingMode;
import com.llmetor.miniagentx.tools.SkillsTool;
import com.llmetor.miniagentx.tools.search.DeferredToolRegistry;
import com.llmetor.miniagentx.tools.search.ToolSearchConfig;
import com.llmetor.miniagentx.trace.TraceManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据分析智能体门面（对齐原版 DodoAgent 的 M7 形态——恢复三层工具装配）。
 *
 * <p>M7 新增：上下文工程（docs/03 M7）——
 * <ul>
 *   <li><b>三层工具装配</b>（对齐 docs/01 §1.1）：常驻层（lookupGlossary/todoWrite +
 *       条件注入的 Skill/analyzeFile/MCP 联网工具）随请求下发；延迟层（SQL 六工具 +
 *       MCP ALWAYS 型工具）进 {@link DeferredToolRegistry}，LLM 经 tool_search 按需发现
 *       （KEYWORD 模式，每请求 Session 内跨轮累积）；新会话首轮 tools 只剩常驻 + tool_search</li>
 *   <li><b>上下文压缩</b>（内核内置）：每轮 LLM 调用前超阈值（100 条/90K token）触发
 *       L1→L2→L4 策略链，offload 原文可 context_reload 取回——内核 ReactAgent 默认开启，
 *       本类只透传策略参数</li>
 * </ul>
 * <p>M6 补充：MCP 工具改为动态——{@link McpClientRegistry} 持有已连通服务的工具回调，
 * 本类每请求入口调 {@link #syncMcpToolsIfNeeded()} 按注册表 version 热同步：
 * ALWAYS 型反注册旧实例再注册新实例进延迟层；ONLINE_TOGGLE 型进常驻层随 online 开关注入。
 * （此前 mcp-echarts/Tavily 为构造期一次性装配的硬编码 bean，增删需改 yml 重启。）
 *
 * <p>每请求按 userId 重建 ReactAgent（instructions 含用户上下文 + 会话文件段）。
 */
@Slf4j
@Component
public class DodoAgent {

    private final ChatModel chatModel;
    private final AgentTaskManager taskManager;
    private final DataSource dataSource;
    private final AgentxSessionStore sessionStore;
    private final TraceManager traceManager;
    private final PauseStateStore pauseStateStore;
    private final UserContextBuilder userContextBuilder;
    private final FileManageService fileManageService;
    private final SessionFilesPromptBuilder sessionFilesPromptBuilder;
    private final com.llmetor.dataagent.dataagent.tools.AnalyzeFileTool analyzeFileTool;
    private final SkillManager skillManager;
    /** 常驻工具层：每请求工具清单必带（lookupGlossary + todoWrite） */
    private final List<ToolCallback> residentTools;
    /** 延迟工具注册表：SQL 六工具 + MCP ALWAYS 型工具（LLM 经 tool_search 发现） */
    private final DeferredToolRegistry deferredRegistry;

    /** MCP 客户端运行时注册表（McpServerManager 写、本类只读）——MCP 工具热生效的信号源 */
    private final McpClientRegistry mcpRegistry;

    // ── MCP 工具同步状态（guarded by mcpSyncLock）──
    private final Object mcpSyncLock = new Object();
    /** 已同步到的注册表版本（-1 = 从未同步，首次必同步） */
    private long syncedMcpVersion = -1;
    /** 当前注册进延迟层的 MCP 工具名（重建时先按名反注册，规避重名 putIfAbsent 保留先注册） */
    private final Set<String> mcpDeferredToolNames = new LinkedHashSet<>();
    /** 联网常驻层 MCP 工具（ONLINE_TOGGLE 型；online=true 且非空才注入） */
    private volatile List<ToolCallback> onlineMcpTools = List.of();

    private final int maxRounds;
    private final long retryIntervalMs;
    private final ThinkingMode thinkingMode;
    /** 压缩策略（yml 可配；联调时调小阈值可真实触发） */
    private final com.llmetor.miniagentx.context.ContextPolicy contextPolicy;

    public DodoAgent(ChatModel chatModel,
                     AgentTaskManager taskManager,
                     DataSource dataSource,
                     PauseStateStore pauseStateStore,
                     UserContextBuilder userContextBuilder,
                     FileManageService fileManageService,
                     SessionFilesPromptBuilder sessionFilesPromptBuilder,
                     com.llmetor.dataagent.dataagent.tools.AnalyzeFileTool analyzeFileTool,
                     SkillManager skillManager,
                     List<ToolCallback> dataResidentTools,
                     com.llmetor.dataagent.dataagent.config.DataAgentToolsConfig.DeferredToolCandidates dataDeferredToolCandidates,
                     McpClientRegistry mcpRegistry,
                     com.llmetor.miniagentx.context.ContextPolicy contextPolicy,
                     @Value("${data-agent.agent.max-rounds:100}") int maxRounds,
                     @Value("${data-agent.agent.retry-interval-ms:3000}") long retryIntervalMs,
                     @Value("${data-agent.agent.thinking-mode:REASONING_CONTENT}") ThinkingMode thinkingMode,
                     @Value("${data-agent.agent.tool-search-max-results:8}") int toolSearchMaxResults) {
        this.chatModel = chatModel;
        this.taskManager = taskManager;
        this.dataSource = dataSource;
        // 共享持久化设施：每请求重建 Agent 时不再重复包装/建表；中断快照 JDBC 落库（M3）
        this.sessionStore = new AgentxSessionStore(dataSource);
        this.traceManager = new TraceManager(dataSource);
        this.pauseStateStore = pauseStateStore;
        this.userContextBuilder = userContextBuilder;
        this.fileManageService = fileManageService;
        this.sessionFilesPromptBuilder = sessionFilesPromptBuilder;
        this.analyzeFileTool = analyzeFileTool;
        this.skillManager = skillManager;
        this.residentTools = List.copyOf(dataResidentTools);
        // 延迟层：SQL 六工具（业务候选）静态注册；MCP 工具由 syncMcpToolsIfNeeded 按版本动态增删
        // （重名注册由内核 warn 并保留先注册者——业务先注册，MCP 重名工具让位，缺陷清单项）
        this.deferredRegistry = new DeferredToolRegistry(
                new ToolSearchConfig(ToolSearchConfig.Mode.KEYWORD, toolSearchMaxResults));
        this.deferredRegistry.registerAll(dataDeferredToolCandidates.tools());
        this.mcpRegistry = mcpRegistry;
        this.maxRounds = maxRounds;
        this.retryIntervalMs = retryIntervalMs;
        this.thinkingMode = thinkingMode;
        this.contextPolicy = contextPolicy;
        log.info("[data-agent] 三层工具装配完成: 常驻={}个 {}, 延迟={}个 {}（MCP 工具按注册表版本动态同步）",
                residentTools.size(), toolNames(residentTools),
                deferredRegistry.size(), deferredRegistry.names());
    }

    /**
     * MCP 工具热同步：比对注册表 version，变更则重建——ALWAYS 型先反注册旧实例再注册
     * （规避重名 putIfAbsent 保留先注册），ONLINE_TOGGLE 型刷新联网常驻清单。
     * 每请求入口调用；变更后下一请求生效，运行中会话用旧清单跑完（不做插拔）。
     */
    private void syncMcpToolsIfNeeded() {
        long current = mcpRegistry.version();
        synchronized (mcpSyncLock) {
            if (current == syncedMcpVersion) {
                return;
            }
            deferredRegistry.unregisterAll(mcpDeferredToolNames);
            mcpDeferredToolNames.clear();
            List<ToolCallback> always = mcpRegistry.toolsOfInjectMode(McpServerManager.INJECT_ALWAYS);
            deferredRegistry.registerAll(always);
            for (ToolCallback t : always) {
                mcpDeferredToolNames.add(t.getToolDefinition().name());
            }
            onlineMcpTools = List.copyOf(mcpRegistry.toolsOfInjectMode(McpServerManager.INJECT_ONLINE_TOGGLE));
            syncedMcpVersion = current;
            log.info("[data-agent] MCP 工具同步(version={}): 延迟层ALWAYS={}个 {}, 联网常驻={}个 {}",
                    current, always.size(), toolNames(always),
                    onlineMcpTools.size(), toolNames(onlineMcpTools));
        }
    }

    /**
     * 流式执行一轮问答（M6：增加 online 联网开关）。
     *
     * @param query          用户问题
     * @param conversationId 会话窗口 ID
     * @param userId         当前登录用户 ID（进 ToolContext + toolParams 注入 + 用户上下文）
     * @param fileIds        本次上传的文件 ID（启动时立即 link 到会话，结束后按轮补 link）
     * @param online         是否注入 Tavily 联网工具（前端联网开关）
     */
    public Flux<AgentStreamEvent> stream(String query, String conversationId, Long userId,
                                         List<String> fileIds, boolean online) {
        // 阶段一关联：本轮 system prompt 反查立即生效
        if (hasFiles(fileIds)) {
            fileManageService.linkToConversation(fileIds, conversationId);
        }
        ReactAgent agent = buildAgent(userId, conversationId, fileIds, online);
        RunnableParams params = RunnableParams.builder()
                .conversationId(conversationId)
                .userId(userId == null ? "" : userId.toString())
                // executeSql 的 inputSchema 有 userId 参数 → 内核执行前强制覆盖为真实值，不暴露给 LLM
                .toolParams(userId == null ? Map.of() : Map.of("userId", userId.toString()))
                .build();
        log.info("[data-agent] 收到查询: convId={}, userId={}, query={}, fileIds={}, online={}",
                conversationId, userId, query, fileIds == null ? 0 : fileIds.size(), online);
        // M7-A01 观测点：首轮工具清单（延迟层不下发，tool_search 按需发现）
        List<ToolCallback> firstRound = availableTools(conversationId, online);
        log.info("[data-agent] 首轮工具清单({}个): {} + tool_search + context_reload | 延迟层({}个)经 tool_search 发现",
                firstRound.size() + 2, toolNames(firstRound), deferredRegistry.size());
        // 阶段二关联：Complete/Paused 事件携带 sessionId 后补写（历史回放按轮取附件）
        Flux<AgentStreamEvent> baseFlux = agent.stream(query, params);
        return hasFiles(fileIds) ? baseFlux.doOnNext(evt -> linkFilesFromEvent(fileIds, evt)) : baseFlux;
    }

    /** 打断运行中任务（→ Paused + 快照落库） */
    public boolean interrupt(String conversationId, String message) {
        return taskManager.interrupt(conversationId, message);
    }

    /**
     * 联网能力是否可用（存在 enabled 且工具拉取成功的 ONLINE_TOGGLE 型 MCP 服务，
     * Tavily 只是首个实例）——前端联网开关置灰的依据。
     */
    public boolean isOnlineAvailable() {
        syncMcpToolsIfNeeded();
        return !onlineMcpTools.isEmpty();
    }

    public boolean hasRunningTask(String conversationId) {
        return taskManager.hasRunningTask(conversationId);
    }

    /** 是否存在中断快照（存在时 /agent/stream 自动走恢复通道） */
    public boolean hasInterruptedState(String conversationId) {
        return pauseStateStore.has(conversationId);
    }

    /** 丢弃中断快照（takeOver 顶掉旧断点时用） */
    public boolean discardInterruptedState(String conversationId) {
        return pauseStateStore.delete(conversationId);
    }

    /**
     * 从中断快照恢复执行（M3；M5 增加 fileIds，M6 增加 online）。
     *
     * @param conversationId 会话窗口 ID
     * @param userId         当前登录用户 ID（重建 Agent 的用户上下文 + toolParams 注入）
     * @param resumeQuery    用户中断后补充的消息（空 = 纯恢复，本地工具重放续跑）
     * @param fileIds        本次恢复请求新上传的文件 ID（可空）
     * @param online         是否注入 Tavily 联网工具（前端联网开关）
     */
    public Flux<AgentStreamEvent> resumeStream(String conversationId, Long userId, String resumeQuery,
                                               List<String> fileIds, boolean online) {
        if (hasFiles(fileIds)) {
            fileManageService.linkToConversation(fileIds, conversationId);
        }
        ReactAgent agent = buildAgent(userId, conversationId, fileIds, online);
        String effective = (resumeQuery == null || resumeQuery.isBlank()) ? null : resumeQuery;
        log.info("[data-agent] 恢复中断会话: convId={}, userId={}, resumeQuery={}, fileIds={}, online={}",
                conversationId, userId, effective, fileIds == null ? 0 : fileIds.size(), online);
        Flux<AgentStreamEvent> baseFlux = agent.resumeStream(conversationId, effective);
        return hasFiles(fileIds) ? baseFlux.doOnNext(evt -> linkFilesFromEvent(fileIds, evt)) : baseFlux;
    }

    /**
     * 从流事件提取 sessionId 补写文件关联：Complete（正常完成）与 Paused（用户中断，
     * 内核 M5 起事件携带 sessionId）都处理。关联失败仅 warn——不阻断主流程。
     */
    private void linkFilesFromEvent(List<String> fileIds, AgentStreamEvent evt) {
        Long sessionId = null;
        String conversationId = null;
        if (evt.getSessionId() != null && !evt.getSessionId().isBlank()) {
            try {
                sessionId = Long.parseLong(evt.getSessionId());
            } catch (NumberFormatException e) {
                log.debug("[data-agent] 事件 sessionId 非数字，跳过 link: {}", evt.getSessionId());
                return;
            }
            conversationId = evt.getConversationId();
        }
        if (sessionId != null) {
            try {
                fileManageService.linkToSession(fileIds, sessionId, conversationId);
            } catch (Exception e) {
                log.warn("[data-agent] link 文件到 session 失败（不影响主流程）: sessionId={}, fileIds={}",
                        sessionId, fileIds, e);
            }
        }
    }

    private ReactAgent buildAgent(Long userId, String conversationId, List<String> fileIds, boolean online) {
        syncMcpToolsIfNeeded();
        // 系统提示词（身份 + Skills 优先 + 工具发现引导 + 原则 + 输出规范）+ 用户上下文 + 会话文件段
        String instructions = SystemPrompt.build(userContextBuilder.build(userId))
                + "\n\n---\n\n"
                + sessionFilesPromptBuilder.build(conversationId, fileIds);
        return ReactAgent.builder()
                .chatModel(chatModel)
                .instructions(instructions)
                .tools(availableTools(conversationId, online))
                // M7 延迟工具发现：SQL 六工具 + 图表工具经 tool_search 按需发现
                .deferredTools(deferredRegistry)
                // M7 上下文压缩：策略 yml 可配（默认 100 条/90K token 触发）
                .contextPolicy(contextPolicy)
                .thinkingMode(thinkingMode)
                .maxRounds(maxRounds)
                .maxRetries(3)
                .retryIntervalMs(retryIntervalMs)
                // 运行任务管理器
                // - 管理运行中任务的暂停情况
                // - interrupt（可恢复暂停）：任务挂起，会产生pause表快照
                // - stopTask（强制停止丢状态）: 任务消亡，不会产生pause表快照
                .taskManager(taskManager)
                .dataSource(dataSource)
                // 两张会话表 agentx_conversation、 agentx_session
                .sessionStore(sessionStore)
                // agentx_trace表
                .traceManager(traceManager)
                // 任务中断快照pause管理器
                .stateStore(pauseStateStore)
                .build();
    }

    /**
     * 组装本次请求的<b>非延迟</b>工具：常驻层 + 条件注入层——
     * Skill（每请求按 DB 启用状态重建）+ analyzeFile（会话有文件）+
     * MCP 联网工具（online 且 ONLINE_TOGGLE 型已连通）。
     * SQL 六工具与 MCP ALWAYS 型工具已入延迟层（deferredRegistry），首轮不下发，由 tool_search 发现。
     */
    private List<ToolCallback> availableTools(String conversationId, boolean online) {
        syncMcpToolsIfNeeded();
        List<ToolCallback> merged = new ArrayList<>(residentTools);

        // SkillsTool 每请求重建：getEnabledSkillDirs 实时查，停用立即生效；无启用技能不注册
        List<String> enabledDirs = skillManager.getEnabledSkillDirs();
        if (!enabledDirs.isEmpty()) {
            merged.add(SkillsTool.builder().addSkillsDirectories(enabledDirs).build());
        }

        boolean hasAnyFile = !fileManageService.listByConversationId(conversationId).isEmpty();
        if (hasAnyFile) {
            merged.addAll(List.of(ToolCallbacks.from(analyzeFileTool)));
        }

        List<ToolCallback> onlineTools = onlineMcpTools;
        if (online && !onlineTools.isEmpty()) {
            merged.addAll(onlineTools);
        }
        return List.copyOf(merged);
    }

    /** 三层装配速览（/agent/capabilities 用）：常驻/延迟/联网三层工具名清单 */
    public Map<String, Object> toolLayers() {
        syncMcpToolsIfNeeded();
        Map<String, Object> layers = new LinkedHashMap<>();
        List<String> resident = new ArrayList<>(toolNames(residentTools));
        if (!skillManager.getEnabledSkillDirs().isEmpty()) {
            resident.add("Skill");
        }
        resident.add("tool_search");
        resident.add("context_reload");
        layers.put("resident", resident);
        layers.put("deferred", deferredRegistry.names());
        layers.put("online", toolNames(onlineMcpTools));
        return layers;
    }

    private static List<String> toolNames(List<ToolCallback> tools) {
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
    }

    private static boolean hasFiles(List<String> fileIds) {
        return fileIds != null && !fileIds.isEmpty();
    }
}
