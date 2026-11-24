package com.llmetor.dataagent.dataagent.agent;

import com.llmetor.dataagent.dataagent.file.FileManageService;
import com.llmetor.dataagent.dataagent.mcp.McpClientRegistry;
import com.llmetor.dataagent.dataagent.mcp.McpServerManager;
import com.llmetor.dataagent.dataagent.model.ChatModelManager;
import com.llmetor.dataagent.dataagent.prompt.SessionFilesPromptBuilder;
import com.llmetor.dataagent.dataagent.prompt.SystemPrompt;
import com.llmetor.dataagent.dataagent.prompt.UserContextBuilder;
import com.llmetor.dataagent.dataagent.skill.SkillManager;
import com.llmetor.miniagentx.agent.AgentTaskManager;
import com.llmetor.miniagentx.agent.ReactAgent;
import com.llmetor.miniagentx.agent.LoopGuardConfig;
import com.llmetor.miniagentx.interrupt.PauseStateStore;
import com.llmetor.miniagentx.memory.AgentxSessionStore;
import com.llmetor.miniagentx.model.AgentStreamEvent;
import com.llmetor.miniagentx.model.RunnableParams;
import com.llmetor.miniagentx.model.ThinkingMode;
import com.llmetor.miniagentx.tools.SkillsTool;
import com.llmetor.miniagentx.tools.search.DeferredToolRegistry;
import com.llmetor.miniagentx.tools.search.ToolSearchConfig;
import com.llmetor.miniagentx.trace.CompositeTraceCollector;
import com.llmetor.miniagentx.trace.TraceCollector;
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
 * （此前 Tavily 为构造期一次性装配的硬编码 bean，增删需改 yml 重启。）
 *
 * <p>每请求按 userId 重建 ReactAgent（instructions 含用户上下文 + 会话文件段）。
 */
@Slf4j
@Component
public class DodoAgent {

    /** 斜杠指定技能时追加到 system prompt 末尾的优先加载指令（%s = 技能名） */
    private static final String USER_SKILL_DIRECTIVE = """
            <用户指定技能>
            用户已通过斜杠命令显式指定本次任务使用技能「%s」。你必须：
            1. 第一步立即调用 Skill 工具加载该技能（不要先自行作答）；
            2. 严格按技能提示词的流程执行任务；
            3. 若该技能与本轮问题明显不匹配，先简要向用户说明，再按更合理的方式继续。
            </用户指定技能>
            """;

    /** M-SA4：主路由系统提示词（multi 模式） */
    private static final String ROUTER_SYSTEM_PROMPT = """
            你是华西项管平台智能体「多多」的主路由调度助手（RouterAgent）。
            你负责理解用户意图、承接多轮上下文，并将专业任务精准委派给以下有状态垂类专员（每个专员拥有独立的跨轮记忆和专属工具池）：

            1. `call_data_analysis`（数据分析专员）：数据库查表、项目/合同/台账/进度统计分析、指标口径查询、排名对比、图表生成及多轮下钻追问；
            2. `call_declarant_fill`（资料填报专员）：项目资料首次填报、查我的项目清单、查阶段树/填报节点清单、续填草稿、暂存资料与提交审核；
            3. `call_reviewer_workflow`（审核审批专员）：查询待我审核审批的任务、查看待审台账详情、办理通过/驳回/部分通过；
            4. `call_declarant_resubmit`（驳回重提专员）：查询我被驳回需修改重提的待办任务、查看驳回原因、暂存修改与重新提审；
            5. `cancelConfirm`：当用户要求取消某个高危确认令牌（如"取消 ct_xxx"）时，**直接由你调用 `cancelConfirm` 立即作废**，无需转交专员。

            ## 严格调度铁律
            - **闲聊/通用问候直答**：如果用户只是打招呼、问你是谁或能做什么，请直接友好回答，**严禁调用任何工具或子专员**！
            - **自包含交接**：调用 `call_*` 专员时，`task` 参数必须自包含，原样带上用户原文中的项目名、阶段、筛选条件、`[业务文件已上传]` 信息及 `确认执行 ct_xxx` 令牌，不要省略关键实体。
            - **高危确认原样上浮**：当子专员返回待用户确认的提审/重提/审批计划（含 `ct_xxx` 令牌）时，直接原样清晰展示给用户等待其确认，**严禁擅自替用户再次调用确认**。
            - **跨领域复合任务**：若用户一次需求横跨多个领域（如先查数再填报），你可以先用 `TodoWrite` 制定宏观步骤，再按顺序委派对应专员并汇总最终结论。
            """;

