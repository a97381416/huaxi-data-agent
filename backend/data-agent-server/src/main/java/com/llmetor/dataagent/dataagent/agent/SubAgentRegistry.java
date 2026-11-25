package com.llmetor.dataagent.dataagent.agent;

import com.llmetor.dataagent.dataagent.config.DataAgentToolsConfig;
import com.llmetor.dataagent.dataagent.file.FileManageService;
import com.llmetor.dataagent.dataagent.mcp.McpClientRegistry;
import com.llmetor.dataagent.dataagent.mcp.McpServerManager;
import com.llmetor.dataagent.dataagent.prompt.SessionFilesPromptBuilder;
import com.llmetor.dataagent.dataagent.prompt.UserContextBuilder;
import com.llmetor.dataagent.dataagent.skill.SkillManager;
import com.llmetor.dataagent.dataagent.tools.AnalyzeFileTool;
import com.llmetor.miniagentx.agent.AgentTaskManager;
import com.llmetor.miniagentx.agent.LoopGuardConfig;
import com.llmetor.miniagentx.agent.ReactAgent;
import com.llmetor.miniagentx.agent.SubAgentTool;
import com.llmetor.miniagentx.context.ContextPolicy;
import com.llmetor.miniagentx.interrupt.PauseStateStore;
import com.llmetor.miniagentx.memory.AgentxSessionStore;
import com.llmetor.miniagentx.model.ThinkingMode;
import com.llmetor.miniagentx.trace.TraceCollector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * M-SA4 垂类有状态子 Agent 注册表：
 * 将华西 4 大业务域（问数 / 首次填报 / 审核处理 / 驳回重提）封装为独立的 {@link SubAgentTool}，
 * 动态读取 {@link SkillManager} 中的最新 SKILL.md 正文（支持 /skills 页面在线编辑与启停热生效）。
 */
@Slf4j
@Component
public class SubAgentRegistry {

    public static final String CODE_DATA_ANALYSIS = "data-analysis";
    public static final String CODE_DECLARANT_FILL = "declarant-fill";
    public static final String CODE_REVIEWER_WORKFLOW = "reviewer-workflow";
    public static final String CODE_DECLARANT_WORKFLOW = "declarant-workflow";

    public static final String TOOL_DATA_ANALYSIS = "call_data_analysis";
    public static final String TOOL_DECLARANT_FILL = "call_declarant_fill";
    public static final String TOOL_REVIEWER_WORKFLOW = "call_reviewer_workflow";
    public static final String TOOL_DECLARANT_RESUBMIT = "call_declarant_resubmit";

    /** 已被提升为独立子 Agent 的 4 个垂类技能名（主路由 SkillsTool 排除它们，仅保留扩展技能） */
    public static final Set<String> VERTICAL_SKILLS = Set.of(
            CODE_DATA_ANALYSIS, CODE_DECLARANT_FILL, CODE_REVIEWER_WORKFLOW, CODE_DECLARANT_WORKFLOW);

    /** 技能名 → 主路由委派工具名（用于 /斜杠命令 强制定向委派） */
    public static final Map<String, String> SKILL_TO_TOOL = Map.of(
            CODE_DATA_ANALYSIS, TOOL_DATA_ANALYSIS,
            CODE_DECLARANT_FILL, TOOL_DECLARANT_FILL,
            CODE_REVIEWER_WORKFLOW, TOOL_REVIEWER_WORKFLOW,
            CODE_DECLARANT_WORKFLOW, TOOL_DECLARANT_RESUBMIT);

    /** 剥离 YAML frontmatter */
    private static final Pattern FRONTMATTER_RE = Pattern.compile("^---\\r?\\n[\\s\\S]*?\\r?\\n---\\r?\\n?");

    private final DataAgentToolsConfig.SubAgentToolGroups toolGroups;
    private final SkillManager skillManager;
    private final UserContextBuilder userContextBuilder;
    private final SessionFilesPromptBuilder sessionFilesPromptBuilder;
    private final FileManageService fileManageService;
    private final AnalyzeFileTool analyzeFileTool;
    private final McpClientRegistry mcpRegistry;

