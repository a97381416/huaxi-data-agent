package com.llmetor.dataagent.dataagent.session;

import com.llmetor.dataagent.auth.service.HuaxiAuthService;
import com.llmetor.dataagent.dataagent.file.AgentxFile;
import com.llmetor.dataagent.dataagent.file.FileManageService;
import com.llmetor.dataagent.dataagent.session.SessionMessageVO.AttachmentInfo;
import com.llmetor.dataagent.dataagent.stream.LiveStreamRegistry;
import com.llmetor.miniagentx.interrupt.PauseStateStore;
import com.llmetor.miniagentx.memory.MessageJsonSerializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 会话历史管理服务（M3，对齐原版 SessionService）。
 *
 * <p>数据源是内核 M1 落的两张表（JdbcTemplate 直查，不建业务实体）：
 * <ul>
 *   <li><b>列表</b>：agentx_conversation 按 userId 过滤、时间倒序，按 conversationId
 *       分组聚合（title=首轮问题截 40 字 / messageCount / lastActiveAt），
 *       streaming 标记来自 LiveStreamRegistry 批量探测</li>
 *   <li><b>详情</b>：每轮（一行 conversation）从 agentx_session 的 original_messages
 *       重构——answer 实质性判定 + timeline 三类事件（thinking/text/tool）+
 *       TodoWrite 工具参数侧信道解析为 todo 面板事件</li>
 *   <li><b>删除</b>：conversation 行（属主校验）+ session 消息行级联 + 中断快照清理</li>
 * </ul>
 */
@Slf4j
@Service
public class SessionService {

