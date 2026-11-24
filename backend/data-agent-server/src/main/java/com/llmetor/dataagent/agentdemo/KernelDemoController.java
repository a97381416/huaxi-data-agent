package com.llmetor.dataagent.agentdemo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmetor.dataagent.auth.HuaxiAuthContext;
import com.llmetor.dataagent.common.R;
import com.llmetor.dataagent.dataagent.monitor.MonitorTraceCollector;
import com.llmetor.miniagentx.agent.AgentTaskManager;
import com.llmetor.miniagentx.agent.ReactAgent;
import com.llmetor.miniagentx.agent.SubAgentTool;
import com.llmetor.miniagentx.interrupt.PauseStateStore;
import com.llmetor.miniagentx.memory.AgentxSessionStore;
import com.llmetor.miniagentx.model.AgentStreamEvent;
import com.llmetor.miniagentx.model.PauseState;
import com.llmetor.miniagentx.model.RunnableParams;
import com.llmetor.miniagentx.model.ThinkingMode;
import com.llmetor.miniagentx.trace.CompositeTraceCollector;
import com.llmetor.miniagentx.trace.TraceCollector;
import com.llmetor.miniagentx.trace.TraceManager;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M1 内核演示接口（SSE 全链路验收入口，M2 换正式 /agent/stream）及 M-SA 多 Agent 地基靶场。
 */
@RestController
@RequestMapping("/agent/demo")
public class KernelDemoController {

    private static final String DEMO_SUB_CODE = "demo-sub";

    private final ReactAgent demoAgent;
    private final ChatModel chatModel;
    private final DataSource dataSource;
    private final AgentTaskManager taskManager;
    private final PauseStateStore pauseStateStore;
    private final AgentxSessionStore sessionStore;
    private final TraceCollector traceCollector;
    private final ObjectMapper objectMapper;
    /** M5 起 pgVectorJdbcTemplate 会顶掉主库 JdbcTemplate 自动装配，按 DataSource 自建避免按类型误注入 */
    private final JdbcTemplate jdbc;