    /** M-SA4：multi 模式下斜杠命令指向垂类子 Agent 时的强制定向指令 */
    private static final String ROUTER_SKILL_DIRECTIVE = """
            <用户指定垂类专员>
            用户已通过斜杠命令显式指定本次任务使用「%s」。你必须第一步立即调用 `%s` 工具委派给对应专员，严禁自行编造答案或调用其他垂类专员。
            </用户指定垂类专员>
            """;

    /** yml 兜底 ChatModel（spring-ai 自动装配 bean）：库空/无可用模型时 resolve 的落点 */
    private final ChatModel chatModel;
    /** 对话模型管理器（agentx_chat_model）：每请求按前端传的 code 解析实际 ChatModel */
    private final ChatModelManager chatModelManager;
    private final AgentTaskManager taskManager;
    private final DataSource dataSource;
    private final AgentxSessionStore sessionStore;
    /** 旧轨 trace 审计（系统三表，照旧写不动——D1） */
    private final TraceManager traceManager;
    /** 新轨组合采集器（旧 TraceManager + 监控自有表 MonitorTraceCollector 双写——D2/D5） */
    private final TraceCollector traceCollector;
    private final PauseStateStore pauseStateStore;
    private final UserContextBuilder userContextBuilder;
    private final FileManageService fileManageService;
    private final SessionFilesPromptBuilder sessionFilesPromptBuilder;
    private final com.llmetor.dataagent.dataagent.tools.AnalyzeFileTool analyzeFileTool;
    private final SkillManager skillManager;
    /** M-SA4：垂类子 Agent 注册表 */
    private final SubAgentRegistry subAgentRegistry;
    /** M-SA4：默认架构模式（single / multi） */
    private final String defaultAgentMode;
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
    /** 循环熔断配置（Fix A，yml 可配；豁免 executeSql 等合法高频工具） */
    private final LoopGuardConfig loopGuardConfig;