    private static final int TITLE_MAX_LENGTH = 40;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final HuaxiAuthService authService;
    private final LiveStreamRegistry liveStreams;
    private final PauseStateStore pauseStateStore;
    private final FileManageService fileManageService;
    private final MessageJsonSerializer serializer = new MessageJsonSerializer();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 刻意注入 DataSource 自建 JdbcTemplate，而非按类型注入 JdbcTemplate Bean——
     * M5 起 pgVectorJdbcTemplate（PG）也是 JdbcTemplate Bean，它会使主库模板的自动装配
     * 回退（@ConditionalOnMissingBean），按类型注入可能拿到 PG 模板打错库。
     */
    public SessionService(DataSource dataSource, HuaxiAuthService authService,
                          LiveStreamRegistry liveStreams, PauseStateStore pauseStateStore,
                          FileManageService fileManageService) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.authService = authService;
        this.liveStreams = liveStreams;
        this.pauseStateStore = pauseStateStore;
        this.fileManageService = fileManageService;
    }

    // ==================== 会话行 ====================

    /** conversation 行的最小投影（列表/详情共用） */
    private record ConvRow(long id, String conversationId, String sessionId,
                           String question, String createdAt, Long elapsedMs) {
    }

    private List<ConvRow> loadRows(String userId, String conversationId, boolean desc) {
        String order = desc ? "DESC" : "ASC";
        String sql = "SELECT id, conversation_id, session_id, question, created_at, completed_at " +
                "FROM agentx_conversation WHERE user_id = ?" +
                (conversationId != null ? " AND conversation_id = ?" : "") +
                " ORDER BY created_at " + order + ", id " + order;
        Object[] args = conversationId != null
                ? new Object[]{userId, conversationId}
                : new Object[]{userId};
        return jdbc.query(sql, (rs, i) -> {
            Timestamp created = rs.getTimestamp("created_at");
            Timestamp completed = rs.getTimestamp("completed_at");
            Long elapsedMs = (created != null && completed != null)
                    ? Math.max(0L, completed.getTime() - created.getTime()) : null;
            return new ConvRow(
                    rs.getLong("id"),
                    rs.getString("conversation_id"),
                    rs.getString("session_id"),
                    rs.getString("question"),
                    formatTs(created),
                    elapsedMs);
        }, args);
    }

    private static String formatTs(Timestamp ts) {
        return ts == null ? null : TS.format(ts.toLocalDateTime());
    }

    // ==================== 列表 ====================

    /**
     * 列出会话（按最近活跃倒序，按 conversationId 分组聚合，分页返回）。
     */
    public ConversationPage listConversations(Integer page, Integer size) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : size;

        String currentUserId = currentUserIdOrNull();
        if (currentUserId == null) {
            return ConversationPage.empty(p, s);
        }

        List<ConvRow> all = loadRows(currentUserId, null, true);
        if (all.isEmpty()) {
            return ConversationPage.empty(p, s);
        }

        // groupingBy 保持插入顺序 = 最近活跃的会话排前面
        Map<String, List<ConvRow>> grouped = all.stream()
                .collect(Collectors.groupingBy(ConvRow::conversationId, LinkedHashMap::new, Collectors.toList()));

        // 进行中的流一次取全，避免列表逐会话查询
        Set<String> activeIds = liveStreams.activeConversationIds();

        List<ConversationVO> allConvs = grouped.entrySet().stream().map(e -> {
            List<ConvRow> rounds = e.getValue();
            ConvRow latest = rounds.get(0);
            ConvRow first = rounds.get(rounds.size() - 1);
            return new ConversationVO(
                    e.getKey(),
                    truncate(first.question()),
                    rounds.size(),
                    first.createdAt(),
                    latest.createdAt(),
                    activeIds.contains(e.getKey())
            );
        }).toList();

        int total = allConvs.size();
        int from = Math.min(p * s, total);
        int to = Math.min(from + s, total);
        boolean hasMore = to < total;
        return new ConversationPage(allConvs.subList(from, to), total, p, s, hasMore);
    }

    // ==================== 详情 ====================

    /**
     * 查询会话详情（包含所有问答轮次，按时间正序）。
     */
    public ConversationDetailVO getConversation(String conversationId) {
        String currentUserId = currentUserIdOrNull();
        if (currentUserId == null) {
            return null;
        }
        List<ConvRow> rows = loadRows(currentUserId, conversationId, false);
        if (rows.isEmpty()) {
            return null;
        }
        List<SessionMessageVO> messages = rows.stream()
                .map(this::buildSessionMessage)
                .toList();
        return new ConversationDetailVO(conversationId, messages);
    }

    private SessionMessageVO buildSessionMessage(ConvRow conv) {
        List<Message> originalMessages = loadOriginalMessages(conv.conversationId(), conv.sessionId());
        String answer = extractAnswer(originalMessages);
        List<SubSessionRow> subSessions = loadSubSessions(conv.sessionId());
        String timeline = buildTimeline(conv.conversationId(), originalMessages,
                loadThinkDurations(conv.conversationId(), conv.sessionId()), subSessions);
        return new SessionMessageVO(conv.id(), conv.question(), answer, timeline,
                conv.createdAt(), conv.elapsedMs(), loadAttachments(conv.sessionId()));
    }

    private record SubSessionRow(String sessionId, String agentCode, String parentToolCallId,
                                 String status, Long durationMs) {
    }

    /** M-SA5：联查该根提问下的所有子 Agent 会话（按创建时间正序，含耗时与终态） */
    private List<SubSessionRow> loadSubSessions(String rootSessionId) {
        if (rootSessionId == null || rootSessionId.isBlank()) {
            return List.of();
        }
        try {
            return jdbc.query(
                    "SELECT session_id, agent_code, parent_tool_call_id, status, duration_ms FROM agentx_monitor_session " +
                            "WHERE parent_session_id = ? ORDER BY created_at ASC",
                    (rs, i) -> {
                        Object durObj = rs.getObject("duration_ms");
                        Long dur = durObj instanceof Number n ? n.longValue() : null;
                        return new SubSessionRow(
                                rs.getString("session_id"),
                                rs.getString("agent_code"),
                                rs.getString("parent_tool_call_id"),
                                rs.getString("status"),
                                dur);
                    },
                    rootSessionId);
        } catch (Exception e) {
            log.debug("联查子会话跳过（列或表未就绪）: rootSessionId={}, err={}", rootSessionId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 该轮 trace 里的思考耗时（think 文本 → think_duration_ms）。
     * 消息链与 trace 的 reasoning 出自同一 reasoningBuffer，按文本全等对齐；
     * 对齐不上的（存量数据无该列/失败轮）返回映射不含对应项，前端回退为不显示时长。
     */
    private LinkedHashMap<String, Long> loadThinkDurations(String conversationId, String sessionId) {
        LinkedHashMap<String, Long> thinkDurations = new LinkedHashMap<>();
        try {
            jdbc.query(
                    "SELECT think, think_duration_ms FROM agentx_trace " +
                            "WHERE conversation_id = ? AND session_id = ? " +
                            "AND think IS NOT NULL AND length(think) > 0 AND think_duration_ms IS NOT NULL " +
                            "ORDER BY round ASC",
                    rs -> {
                        thinkDurations.putIfAbsent(rs.getString(1), rs.getLong(2));
                    },
                    conversationId, sessionId);
        } catch (Exception e) {
            log.warn("思考耗时加载失败（回放不带时长）: convId={}, sessionId={}", conversationId, sessionId, e);
        }
        return thinkDurations;
    }

    /** 该轮附件元数据（M5 双阶段关联的 session_id 反查；轮次无文件返回空列表） */
    private List<AttachmentInfo> loadAttachments(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        try {
            long sid = Long.parseLong(sessionId);
            return fileManageService.listBySessionId(sid).stream()
                    .map(f -> new AttachmentInfo(f.getFileId(), f.getFileName(), f.getFileType(), f.getFileSize()))
                    .toList();
        } catch (NumberFormatException e) {
            return List.of();
        }
    }

    /** 加载某一轮的 original_messages（按 session_id 定位该轮追加的切片，item_index 正序） */
    private List<Message> loadOriginalMessages(String conversationId, String sessionId) {
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT state_data FROM agentx_session " +
                            "WHERE conversation_id = ? AND session_id = ? AND state_key = 'original_messages' " +
                            "ORDER BY item_index ASC", String.class, conversationId, sessionId);
            List<Message> all = new ArrayList<>();
            for (String row : rows) {
                if (row != null && !row.isBlank()) {
                    all.addAll(serializer.fromJsonArray(row));
                }
            }
            all.removeIf(m -> m instanceof org.springframework.ai.chat.messages.SystemMessage);
            return all;
        } catch (Exception e) {
            log.warn("会话详情消息加载失败: convId={}, sessionId={}", conversationId, sessionId, e);
            return List.of();
        }
    }

    /**
     * 从消息链提取最终回答（口径 = 内核公共工具 AnswerExtractor，与监控中心终答同源——
     * 监控中心自有表方案 §4.3：换口径只改一处）。
     */
    private String extractAnswer(List<Message> messages) {
        return com.llmetor.miniagentx.utils.AnswerExtractor.extract(messages);
    }

    /**
     * 从消息链重构前端时间线 JSON（M-SA5：内嵌子 Agent 的 subSteps 并归并双层 TodoWrite 计划）。
     */
    private String buildTimeline(String rootConvId, List<Message> messages,
                                 Map<String, Long> thinkDurations, List<SubSessionRow> subSessions) {
        List<Map<String, Object>> events = buildRawEvents(messages, thinkDurations, null);

        // M-SA5：按 parent_tool_call_id 将子会话步骤嵌套回父工具节点，并归并子 TodoWrite
        if (subSessions != null && !subSessions.isEmpty()) {
            Map<String, List<Map<String, Object>>> subStepsByCallId = new LinkedHashMap<>();
            Map<String, SubSessionRow> subMetaByCallId = new LinkedHashMap<>();
            List<Map<String, Object>> subTodoSnapshots = new ArrayList<>();
            for (SubSessionRow sub : subSessions) {
                if (sub.agentCode() == null || sub.agentCode().isBlank()) {
                    continue;
                }
                if (sub.parentToolCallId() != null && !sub.parentToolCallId().isBlank()) {
                    subMetaByCallId.put(sub.parentToolCallId(), sub);
                }
                String subConvId = rootConvId + "::" + sub.agentCode();
                List<Message> subMsgs = loadOriginalMessages(subConvId, sub.sessionId());
                if (subMsgs.isEmpty()) {
                    continue;
                }
                Map<String, Long> subThinkDurations = loadThinkDurations(subConvId, sub.sessionId());
                List<Map<String, Object>> subEvents = buildRawEvents(subMsgs, subThinkDurations, sub.agentCode());
                List<Map<String, Object>> steps = new ArrayList<>();
                for (Map<String, Object> se : subEvents) {
                    if ("todo".equals(se.get("type"))) {
                        Map<String, Object> snap = new LinkedHashMap<>(se);
                        snap.put("agentCode", sub.agentCode());
                        subTodoSnapshots.add(snap);
                    } else if ("tool".equals(se.get("type"))
                            || "thinking".equals(se.get("type"))
                            || "text".equals(se.get("type"))) {
                        if ("text".equals(se.get("type")) && !se.containsKey("agentCode")) {
                            se.put("agentCode", sub.agentCode());
                        }
                        steps.add(se);
                    }
                }
                if (sub.parentToolCallId() != null && !sub.parentToolCallId().isBlank() && !steps.isEmpty()) {
                    subStepsByCallId.computeIfAbsent(sub.parentToolCallId(), k -> new ArrayList<>()).addAll(steps);
                }
            }
            // 1. 回填 subSteps 及子会话元数据（agentCode / durationMs / 终态）到父工具节点
            for (Map<String, Object> ev : events) {
                if ("tool".equals(ev.get("type"))) {
                    String callId = String.valueOf(ev.getOrDefault("toolCallId", ""));
                    List<Map<String, Object>> nested = subStepsByCallId.get(callId);
                    if (nested != null && !nested.isEmpty()) {
                        ev.put("subSteps", nested);
                    }
                    SubSessionRow meta = subMetaByCallId.get(callId);
                    if (meta != null) {
                        ev.put("agentCode", meta.agentCode());
                        if (meta.durationMs() != null) {
                            ev.put("durationMs", meta.durationMs());
                        }
                        if ("interrupted".equalsIgnoreCase(meta.status())) {
                            ev.put("status", "interrupted");
                        } else if ("error".equalsIgnoreCase(meta.status())) {
                            ev.put("status", "failed");
                        }
                    }
                }
            }
            // 2. 双层 TodoWrite 归并：有主计划则挂 subItems，无主计划则直接提升为顶层 todo
            if (!subTodoSnapshots.isEmpty()) {
                Map<String, Object> lastSubTodo = subTodoSnapshots.get(subTodoSnapshots.size() - 1);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> subItems = (List<Map<String, Object>>) lastSubTodo.get("items");
                Map<String, Object> lastRootTodo = null;
                for (int i = events.size() - 1; i >= 0; i--) {
                    if ("todo".equals(events.get(i).get("type"))) {
                        lastRootTodo = events.get(i);
                        break;
                    }
                }
                if (lastRootTodo != null && subItems != null && !subItems.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> rootItems = (List<Map<String, Object>>) lastRootTodo.get("items");
                    if (rootItems != null && !rootItems.isEmpty()) {
                        Map<String, Object> target = rootItems.stream()
                                .filter(it -> "in_progress".equals(it.get("status")))
                                .findFirst()
                                .orElse(rootItems.get(rootItems.size() - 1));
                        target.put("subItems", subItems);
                    }
                } else if (lastRootTodo == null && subItems != null && !subItems.isEmpty()) {
                    Map<String, Object> lifted = new LinkedHashMap<>();
                    lifted.put("type", "todo");
                    lifted.put("agentCode", lastSubTodo.get("agentCode"));
                    lifted.put("items", subItems);
                    events.add(lifted);
                }
            }
        }

        try {
            return objectMapper.writeValueAsString(events);
        } catch (Exception e) {
            log.warn("时间线序列化失败: {}", e.getMessage());
            return "[]";
        }
    }

    private List<Map<String, Object>> buildRawEvents(List<Message> messages, Map<String, Long> thinkDurations,
                                                     String agentCode) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> events = new ArrayList<>();
        Set<String> seenToolCallIds = new LinkedHashSet<>();

        for (Message msg : messages) {
            if (msg instanceof AssistantMessage am) {
                // 1. 思考过程（带 trace 对齐出的真实耗时，无则省略字段）
                String reasoning = extractReasoning(am);
                if (reasoning != null && !reasoning.isBlank()) {
                    Map<String, Object> thinkEvent = new LinkedHashMap<>();
                    thinkEvent.put("type", "thinking");
                    thinkEvent.put("content", reasoning);
                    if (agentCode != null) {
                        thinkEvent.put("agentCode", agentCode);
                    }
                    Long thinkMs = thinkDurations.get(reasoning);
                    if (thinkMs != null) {
                        thinkEvent.put("durationMs", thinkMs);
                    }
                    events.add(thinkEvent);
                }
                // 2. 正文回答
                String text = am.getText();
                if (text != null && !text.isBlank()) {
                    events.add(new LinkedHashMap<>(Map.of("type", "text", "content", text)));
                }
                // 3. 工具调用（TodoWrite → todo 执行计划面板，其余 → 普通 tool 卡片）
                List<AssistantMessage.ToolCall> toolCalls = am.getToolCalls();
                if (toolCalls != null) {
                    for (AssistantMessage.ToolCall tc : toolCalls) {
                        if ("TodoWrite".equalsIgnoreCase(tc.name())) {
                            List<Map<String, Object>> items = parseTodoItems(tc.arguments());
                            if (!items.isEmpty()) {
                                Map<String, Object> todoEv = new LinkedHashMap<>();
                                todoEv.put("type", "todo");
                                todoEv.put("items", items);
                                events.add(todoEv);
                            }
                            continue;
                        }
                        seenToolCallIds.add(tc.id());
                        Map<String, Object> toolEvent = new LinkedHashMap<>();
                        toolEvent.put("type", "tool");
                        toolEvent.put("toolName", tc.name());
                        toolEvent.put("toolCallId", tc.id());
                        toolEvent.put("arguments", tc.arguments());
                        toolEvent.put("status", "pending");
                        if (agentCode != null) {
                            toolEvent.put("agentCode", agentCode);
                        }
                        events.add(toolEvent);
                    }
                }
            } else if (msg instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                    if (seenToolCallIds.contains(tr.id())) {
                        for (int i = events.size() - 1; i >= 0; i--) {
                            Map<String, Object> event = events.get(i);
                            if ("tool".equals(event.get("type"))
                                    && tr.id().equals(event.get("toolCallId"))
                                    && "pending".equals(event.get("status"))) {
                                event.put("result", tr.responseData());
                                event.put("status", "completed");
                                break;
                            }
                        }
                    }
                }
            }
        }
        return events;
    }

    /**
     * TodoWrite 参数中的 todos 转前端 todo 面板 items（参数侧信道解析，不落库）。
     * 参数格式：{"todos": [{"content": "...", "status": "pending", "activeForm": "..."}]}
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseTodoItems(String arguments) {
        try {
            Map<String, Object> args = objectMapper.readValue(arguments == null ? "{}" : arguments, Map.class);
            List<Map<String, Object>> todos = (List<Map<String, Object>>) args.get("todos");
            if (todos == null) {
                return List.of();
            }
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> todo : todos) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("text", todo.getOrDefault("content", ""));
                item.put("status", todo.getOrDefault("status", "pending"));
                items.add(item);
            }
            return items;
        } catch (Exception e) {
            log.warn("TodoWrite 参数解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 从 AssistantMessage metadata 提取思考内容（兼容两种 key） */
    private static String extractReasoning(AssistantMessage am) {
        Map<String, Object> metadata = am.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object rc = metadata.get("reasoningContent");
        if (rc == null) {
            rc = metadata.get("reasoning_content");
        }
        return rc instanceof String s ? s : null;
    }

    // ==================== 删除 ====================

    /**
     * 删除会话（仅当前用户自己的）：conversation 行 + session 消息行级联 +
     * 中断快照清理（防删除后僵尸恢复）。返回是否确有删除。
     */
    public boolean deleteConversation(String conversationId) {
        String currentUserId = currentUserIdOrNull();
        if (currentUserId == null) {
            return false;
        }
        int deleted = jdbc.update(
                "DELETE FROM agentx_conversation WHERE conversation_id = ? AND user_id = ?",
                conversationId, currentUserId);
        if (deleted == 0) {
            return false;
        }
        jdbc.update("DELETE FROM agentx_session WHERE conversation_id = ? OR conversation_id LIKE ?",
                conversationId, conversationId + "::%");
        try {
            pauseStateStore.delete(conversationId);
            pauseStateStore.deleteByPrefix(conversationId + "::");
        } catch (Exception e) {
            log.warn("删除会话时清理中断快照失败: convId={}", conversationId, e);
        }
        log.info("[session] 删除会话: userId={}, conversationId={}, 调用数={}",
                currentUserId, conversationId, deleted);
        return true;
    }

    private String currentUserIdOrNull() {
        Long uid = authService.getCurrentUserIdOrNull();
        return uid == null ? null : uid.toString();
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > TITLE_MAX_LENGTH
                ? text.substring(0, TITLE_MAX_LENGTH) + "..."
                : text;
    }
}