    public KernelDemoController(ReactAgent demoAgent,
                                ChatModel chatModel,
                                ObjectMapper objectMapper,
                                DataSource dataSource,
                                AgentTaskManager taskManager,
                                PauseStateStore pauseStateStore) {
        this.demoAgent = demoAgent;
        this.chatModel = chatModel;
        this.dataSource = dataSource;
        this.taskManager = taskManager;
        this.pauseStateStore = pauseStateStore;
        this.sessionStore = new AgentxSessionStore(dataSource);
        this.traceCollector = new CompositeTraceCollector(List.of(
                new TraceManager(dataSource),
                new MonitorTraceCollector(dataSource)));
        this.objectMapper = objectMapper;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Data
    public static class DemoStreamRequest {
        private String query;
        private String conversationId;
        /** M-SA1 靶场：覆盖子 Agent 硬超时毫秒数（可选，默认 60000） */
        private Long subTimeoutMs;
        /** M-SA5 靶场：开启主/子双层 TodoWrite 计划演示 */
        private Boolean enableDualTodoDemo;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody DemoStreamRequest request) {
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        String conversationId = (request.getConversationId() == null || request.getConversationId().isBlank())
                ? "demo_conv_" + UUID.randomUUID()
                : request.getConversationId();
        String userId = HuaxiAuthContext.getCurrentUserIdOrNull() == null ? "" : HuaxiAuthContext.getCurrentUserIdOrNull().toString();

        Map<String, String> custom = new LinkedHashMap<>();
        custom.put("demo", "mini-agentx M1");

        return demoAgent.stream(request.getQuery(),
                        RunnableParams.builder()
                                .conversationId(conversationId)
                                .userId(userId)
                                .customParams(custom)
                                .build())
                .map(this::toSse)
                .onErrorResume(e -> Flux.just(
                        toSse(AgentStreamEvent.error("STREAM_ERROR", e.getMessage(), null)),
                        toSse(AgentStreamEvent.complete(0, 0, conversationId, ""))));
    }

    /** 可恢复中断：返回是否找到运行任务与快照状态 */
    @GetMapping("/stop")
    public R<Map<String, Object>> stop(@RequestParam String conversationId) {
        boolean interrupted = demoAgent.interrupt(conversationId, "用户请求中断");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stopped", interrupted);
        data.put("hasInterruptState", demoAgent.hasInterruptedState(conversationId));
        return R.ok(data);
    }

    /** 查询中断快照（内存，M1） */
    @GetMapping("/snapshot")
    public R<Map<String, Object>> snapshot(@RequestParam String conversationId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hasSnapshot", demoAgent.hasInterruptedState(conversationId));
        var state = demoAgent.getInterruptedState(conversationId);
        if (state != null) {
            data.put("currentRound", state.getCurrentRound());
            data.put("safePoint", state.getSafePoint().name());
            data.put("reason", state.getReason().name());
            data.put("pendingToolCalls", state.getPendingToolCalls().size());
            data.put("totalPromptTokens", state.getTotalPromptTokens());
        }
        return R.ok(data);
    }

    /** 丢弃中断快照 */
    @GetMapping("/discard")
    public R<Boolean> discard(@RequestParam String conversationId) {
        return R.ok(demoAgent.discardInterruptedState(conversationId));
    }

    /**
     * 三表落库查证（M1 测试页 D 组用例的只读支持接口）：
     * 按会话号聚合 agentx_conversation / agentx_session / agentx_trace 的可断言事实。
     */
    @GetMapping("/persist-check")
    public R<Map<String, Object>> persistCheck(@RequestParam String conversationId) {
        Map<String, Object> data = new LinkedHashMap<>();

        // conversation：取最新一行
        Map<String, Object> conv = new LinkedHashMap<>();
        List<Map<String, Object>> convRows = jdbc.queryForList(
                "SELECT session_id, status, question, completed_at FROM agentx_conversation " +
                        "WHERE conversation_id = ? ORDER BY id DESC LIMIT 1", conversationId);
        if (convRows.isEmpty()) {
            conv.put("found", false);
        } else {
            Map<String, Object> row = convRows.get(0);
            conv.put("found", true);
            conv.put("sessionId", row.get("session_id"));
            conv.put("status", row.get("status"));
            conv.put("questionPresent", row.get("question") != null && !row.get("question").toString().isBlank());
            conv.put("hasCompletedAt", row.get("completed_at") != null);
        }
        data.put("conversation", conv);

        // session：双视图行数 + original 的消息条数（state_data 为消息数组 JSON，按片追加）+ item_index 连续性
        Map<String, Object> session = new LinkedHashMap<>();
        Integer originalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agentx_session WHERE conversation_id = ? AND state_key = 'original_messages'",
                Integer.class, conversationId);
        Integer workingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agentx_session WHERE conversation_id = ? AND state_key = 'working_messages'",
                Integer.class, conversationId);
        Integer originalMessageCount = jdbc.queryForObject(
                "SELECT COALESCE(SUM(jsonb_array_length(state_data::jsonb)), 0) FROM agentx_session " +
                        "WHERE conversation_id = ? AND state_key = 'original_messages'",
                Integer.class, conversationId);
        List<Integer> indexes = jdbc.queryForList(
                "SELECT item_index FROM agentx_session WHERE conversation_id = ? AND state_key = 'original_messages' " +
                        "ORDER BY item_index", Integer.class, conversationId);
        boolean contiguous = true;
        for (int i = 0; i < indexes.size(); i++) {
            if (indexes.get(i) != i) {
                contiguous = false;
                break;
            }
        }
        session.put("originalCount", originalCount);
        session.put("workingCount", workingCount);
        session.put("originalMessageCount", originalMessageCount);
        session.put("itemIndexContiguous", contiguous);
        data.put("session", session);