    public DodoAgent(ChatModel chatModel,
                     ChatModelManager chatModelManager,
                     AgentTaskManager taskManager,
                     DataSource dataSource,
                     com.llmetor.dataagent.dataagent.monitor.detect.DetectDispatcher detectDispatcher,
                     PauseStateStore pauseStateStore,
                     UserContextBuilder userContextBuilder,
                     FileManageService fileManageService,
                     SessionFilesPromptBuilder sessionFilesPromptBuilder,
                     com.llmetor.dataagent.dataagent.tools.AnalyzeFileTool analyzeFileTool,
                     SkillManager skillManager,
                     SubAgentRegistry subAgentRegistry,
                     List<ToolCallback> dataResidentTools,
                     com.llmetor.dataagent.dataagent.config.DataAgentToolsConfig.DeferredToolCandidates dataDeferredToolCandidates,
                     McpClientRegistry mcpRegistry,
                     com.llmetor.miniagentx.context.ContextPolicy contextPolicy,
                     @Value("${data-agent.agent.mode:single}") String defaultAgentMode,
                     @Value("${data-agent.agent.max-rounds:100}") int maxRounds,
                     @Value("${data-agent.agent.retry-interval-ms:3000}") long retryIntervalMs,
                     @Value("${data-agent.agent.thinking-mode:REASONING_CONTENT}") ThinkingMode thinkingMode,
                     @Value("${data-agent.agent.tool-search-max-results:8}") int toolSearchMaxResults,
                     @Value("${data-agent.agent.loop-guard.enabled:true}") boolean loopGuardEnabled,
                     @Value("${data-agent.agent.loop-guard.warn-after:3}") int loopGuardWarnAfter,
                     @Value("${data-agent.agent.loop-guard.escalate-after:5}") int loopGuardEscalateAfter,
                     @Value("${data-agent.agent.loop-guard.break-after:7}") int loopGuardBreakAfter,
                     @Value("${data-agent.agent.loop-guard.ignore-tools:executeSql}") String loopGuardIgnoreTools) {
        this.chatModel = chatModel;
        this.chatModelManager = chatModelManager;
        this.taskManager = taskManager;
        this.dataSource = dataSource;
        // 共享持久化设施：每请求重建 Agent 时不再重复包装/建表；中断快照 JDBC 落库（M3）
        this.sessionStore = new AgentxSessionStore(dataSource);
        this.traceManager = new TraceManager(dataSource);
        // 双轨写入（监控中心自有表方案 §4.1）：旧轨系统表照旧，新轨监控三表互不影响；
        // Step 3 找到异常（03 方案 §4）：终态后挂异常检测派发器（closeSession → 异步检测）
        var monitorCollector = new com.llmetor.dataagent.dataagent.monitor.MonitorTraceCollector(dataSource);
        monitorCollector.setDetectDispatcher(detectDispatcher);
        this.traceCollector = new CompositeTraceCollector(List.of(traceManager, monitorCollector));
        this.pauseStateStore = pauseStateStore;
        this.userContextBuilder = userContextBuilder;
        this.fileManageService = fileManageService;
        this.sessionFilesPromptBuilder = sessionFilesPromptBuilder;
        this.analyzeFileTool = analyzeFileTool;
        this.skillManager = skillManager;
        this.subAgentRegistry = subAgentRegistry;
        this.defaultAgentMode = defaultAgentMode;
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
        this.loopGuardConfig = new LoopGuardConfig(loopGuardEnabled,
                loopGuardWarnAfter, loopGuardEscalateAfter, loopGuardBreakAfter,
                parseIgnoreTools(loopGuardIgnoreTools));
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
     * @param skillName      用户斜杠指定的技能名（可空）：非空时 SkillsTool 仅装该技能并注入优先加载指令
     * @param modelCode      用户选择的对话模型 code（可空）：空/失效回退默认模型（库空回退 yml 兜底 bean）
     * @param thinking       深度思考开关（null/true = 走模型默认；false = 按模型注入关思考请求参数）
     */
    public Flux<AgentStreamEvent> stream(String query, String conversationId, Long userId,
                                         List<String> fileIds, boolean online, String skillName,
                                         String modelCode, boolean thinking) {
        return stream(query, conversationId, userId, fileIds, online, skillName, modelCode, thinking, null);
    }

    /** M-SA4 双模入口：支持按请求级 agentMode（single / multi）动态切换单/多 Agent 链路 */
    public Flux<AgentStreamEvent> stream(String query, String conversationId, Long userId,
                                         List<String> fileIds, boolean online, String skillName,
                                         String modelCode, boolean thinking, String agentMode) {
        // 阶段一关联：本轮 system prompt 反查立即生效
        if (hasFiles(fileIds)) {
            fileManageService.linkToConversation(fileIds, conversationId);
        }
        boolean multi = isMultiMode(agentMode);
        ReactAgent agent = multi
                ? buildRouterAgent(userId, conversationId, fileIds, online, skillName, modelCode, thinking)
                : buildAgent(userId, conversationId, fileIds, online, skillName, modelCode, thinking);
        RunnableParams params = RunnableParams.builder()
                .conversationId(conversationId)
                .userId(userId == null ? "" : userId.toString())
                .agentCode(multi ? "router" : "single")
                // executeSql 的 inputSchema 有 userId 参数 → 内核执行前强制覆盖为真实值，不暴露给 LLM
                // M-E0 写通道：token/tenantId 同机制注入（写工具以当前用户身份转发主后端 REST）
                .toolParams(buildToolParams(userId, conversationId))
                .build();
        log.info("[data-agent] 收到查询: mode={}, convId={}, userId={}, query={}, fileIds={}, online={}, skill={}, model={}",
                multi ? "multi" : "single", conversationId, userId, query,
                fileIds == null ? 0 : fileIds.size(), online, skillName, modelCode);
        // 阶段二关联：Complete/Paused 事件携带 sessionId 后补写（历史回放按轮取附件）
        Flux<AgentStreamEvent> baseFlux = agent.stream(query, params);
        return hasFiles(fileIds) ? baseFlux.doOnNext(evt -> linkFilesFromEvent(fileIds, evt)) : baseFlux;
    }

    /** 打断运行中任务（→ Paused + 快照落库） */
    public boolean interrupt(String conversationId, String message) {
        return taskManager.interrupt(conversationId, message);
    }

    /**
     * M-E0 写通道 toolParams：userId（既有）+ token/tenantId（REST 转发凭证）+
     * conversationId（动作审计关联）。工具 inputSchema 声明同名参数即被内核强制注入，
     * LLM 传什么都无效。token/tenantId 取自当前请求线程的登录态（Filter 已解析）。
     */
    private Map<String, Object> buildToolParams(Long userId, String conversationId) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userId != null) {
            params.put("userId", userId.toString());
        }
        if (conversationId != null) {
            params.put("conversationId", conversationId);
        }
        com.llmetor.dataagent.auth.HuaxiAuthContext.AuthInfo auth =
                com.llmetor.dataagent.auth.HuaxiAuthContext.getOrNull();
        if (auth != null) {
            if (auth.token() != null) {
                params.put("token", auth.token());
            }
            if (auth.tenantId() != null) {
                params.put("tenantId", auth.tenantId().toString());
            }
        }
        return Map.copyOf(params);
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