    public SubAgentRegistry(DataAgentToolsConfig.SubAgentToolGroups toolGroups,
                            SkillManager skillManager,
                            UserContextBuilder userContextBuilder,
                            SessionFilesPromptBuilder sessionFilesPromptBuilder,
                            FileManageService fileManageService,
                            AnalyzeFileTool analyzeFileTool,
                            McpClientRegistry mcpRegistry) {
        this.toolGroups = toolGroups;
        this.skillManager = skillManager;
        this.userContextBuilder = userContextBuilder;
        this.sessionFilesPromptBuilder = sessionFilesPromptBuilder;
        this.fileManageService = fileManageService;
        this.analyzeFileTool = analyzeFileTool;
        this.mcpRegistry = mcpRegistry;
    }

    public ToolCallback cancelConfirmTool() {
        return toolGroups.cancelConfirmTool();
    }

    public ToolCallback todoWriteTool() {
        return toolGroups.todoWriteTool();
    }

    /**
     * 为当前请求构建全部已启用的垂类 SubAgentTool 列表。
     */
    public List<ToolCallback> buildSubAgentTools(Long userId,
                                                 String rootConvId,
                                                 List<String> fileIds,
                                                 ChatModel resolvedChatModel,
                                                 String thinkingOffParam,
                                                 ThinkingMode thinkingMode,
                                                 int maxRounds,
                                                 long retryIntervalMs,
                                                 AgentTaskManager taskManager,
                                                 DataSource dataSource,
                                                 AgentxSessionStore sessionStore,
                                                 TraceCollector traceCollector,
                                                 PauseStateStore pauseStateStore,
                                                 ContextPolicy contextPolicy,
                                                 LoopGuardConfig loopGuardConfig) {
        String userCtx = userContextBuilder.build(userId);
        String filesCtx = sessionFilesPromptBuilder.build(rootConvId, fileIds);
        boolean hasAnyFile = !fileManageService.listByConversationId(rootConvId).isEmpty();
        List<ToolCallback> alwaysMcpTools = mcpRegistry.toolsOfInjectMode(McpServerManager.INJECT_ALWAYS);

        List<ToolCallback> result = new ArrayList<>();

        // 1. 问数分析子 Agent（常驻 SQL 六工具 + lookupGlossary + MCP 图表 + TodoWrite）
        if (isSkillEnabledOrFallback(CODE_DATA_ANALYSIS)) {
            List<ToolCallback> tools = new ArrayList<>(toolGroups.dataAnalysisTools());
            if (alwaysMcpTools != null && !alwaysMcpTools.isEmpty()) {
                tools.addAll(alwaysMcpTools);
            }
            if (hasAnyFile) {
                tools.addAll(List.of(ToolCallbacks.from(analyzeFileTool)));
            }
            String prompt = buildSubPrompt(CODE_DATA_ANALYSIS,
                    "你是华西项目管理平台的「数据分析专员」，负责查表、统计、对比、趋势分析、明细查询与可视化图表生成。"
                            + "你的 SQL 与图表工具已全部常驻可用，无需调用 tool_search，请直接按业务规范执行。"
                            + "面对「有多少/统计/汇总」类问题，优先使用 1~2 条聚合 SQL（COUNT/GROUP BY）直接得出结论并作答，避免无意义的多轮逐表探查或全量明细展开。",
                    userCtx, filesCtx);
            result.add(new SubAgentTool(
                    TOOL_DATA_ANALYSIS,
                    CODE_DATA_ANALYSIS,
                    "委派「数据分析专员」执行数据库统计查询、项目/合同/台账/进度分析、指标口径查询与图表生成（有跨轮记忆，支持追问下钻）",
                    () -> buildSubReactAgent(resolvedChatModel, prompt, tools, thinkingMode, thinkingOffParam,
                            subMaxRounds(maxRounds), retryIntervalMs, taskManager, dataSource, sessionStore, traceCollector,
                            pauseStateStore, contextPolicy, loopGuardConfig)));
        }

        // 2. 首次填报子 Agent（常驻填报 7 工具 + cancelConfirm + TodoWrite）
        if (isSkillEnabledOrFallback(CODE_DECLARANT_FILL)) {
            List<ToolCallback> tools = new ArrayList<>(toolGroups.declarantFillTools());
            if (hasAnyFile) {
                tools.addAll(List.of(ToolCallbacks.from(analyzeFileTool)));
            }
            String prompt = buildSubPrompt(CODE_DECLARANT_FILL,
                    "你是华西项目管理平台的「资料填报专员」，负责项目资料首次填报全流程（查我的项目→查阶段树→拉填报节点清单→暂存草稿→查询审核人→生成提审确认计划）。"
                            + "你的全部填报工具已常驻可用，无需调用 tool_search。若任务要求跨项目全量数量统计（如汇总全部已上报/已通过数量），请立即说明本专员仅负责填报办理与草稿管理、无法做全量聚合统计（需交由数据分析专员查库），严禁逐项目循环翻树盲猜！"
                            + "涉及 submitLedgerForReview 签发确认令牌（ct_xxx）时，严禁自行二次调用确认，必须把确认计划原样返回给用户等待确认！",
                    userCtx, filesCtx);
            result.add(new SubAgentTool(
                    TOOL_DECLARANT_FILL,
                    CODE_DECLARANT_FILL,
                    "委派「资料填报专员」办理项目资料首次填报、续填草稿、查看我的项目/阶段/填报节点清单、暂存资料及提交审核",
                    () -> buildSubReactAgent(resolvedChatModel, prompt, tools, thinkingMode, thinkingOffParam,
                            subMaxRounds(maxRounds), retryIntervalMs, taskManager, dataSource, sessionStore, traceCollector,
                            pauseStateStore, contextPolicy, loopGuardConfig)));
        }

        // 3. 审核处理子 Agent（常驻审核 6 工具 + cancelConfirm + TodoWrite）
        if (isSkillEnabledOrFallback(CODE_REVIEWER_WORKFLOW)) {
            List<ToolCallback> tools = new ArrayList<>(toolGroups.reviewerWorkflowTools());
            if (hasAnyFile) {
                tools.addAll(List.of(ToolCallbacks.from(analyzeFileTool)));
            }
            String prompt = buildSubPrompt(CODE_REVIEWER_WORKFLOW,
                    "你是华西项目管理平台的「审核审批专员」，负责查看待审核任务、审阅台账详情、查看会审选项，以及执行通过/驳回/部分通过的确认计划。"
                            + "你的全部审核工具已常驻可用，无需调用 tool_search。签发确认令牌（ct_xxx）后严禁自行二次确认，必须原样返回给用户等待确认！",
                    userCtx, filesCtx);
            result.add(new SubAgentTool(
                    TOOL_REVIEWER_WORKFLOW,
                    CODE_REVIEWER_WORKFLOW,
                    "委派「审核审批专员」查询待我审核的任务清单、查看待审资料详情，以及办理审核通过、驳回或部分通过审批",
                    () -> buildSubReactAgent(resolvedChatModel, prompt, tools, thinkingMode, thinkingOffParam,
                            subMaxRounds(maxRounds), retryIntervalMs, taskManager, dataSource, sessionStore, traceCollector,
                            pauseStateStore, contextPolicy, loopGuardConfig)));
        }

        // 4. 驳回重提子 Agent（常驻任务重提 5 工具 + TodoWrite）
        if (isSkillEnabledOrFallback(CODE_DECLARANT_WORKFLOW)) {
            List<ToolCallback> tools = new ArrayList<>(toolGroups.declarantWorkflowTools());
            if (hasAnyFile) {
                tools.addAll(List.of(ToolCallbacks.from(analyzeFileTool)));
            }
            String prompt = buildSubPrompt(CODE_DECLARANT_WORKFLOW,
                    "你是华西项目管理平台的「驳回重提专员」，负责查看申报人被驳回的待办任务、查看被驳回明细、暂存修改草稿以及重新提交审核。"
                            + "你的全部重提工具已常驻可用，无需调用 tool_search。调用 getTaskDetail 前必须确认任务属于资料台账类且带有效台账 ID，严禁把项目批注等非台账任务 ID 反复传入 getTaskDetail！签发确认令牌（ct_xxx）后严禁自行二次确认，必须原样返回给用户等待确认！",
                    userCtx, filesCtx);
            result.add(new SubAgentTool(
                    TOOL_DECLARANT_RESUBMIT,
                    CODE_DECLARANT_WORKFLOW,
                    "委派「驳回重提专员」查询我被驳回需修改重提的待办任务、查看驳回原因详情、暂存修改及重新提审",
                    () -> buildSubReactAgent(resolvedChatModel, prompt, tools, thinkingMode, thinkingOffParam,
                            subMaxRounds(maxRounds), retryIntervalMs, taskManager, dataSource, sessionStore, traceCollector,
                            pauseStateStore, contextPolicy, loopGuardConfig)));
        }

        return List.copyOf(result);
    }