        // trace：轮数/成功失败/每轮字段完整性
        Map<String, Object> trace = new LinkedHashMap<>();
        List<Map<String, Object>> rounds = jdbc.queryForList(
                "SELECT round, input_data, output_data, prompt_tokens, completion_tokens, duration_ms, success " +
                        "FROM agentx_trace WHERE conversation_id = ? ORDER BY round", conversationId);
        trace.put("rounds", rounds.size());
        trace.put("successRounds", rounds.stream().filter(r -> asInt(r.get("success")) == 1).count());
        trace.put("failedRounds", rounds.stream().filter(r -> asInt(r.get("success")) == 0).count());
        trace.put("allHaveInputOutput", rounds.stream().allMatch(r ->
                notBlank(r.get("input_data")) && notBlank(r.get("output_data"))));
        trace.put("allHaveTokens", rounds.stream().allMatch(r ->
                asLong(r.get("prompt_tokens")) > 0 || asLong(r.get("completion_tokens")) > 0));
        trace.put("allHaveDuration", rounds.stream().allMatch(r -> asLong(r.get("duration_ms")) > 0));
        data.put("trace", trace);

        return R.ok(data);
    }

    // ==================== M-SA1~M-SA5 多 Agent 地基靶场 ====================

    /**
     * 多 Agent 靶场流式入口（DemoRouterAgent -> SubAgentTool -> DemoSubAgent）：
     * 自带严格递增 SSE id，用于稳定验证状态隔离、凭证透传、流透传、超时熔断、级联中断与 Trace 树。
     */
    @PostMapping(value = "/subagent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> subagentStream(@RequestBody DemoStreamRequest request,
                                                        HttpServletRequest httpReq) {
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        String convId = (request.getConversationId() == null || request.getConversationId().isBlank())
                ? "demo_sa_" + UUID.randomUUID()
                : request.getConversationId();
        String userId = HuaxiAuthContext.getCurrentUserIdOrNull() == null
                ? "" : HuaxiAuthContext.getCurrentUserIdOrNull().toString();
        String token = extractBearerToken(httpReq);
        long subTimeoutMs = request.getSubTimeoutMs() != null && request.getSubTimeoutMs() > 0
                ? request.getSubTimeoutMs() : 60_000L;
        boolean dualTodo = Boolean.TRUE.equals(request.getEnableDualTodoDemo());

        Map<String, Object> toolParams = new LinkedHashMap<>();
        toolParams.put("userId", userId);
        toolParams.put("conversationId", convId);
        if (token != null && !token.isBlank()) {
            toolParams.put("token", token);
        }

        ReactAgent router = buildDemoRouterAgent(subTimeoutMs, dualTodo);
        AtomicLong seq = new AtomicLong(0);
        return router.stream(request.getQuery(),
                        RunnableParams.builder()
                                .conversationId(convId)
                                .userId(userId)
                                .agentCode("router")
                                .toolParams(toolParams)
                                .build())
                .map(ev -> toSseWithId(ev, seq.incrementAndGet()))
                .onErrorResume(e -> Flux.just(
                        toSseWithId(AgentStreamEvent.error("STREAM_ERROR", e.getMessage(), null), seq.incrementAndGet()),
                        toSseWithId(AgentStreamEvent.complete(0, 0, convId, ""), seq.incrementAndGet())));
    }

    /** 多 Agent 靶场可恢复中断（M-SA2） */
    @GetMapping("/subagent/stop")
    public R<Map<String, Object>> subagentStop(@RequestParam String conversationId) {
        boolean interrupted = taskManager.interrupt(conversationId, "用户请求中断多Agent靶场");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stopped", interrupted);
        data.put("rootPauseSnapshot", pauseStateStore.has(conversationId));
        data.put("subPauseSnapshot", pauseStateStore.has(conversationId + "::" + DEMO_SUB_CODE));
        return R.ok(data);
    }

    /** 多 Agent 靶场断点恢复（M-SA2） */
    @PostMapping(value = "/subagent/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> subagentResume(@RequestBody DemoStreamRequest request) {
        String convId = request.getConversationId();
        if (convId == null || convId.isBlank()) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        long subTimeoutMs = request.getSubTimeoutMs() != null && request.getSubTimeoutMs() > 0
                ? request.getSubTimeoutMs() : 60_000L;
        ReactAgent router = buildDemoRouterAgent(subTimeoutMs, Boolean.TRUE.equals(request.getEnableDualTodoDemo()));
        String resumeQuery = (request.getQuery() == null || request.getQuery().isBlank()) ? null : request.getQuery();
        AtomicLong seq = new AtomicLong(0);
        return router.resumeStream(convId, resumeQuery)
                .map(ev -> toSseWithId(ev, seq.incrementAndGet()))
                .onErrorResume(e -> Flux.just(
                        toSseWithId(AgentStreamEvent.error("RESUME_ERROR", e.getMessage(), null), seq.incrementAndGet()),
                        toSseWithId(AgentStreamEvent.complete(0, 0, convId, ""), seq.incrementAndGet())));
    }

    private ReactAgent buildDemoRouterAgent(long subTimeoutMs, boolean enableDualTodo) {
        List<ToolCallback> subTools = new ArrayList<>(Arrays.asList(ToolCallbacks.from(new DemoSubTools())));
        if (enableDualTodo) {
            subTools.addAll(Arrays.asList(ToolCallbacks.from(new DemoTodoTool())));
        }
        ReactAgent subAgent = ReactAgent.builder()
                .chatModel(chatModel)
                .instructions("""
                        你是演示子助手（demo-sub），拥有独立跨轮记忆和专属工具：
                        - rememberSecret：保存暗号（请如实调用并记住结果）
                        - whoamiProbe：回显当前身份与凭证参数（如用户要求传某个 userId 请照传，框架会自动校验覆盖）
                        - hangProbe / slowStep：慢任务模拟工具（按要求的秒数调用）
                        - echo：原样回显文本
                        - 如配置了 TodoWrite 工具且任务要求建立子计划，先调用 TodoWrite 建立计划再完成。
                        收到任务后请直接调用对应工具并简洁、完整地返回结果（如调用了 whoamiProbe 请原样包含返回的 JSON）。""")
                .tools(subTools)
                .thinkingMode(ThinkingMode.REASONING_CONTENT)
                .maxRounds(10)
                .maxRetries(2)
                .retryIntervalMs(1500)
                .taskManager(taskManager)
                .dataSource(dataSource)
                .sessionStore(sessionStore)
                .traceCollector(traceCollector)
                .stateStore(pauseStateStore)
                .enableCompaction(false)
                .build();

        ToolCallback callDemoSub = new SubAgentTool(
                "call_demo_sub",
                DEMO_SUB_CODE,
                "委派演示子助手（demo-sub）执行工具任务（记忆暗号、身份探测、慢任务、echo 回显等）",
                () -> subAgent,
                subTimeoutMs);

        List<ToolCallback> routerTools = new ArrayList<>();
        routerTools.add(callDemoSub);
        if (enableDualTodo) {
            routerTools.addAll(Arrays.asList(ToolCallbacks.from(new DemoTodoTool())));
        }

        return ReactAgent.builder()
                .chatModel(chatModel)
                .instructions("""
                        你是多 Agent 地基测试主路由（DemoRouterAgent）。
                        凡是用户要求委派子助手（保存/查询暗号、调用 whoamiProbe/hangProbe/slowStep/echo 等），
                        你必须调用 call_demo_sub 工具委派给子助手，严禁自己编造工具结果。
                        如果用户要求先建主计划再委派子助手建子计划，请先调 TodoWrite 再调 call_demo_sub。
                        拿到子助手返回结果后，简洁准确地回复用户（保留关键暗号或返回 JSON）。""")
                .tools(routerTools)
                .thinkingMode(ThinkingMode.REASONING_CONTENT)
                .maxRounds(10)
                .maxRetries(2)
                .retryIntervalMs(1500)
                .taskManager(taskManager)
                .dataSource(dataSource)
                .sessionStore(sessionStore)
                .traceCollector(traceCollector)
                .stateStore(pauseStateStore)
                .enableCompaction(false)
                .build();
    }

    static class DemoSubTools {
        @Tool(description = "保存用户指定的暗号到子助手会话记忆中")
        public String rememberSecret(String secret) {
            return "已成功保存暗号: " + secret;
        }

        @Tool(description = "身份与凭证探针工具：返回实际注入的 userId、conversationId 以及 token 是否存在")
        public String whoamiProbe(String userId, String conversationId, String token) {
            boolean tokenPresent = token != null && !token.isBlank();
            return "{\"userId\":\"" + safe(userId) + "\",\"conversationId\":\"" + safe(conversationId)
                    + "\",\"tokenPresent\":" + tokenPresent + "}";
        }

        @Tool(description = "慢任务探针（模拟耗时操作，用于验证超时熔断或运行中中断）")
        public String hangProbe(Integer seconds) {
            return sleepSeconds(seconds == null ? 5 : seconds, "hangProbe-done");
        }

        @Tool(description = "慢步骤工具（模拟耗时计算，返回 OK-RESUME）")
        public String slowStep(Integer seconds) {
            return sleepSeconds(seconds == null ? 3 : seconds, "OK-RESUME");
        }

        @Tool(description = "原样回显输入文本")
        public String echo(String text) {
            return "echo: " + text;
        }

        private static String sleepSeconds(int sec, String okMsg) {
            int bounded = Math.max(1, Math.min(sec, 15));
            try {
                Thread.sleep(bounded * 1000L);
                return okMsg;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "INTERRUPTED";
            }
        }

        private static String safe(String s) {
            return s == null ? "" : s.replace("\"", "'");
        }
    }

    static class DemoTodoTool {
        @Tool(name = "TodoWrite", description = "更新当前执行计划步骤列表（JSON 数组字符串，每项含 content 与 status）")
        public String todoWrite(List<Map<String, Object>> todos) {
            return "{\"ok\":true,\"count\":" + (todos == null ? 0 : todos.size()) + "}";
        }
    }

    private static String extractBearerToken(HttpServletRequest req) {
        if (req == null) {
            return null;
        }
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        return auth;
    }

    /**
     * 多 Agent 地基查库探针（M-SA0~M-SA3；供 /test/mSA 浏览器用例断言，只读）：
     * 汇总 rootConvId 与子窗口 {rootConvId}::% 在 conversation / session / pause_state / trace / monitor 的落库事实。
     */
    @GetMapping("/subagent-check")
    public R<Map<String, Object>> subagentCheck(@RequestParam String conversationId) {
        String subLike = conversationId + "::%";
        String demoSubConvId = conversationId + "::" + DEMO_SUB_CODE;
        Map<String, Object> d = new LinkedHashMap<>();
        int rootRows = safeCount("SELECT COUNT(*) FROM agentx_conversation WHERE conversation_id = ?", conversationId);
        d.put("rootConvRowCount", rootRows);
        d.put("rootConversationFound", rootRows > 0);
        d.put("subConvRowCount", safeCount("SELECT COUNT(*) FROM agentx_conversation WHERE conversation_id LIKE ?", subLike));
        d.put("rootWorkingCount", safeCount(
                "SELECT COUNT(*) FROM agentx_session WHERE conversation_id = ? AND state_key = 'working_messages'", conversationId));
        d.put("subWorkingCount", safeCount(
                "SELECT COUNT(*) FROM agentx_session WHERE conversation_id LIKE ? AND state_key = 'working_messages'", subLike));

        int rootPauseCount = safeCount("SELECT COUNT(*) FROM agentx_pause_state WHERE conversation_id = ?", conversationId);
        int subPauseCount = safeCount("SELECT COUNT(*) FROM agentx_pause_state WHERE conversation_id LIKE ?", subLike);
        d.put("rootPauseCount", rootPauseCount);
        d.put("subPauseCount", subPauseCount);
        d.put("rootPauseSnapshot", rootPauseCount > 0 || pauseStateStore.has(conversationId));
        d.put("subPauseSnapshot", subPauseCount > 0 || pauseStateStore.has(demoSubConvId));
        PauseState subSnap = pauseStateStore.get(demoSubConvId);
        d.put("subSnapshotIsSubAgent", subSnap != null && subSnap.getParams() != null && subSnap.getParams().isSubAgent());

        boolean anySubRunning = taskManager.hasRunningTask(demoSubConvId);
        d.put("anySubTaskRunning", anySubRunning);
        d.put("runningTaskCount", anySubRunning ? 1 : 0);

        int oldTraceRoot = safeCount("SELECT COUNT(*) FROM agentx_trace WHERE conversation_id = ?", conversationId);
        int oldTraceSub = safeCount("SELECT COUNT(*) FROM agentx_trace WHERE conversation_id LIKE ?", subLike);
        d.put("rootTraceRounds", oldTraceRoot);
        d.put("subTraceRounds", oldTraceSub);
        d.put("oldTraceCount", oldTraceRoot + Math.max(0, oldTraceSub));
        d.put("subTraceWithParentCount", safeCount(
                "SELECT COUNT(*) FROM agentx_trace WHERE (conversation_id = ? OR conversation_id LIKE ?) AND parent_session_id IS NOT NULL",
                conversationId, subLike));

        // M-SA3 监控统计探针（列不存在时安全返回 0 / false）
        d.put("monitorQuestionCount", Math.max(0, safeCount(
                "SELECT COALESCE(question_count, 0) FROM agentx_monitor_conversation WHERE conversation_id = ?", conversationId)));
        d.put("monitorTotalRounds", Math.max(0, safeCount(
                "SELECT COALESCE(total_rounds, 0) FROM agentx_monitor_conversation WHERE conversation_id = ?", conversationId)));
        d.put("monitorSubSessionCount", Math.max(0, safeCount(
                "SELECT COUNT(*) FROM agentx_monitor_session WHERE conversation_id = ? AND parent_session_id IS NOT NULL", conversationId)));
        int matchedParent = safeCount(
                "SELECT COUNT(*) FROM agentx_monitor_session sub " +
                        "JOIN agentx_monitor_session root ON sub.parent_session_id = root.session_id " +
                        "WHERE sub.conversation_id = ? AND root.parent_session_id IS NULL", conversationId);
        d.put("subParentSessionMatched", matchedParent > 0);
        return R.ok(d);
    }

    /** 容错计数（返回 int 避开全局 Long->String 序列化）：SQL 失败（表/列未迁移）返回 -1 */
    private int safeCount(String sql, Object... args) {
        try {
            Integer n = jdbc.queryForObject(sql, Integer.class, args);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return -1;
        }
    }

    private ServerSentEvent<String> toSseWithId(AgentStreamEvent event, long id) {
        try {
            return ServerSentEvent.builder(objectMapper.writeValueAsString(event))
                    .id(String.valueOf(id))
                    .event(event.getType().name().toLowerCase())
                    .build();
        } catch (Exception e) {
            return ServerSentEvent.builder("{\"type\":\"ERROR\",\"message\":\"事件序列化失败\"}")
                    .id(String.valueOf(id))
                    .event("error")
                    .build();
        }
    }
    private static int asInt(Object v) {
        return v instanceof Number n ? n.intValue() : -1;
    }

    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : -1L;
    }

    private static boolean notBlank(Object v) {
        return v != null && !v.toString().isBlank();
    }

    private ServerSentEvent<String> toSse(AgentStreamEvent event) {
        try {
            return ServerSentEvent.builder(objectMapper.writeValueAsString(event))
                    .event(event.getType().name().toLowerCase())
                    .build();
        } catch (Exception e) {
            return ServerSentEvent.builder("{\"type\":\"ERROR\",\"message\":\"事件序列化失败\"}")
                    .event("error")
                    .build();
        }
    }
}