    /** 丢弃中断快照（takeOver 顶掉旧断点时用；M-SA2 级联清理所有 :: 子快照） */
    public boolean discardInterruptedState(String conversationId) {
        boolean rootDeleted = pauseStateStore.delete(conversationId);
        int subDeleted = pauseStateStore.deleteByPrefix(conversationId + "::");
        return rootDeleted || subDeleted > 0;
    }

    /**
     * 从中断快照恢复执行（M3；M5 增加 fileIds，M6 增加 online；斜杠技能增加 skillName）。
     *
     * @param conversationId 会话窗口 ID
     * @param userId         当前登录用户 ID（重建 Agent 的用户上下文 + toolParams 注入）
     * @param resumeQuery    用户中断后补充的消息（空 = 纯恢复，本地工具重放续跑）
     * @param fileIds        本次恢复请求新上传的文件 ID（可空）
     * @param online         是否注入 Tavily 联网工具（前端联网开关）
     * @param skillName      用户斜杠指定的技能名（可空，语义同 {@link #stream}）
     * @param modelCode      用户选择的对话模型 code（可空，语义同 {@link #stream}）
     * @param thinking       深度思考开关（可空 = 默认开启，语义同 {@link #stream}）
     */
    public Flux<AgentStreamEvent> resumeStream(String conversationId, Long userId, String resumeQuery,
                                               List<String> fileIds, boolean online, String skillName,
                                               String modelCode, Boolean thinking) {
        return resumeStream(conversationId, userId, resumeQuery, fileIds, online, skillName, modelCode, thinking, null);
    }

    /** M-SA4 双模恢复入口 */
    public Flux<AgentStreamEvent> resumeStream(String conversationId, Long userId, String resumeQuery,
                                               List<String> fileIds, boolean online, String skillName,
                                               String modelCode, Boolean thinking, String agentMode) {
        if (hasFiles(fileIds)) {
            fileManageService.linkToConversation(fileIds, conversationId);
        }
        boolean multi = isMultiMode(agentMode);
        ReactAgent agent = multi
                ? buildRouterAgent(userId, conversationId, fileIds, online, skillName, modelCode, thinking)
                : buildAgent(userId, conversationId, fileIds, online, skillName, modelCode, thinking);
        String effective = (resumeQuery == null || resumeQuery.isBlank()) ? null : resumeQuery;
        log.info("[data-agent] 恢复中断会话: mode={}, convId={}, userId={}, resumeQuery={}, fileIds={}, online={}, model={}",
                multi ? "multi" : "single", conversationId, userId, effective,
                fileIds == null ? 0 : fileIds.size(), online, modelCode);
        Flux<AgentStreamEvent> baseFlux = agent.resumeStream(conversationId, effective);
        return hasFiles(fileIds) ? baseFlux.doOnNext(evt -> linkFilesFromEvent(fileIds, evt)) : baseFlux;
    }

    private boolean isMultiMode(String requestAgentMode) {
        String effective = (requestAgentMode != null && !requestAgentMode.isBlank())
                ? requestAgentMode.trim() : defaultAgentMode;
        return "multi".equalsIgnoreCase(effective);
    }