    /** 限制子 Agent 单次委托最大轮次，防止无限探索拖垮 180s 超时窗口 */
    private static int subMaxRounds(int maxRounds) {
        return Math.min(Math.max(maxRounds, 1), 25);
    }

    private ReactAgent buildSubReactAgent(ChatModel chatModel,
                                          String instructions,
                                          List<ToolCallback> tools,
                                          ThinkingMode thinkingMode,
                                          String thinkingOffParam,
                                          int maxRounds,
                                          long retryIntervalMs,
                                          AgentTaskManager taskManager,
                                          DataSource dataSource,
                                          AgentxSessionStore sessionStore,
                                          TraceCollector traceCollector,
                                          PauseStateStore pauseStateStore,
                                          ContextPolicy contextPolicy,
                                          LoopGuardConfig loopGuardConfig) {
        return ReactAgent.builder()
                .chatModel(chatModel)
                .instructions(instructions)
                .tools(tools)
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

    /** 技能未在 DB 注册时兜底启用，若在 DB 中被显式停用则不挂载 */
    private boolean isSkillEnabledOrFallback(String skillName) {
        if (skillManager.isEnabled(skillName)) {
            return true;
        }
        // 如果 DB 中根本没有该记录（如最小测试库），默认启用；若存在且 disabled 则尊重管理员开关
        return skillManager.list().stream().noneMatch(s -> skillName.equals(s.name()));
    }

    private String buildSubPrompt(String skillName, String fallbackRole, String userCtx, String filesCtx) {
        String skillBody = loadSkillBody(skillName);
        StringBuilder sb = new StringBuilder();
        sb.append(fallbackRole).append("\n\n");
        if (!skillBody.isBlank()) {
            sb.append("## 业务操作规范（动态加载自技能 ").append(skillName).append("）\n")
                    .append(skillBody).append("\n\n");
        }
        sb.append("## 工具直发说明\n")
                .append("本专员所需全部工具已在当前会话直接提供，禁止调用或提及 `tool_search`。\n\n");
        if (userCtx != null && !userCtx.isBlank()) {
            sb.append("---\n\n").append(userCtx).append("\n\n");
        }
        if (filesCtx != null && !filesCtx.isBlank()) {
            sb.append("---\n\n").append(filesCtx).append("\n");
        }
        return sb.toString();
    }

    private String loadSkillBody(String skillName) {
        try {
            String dir = skillManager.getEnabledSkillDir(skillName);
            if (dir == null) {
                return "";
            }
            Path md = Path.of(dir, "SKILL.md");
            if (!Files.isRegularFile(md)) {
                return "";
            }
            String raw = Files.readString(md, StandardCharsets.UTF_8);
            String body = FRONTMATTER_RE.matcher(raw).replaceFirst("").trim();
            // 剥离旧版关于必须先调 tool_search 发现工具的提示行，避免子 Agent 困惑
            StringBuilder cleaned = new StringBuilder();
            for (String line : body.split("\\r?\\n")) {
                if (line.contains("tool_search")) {
                    continue;
                }
                cleaned.append(line).append("\n");
            }
            return cleaned.toString().trim();
        } catch (Exception e) {
            log.warn("[SubAgentRegistry] 读取技能正文失败，降级使用内置角色提示: skill={}", skillName, e);
            return "";
        }
    }
}
