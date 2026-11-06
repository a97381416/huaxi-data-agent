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
                           String question, String createdAt) {
    }

    private List<ConvRow> loadRows(String userId, String conversationId, boolean desc) {
        String order = desc ? "DESC" : "ASC";
        String sql = "SELECT id, conversation_id, session_id, question, created_at " +
                "FROM agentx_conversation WHERE user_id = ?" +
                (conversationId != null ? " AND conversation_id = ?" : "") +
                " ORDER BY created_at " + order + ", id " + order;
        Object[] args = conversationId != null
                ? new Object[]{userId, conversationId}
                : new Object[]{userId};
        return jdbc.query(sql, (rs, i) -> new ConvRow(
                rs.getLong("id"),
                rs.getString("conversation_id"),
                rs.getString("session_id"),
                rs.getString("question"),
                formatTs(rs.getTimestamp("created_at"))), args);
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
        String timeline = buildTimeline(originalMessages);
        return new SessionMessageVO(conv.id(), conv.question(), answer, timeline,
                conv.createdAt(), loadAttachments(conv.sessionId()));
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
     * 从消息链提取最终回答：倒序找第一个实质性 AssistantMessage（≥100 字符或含
     * markdown 结构 #/|/http），跳过"好的/明白了"类收尾短回复；找不到则回退最后一个非空。
     */
    private String extractAnswer(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        String lastNonBlank = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg instanceof AssistantMessage am) {
                String text = am.getText();
                if (text != null && !text.isBlank()) {
                    if (lastNonBlank.isEmpty()) {
                        lastNonBlank = text;
                    }
                    if (isSubstantial(text)) {
                        return text;
                    }
                }
            }
        }
        return lastNonBlank;
    }

    /** 实质性报告判定：够长，或带 markdown 结构（标题/表格/链接） */
    private static boolean isSubstantial(String text) {
        if (text.length() >= 100) {
            return true;
        }
        return text.contains("#") || text.contains("|") || text.contains("http");
    }

    /**
     * 从消息链重构前端时间线 JSON（对齐原版 buildTimeline）。
     * AssistantMessage 拆为三阶段：思考(reasoningContent) → 正文(content) → 工具调用；
     * TodoWrite 不进工具卡片，解析参数里的 todos 为 todo 面板事件（参数侧信道）；
     * ToolResponseMessage 按 toolCallId 回填到对应的 pending 工具事件上。
     */
    private String buildTimeline(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> events = new ArrayList<>();
        Set<String> seenToolCallIds = new LinkedHashSet<>();

        for (Message msg : messages) {
            if (msg instanceof AssistantMessage am) {
                // 1. 思考过程
                String reasoning = extractReasoning(am);
                if (reasoning != null && !reasoning.isBlank()) {
                    events.add(Map.of("type", "thinking", "content", reasoning));
                }
                // 2. 正文回答
                String text = am.getText();
                if (text != null && !text.isBlank()) {
                    events.add(Map.of("type", "text", "content", text));
                }
                // 3. 工具调用（TodoWrite → todo 执行计划面板，其余 → 普通 tool 卡片）
                List<AssistantMessage.ToolCall> toolCalls = am.getToolCalls();
                if (toolCalls != null) {
                    for (AssistantMessage.ToolCall tc : toolCalls) {
                        if ("TodoWrite".equalsIgnoreCase(tc.name())) {
                            List<Map<String, Object>> items = parseTodoItems(tc.arguments());
                            if (!items.isEmpty()) {
                                events.add(Map.of("type", "todo", "items", items));
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

        try {
            return objectMapper.writeValueAsString(events);
        } catch (Exception e) {
            log.warn("时间线序列化失败: {}", e.getMessage());
            return "[]";
        }
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
        jdbc.update("DELETE FROM agentx_session WHERE conversation_id = ?", conversationId);
        try {
            pauseStateStore.delete(conversationId);
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