    /** M-SA4：构建主路由 RouterAgent（挂载 4 个有状态垂类 SubAgentTool + cancelConfirm + TodoWrite + 扩展技能） */
    private ReactAgent buildRouterAgent(Long userId, String conversationId, List<String> fileIds,
                                        boolean online, String skillName, String modelCode, Boolean thinking) {
        syncMcpToolsIfNeeded();
        String thinkingOffParam = resolveThinkingOffParam(modelCode, thinking);
        ChatModel resolvedModel = chatModelManager.resolve(modelCode, chatModel);

        StringBuilder prompt = new StringBuilder(ROUTER_SYSTEM_PROMPT);
        String userCtx = userContextBuilder.build(userId);
        if (userCtx != null && !userCtx.isBlank()) {
            prompt.append("\n\n---\n\n").append(userCtx);
        }
        String filesCtx = sessionFilesPromptBuilder.build(conversationId, fileIds);
        if (filesCtx != null && !filesCtx.isBlank()) {
            prompt.append("\n\n---\n\n").append(filesCtx);
        }
        if (skillName != null && !skillName.isBlank()) {
            String mappedTool = SubAgentRegistry.SKILL_TO_TOOL.get(skillName);
            if (mappedTool != null) {
                prompt.append("\n\n---\n\n").append(ROUTER_SKILL_DIRECTIVE.formatted(skillName, mappedTool));
            } else {
                prompt.append("\n\n---\n\n").append(USER_SKILL_DIRECTIVE.formatted(skillName));
            }
        }

        List<ToolCallback> routerTools = new ArrayList<>();
        routerTools.addAll(subAgentRegistry.buildSubAgentTools(
                userId, conversationId, fileIds, resolvedModel, thinkingOffParam, thinkingMode,
                maxRounds, retryIntervalMs, taskManager, dataSource, sessionStore,
                traceCollector, pauseStateStore, contextPolicy, loopGuardConfig));
        routerTools.add(subAgentRegistry.cancelConfirmTool());
        routerTools.add(subAgentRegistry.todoWriteTool());

        // 非垂类扩展技能保留给主路由 SkillsTool（如 skill-creator 等）
        List<String> extSkillDirs = skillManager.getEnabledSkillDirs().stream()
                .filter(dir -> {
                    String folderName = java.nio.file.Path.of(dir).getFileName().toString();
                    return !SubAgentRegistry.VERTICAL_SKILLS.contains(folderName);
                })
                .toList();
        if (!extSkillDirs.isEmpty()) {
            routerTools.add(SkillsTool.builder().addSkillsDirectories(extSkillDirs).build());
        }
        if (!fileManageService.listByConversationId(conversationId).isEmpty()) {
            routerTools.addAll(List.of(ToolCallbacks.from(analyzeFileTool)));
        }
        List<ToolCallback> onlineTools = onlineMcpTools;
        if (online && !onlineTools.isEmpty()) {
            routerTools.addAll(onlineTools);
        }

        return ReactAgent.builder()
                .chatModel(resolvedModel)
                .instructions(prompt.toString())
                .tools(routerTools)
                .contextPolicy(contextPolicy)
                .thinkingMode(thinkingMode)
                .thinkingOffParam(thinkingOffParam)
                .loopGuard(loopGuardConfig)
                .maxRounds(maxRounds)
                .maxRetries(3)
                .retryIntervalMs(retryIntervalMs)
                .taskManager(taskManager)
                .dataSource(dataSource)
                .sessionStore(sessionStore)
                .traceCollector(traceCollector)
                .stateStore(pauseStateStore)
                .build();
    }

