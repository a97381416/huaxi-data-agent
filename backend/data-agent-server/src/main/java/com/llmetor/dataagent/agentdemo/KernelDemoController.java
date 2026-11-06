package com.llmetor.dataagent.agentdemo;

import com.llmetor.dataagent.auth.HuaxiAuthContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmetor.dataagent.common.R;
import com.llmetor.miniagentx.agent.ReactAgent;
import com.llmetor.miniagentx.model.AgentStreamEvent;
import com.llmetor.miniagentx.model.RunnableParams;
import lombok.Data;
import org.springframework.http.MediaType;import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M1 内核演示接口（SSE 全链路验收入口，M2 换正式 /agent/stream）。
 *
 * <ul>
 *   <li>POST /agent/demo/stream：SSE 流式执行（事件名=类型小写，data=事件 JSON）</li>
 *   <li>GET /agent/demo/stop：可恢复中断（→ Paused + 快照）</li>
 *   <li>GET /agent/demo/snapshot：查询内存中断快照</li>
 * </ul>
 */
@RestController
@RequestMapping("/agent/demo")
public class KernelDemoController {

    private final ReactAgent demoAgent;
    private final ObjectMapper objectMapper;
    /** M5 起 pgVectorJdbcTemplate 会顶掉主库 JdbcTemplate 自动装配，按 DataSource 自建避免按类型误注入 */
    private final JdbcTemplate jdbc;

    public KernelDemoController(ReactAgent demoAgent, ObjectMapper objectMapper, DataSource dataSource) {
        this.demoAgent = demoAgent;
        this.objectMapper = objectMapper;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Data
    public static class DemoStreamRequest {
        private String query;
        private String conversationId;
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
        Long originalMessageCount = jdbc.queryForObject(
                "SELECT COALESCE(SUM(JSON_LENGTH(state_data)), 0) FROM agentx_session " +
                        "WHERE conversation_id = ? AND state_key = 'original_messages'",
                Long.class, conversationId);
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