    private String resolveThinkingOffParam(String modelCode, Boolean thinking) {
        if (!Boolean.FALSE.equals(thinking)) {
            return null;
        }
        com.llmetor.dataagent.dataagent.model.AgentxChatModel pickedRow = chatModelManager.pickRow(modelCode);
        if (pickedRow != null && pickedRow.getThinkingOffParam() != null && !pickedRow.getThinkingOffParam().isBlank()) {
            log.info("[data-agent] 深度思考已关闭: model={} 注入关思考参数", pickedRow.getCode());
            return pickedRow.getThinkingOffParam();
        }
        return null;
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

    private ReactAgent buildAgent(Long userId, String conversationId, List<String> fileIds,
                                  boolean online, String skillName, String modelCode, Boolean thinking) {
        syncMcpToolsIfNeeded();
        // 系统提示词（身份 + Skills 优先 + 工具发现引导 + 原则 + 输出规范）+ 用户上下文 + 会话文件段
        String instructions = SystemPrompt.build(userContextBuilder.build(userId))
                + "\n\n---\n\n"
                + sessionFilesPromptBuilder.build(conversationId, fileIds);
        // 斜杠指定技能：追加优先加载指令段（放末尾，recency 最强）
        if (skillName != null) {
            instructions = instructions + "\n\n---\n\n" + USER_SKILL_DIRECTIVE.formatted(skillName);
        }
        // 关"深度思考"：按实际生效模型行取关思考请求参数（与 resolve 同一套挑选逻辑）；
        // 显式 false 才关（null/true 走模型默认）；库空（yml 兜底 bean）不支持请求级关闭
        String thinkingOffParam = null;
        if (Boolean.FALSE.equals(thinking)) {
            com.llmetor.dataagent.dataagent.model.AgentxChatModel pickedRow = chatModelManager.pickRow(modelCode);
            if (pickedRow != null) {
                String param = pickedRow.getThinkingOffParam();
                if (param != null && !param.isBlank()) {
                    thinkingOffParam = param;
                    log.info("[data-agent] 深度思考已关闭: model={} 注入关思考参数", pickedRow.getCode());
                }
            }
        }
        return ReactAgent.builder()
                // 对话模型按请求解析：前端选的 code → agentx_chat_model 实例；
                // 空/失效回退默认，库空回退 yml 兜底 bean（老请求零破坏）
                .chatModel(chatModelManager.resolve(modelCode, chatModel))
                .instructions(instructions)
                .tools(availableTools(conversationId, online, skillName))
                // M7 延迟工具发现：SQL 六工具 + 图表工具经 tool_search 按需发现
                .deferredTools(deferredRegistry)
                // M7 上下文压缩：策略 yml 可配（默认 100 条/90K token 触发）
                .contextPolicy(contextPolicy)
                .thinkingMode(thinkingMode)
                // 关"深度思考"：非空时每轮 LLM 调用注入 extra_body（null = 思考走模型默认）
                .thinkingOffParam(thinkingOffParam)
                .loopGuard(loopGuardConfig)
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
                // trace 采集 SPI：旧轨 agentx_trace + 新轨监控自有表组合双写
                .traceCollector(traceCollector)
                // 任务中断快照pause管理器
                .stateStore(pauseStateStore)
                .build();
    }

    /**
     * 组装本次请求的<b>非延迟</b>工具：常驻层 + 条件注入层——
     * Skill（每请求按 DB 启用状态重建；斜杠指定技能时仅装该技能）+
     * analyzeFile（会话有文件）+ MCP 联网工具（online 且 ONLINE_TOGGLE 型已连通）。
     * SQL 六工具与 MCP ALWAYS 型工具已入延迟层（deferredRegistry），首轮不下发，由 tool_search 发现。
     */
    private List<ToolCallback> availableTools(String conversationId, boolean online, String skillName) {
        syncMcpToolsIfNeeded();
        List<ToolCallback> merged = new ArrayList<>(residentTools);

        // SkillsTool 每请求重建：getEnabledSkillDirs 实时查，停用立即生效；无启用技能不注册。
        // 斜杠指定技能时只装该技能（LLM 可见清单只剩它，无从跑偏）；
        // 目录缺失（选择后被并发停用）回退全量清单，仅靠指令段引导
        List<String> enabledDirs = skillManager.getEnabledSkillDirs();
        if (skillName != null) {
            String dir = skillManager.getEnabledSkillDir(skillName);
            if (dir != null) {
                enabledDirs = List.of(dir);
            } else {
                log.warn("[data-agent] 指定技能目录不可用，回退全量技能清单: {}", skillName);
            }
        }
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

    /** 熔断豁免名单解析：逗号分隔 → 去空白去空项（解析失败不阻断装配） */
    private static Set<String> parseIgnoreTools(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static boolean hasFiles(List<String> fileIds) {
        return fileIds != null && !fileIds.isEmpty();
    }
}
