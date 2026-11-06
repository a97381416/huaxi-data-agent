package com.llmetor.miniagentx.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmetor.miniagentx.context.OffloadStore;
import com.llmetor.miniagentx.utils.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话三表持久化（agentx_conversation / agentx_session）。
 *
 * <p>核心设计（对齐原框架 SessionPersister）：
 * <ul>
 *   <li>ReAct 循环期间零 DB 写入，只在终态一次性写</li>
 *   <li>original_messages 视图只增（原始链不被压缩污染，M1 无压缩二者一致，结构留好）</li>
 *   <li>working_messages 全删全插 replace（当前工作视图）</li>
 *   <li>system 消息不落库（每次动态重建）</li>
 *   <li>幂等由调用方（执行器）的 tryMarkPersisted CAS 保证</li>
 *   <li>M7：实现 OffloadStore——压缩 offload 原文按 uuid 存 offload_context 状态键（无独立表）</li>
 * </ul>
 */
public class AgentxSessionStore implements OffloadStore {

    private static final Logger log = LoggerFactory.getLogger(AgentxSessionStore.class);

    public static final String STATE_ORIGINAL = "original_messages";
    public static final String STATE_WORKING = "working_messages";
    public static final String STATE_OFFLOAD = "offload_context";

    private final JdbcTemplate jdbc;
    private final MessageJsonSerializer serializer = new MessageJsonSerializer();
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentxSessionStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        createTables();
    }

    /** 幂等建表（PostgreSQL 方言，M-H1 改造；已存在的表不动） */
    public void createTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agentx_conversation (
                  id bigint NOT NULL,
                  conversation_id varchar(100) NOT NULL,
                  session_id varchar(100) NOT NULL,
                  user_id varchar(100) NULL,
                  question text NOT NULL,
                  status varchar(20) NULL DEFAULT 'running',
                  created_at timestamp NULL DEFAULT CURRENT_TIMESTAMP,
                  completed_at timestamp NULL DEFAULT NULL,
                  PRIMARY KEY (id),
                  CONSTRAINT uk_conv_session UNIQUE (session_id)
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_conv_id ON agentx_conversation (conversation_id)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agentx_session (
                  id bigint NOT NULL,
                  conversation_id varchar(100) NOT NULL,
                  session_id varchar(100) NOT NULL,
                  state_key varchar(255) NOT NULL,
                  item_index int NOT NULL DEFAULT 0,
                  state_data text NOT NULL,
                  created_at timestamp NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at timestamp NULL DEFAULT CURRENT_TIMESTAMP,
                  PRIMARY KEY (id)
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_session_state ON agentx_session (session_id, state_key)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_conv_state ON agentx_session (conversation_id, state_key)");
    }

    /** 会话开局行（status=running） */
    public void initConversation(long id, String conversationId, String sessionId, String userId, String question) {
        jdbc.update("INSERT INTO agentx_conversation (id, conversation_id, session_id, user_id, question, status) " +
                        "VALUES (?, ?, ?, ?, ?, 'running')",
                id, conversationId, sessionId, userId, question);
    }

    /**
     * 加载历史消息：优先 working_messages，空则回退 original_messages；
     * 过滤 SystemMessage（system 每次动态重建）。
     */
    public List<Message> loadHistory(String conversationId) {
        List<Message> working = loadState(conversationId, STATE_WORKING);
        if (!working.isEmpty()) {
            return working;
        }
        return loadState(conversationId, STATE_ORIGINAL);
    }

    private List<Message> loadState(String conversationId, String stateKey) {
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT state_data FROM agentx_session WHERE conversation_id = ? AND state_key = ? " +
                            "ORDER BY item_index ASC", String.class, conversationId, stateKey);
            if (rows.isEmpty()) {
                return List.of();
            }
            List<Message> messages = new ArrayList<>(rows.size());
            for (String row : rows) {
                messages.addAll(serializer.fromJsonArray(row));
            }
            messages.removeIf(m -> m instanceof SystemMessage);
            return messages;
        } catch (Exception e) {
            log.warn("历史消息加载失败，按空历史处理: convId={}", conversationId, e);
            return List.of();
        }
    }

    /**
     * 终态落库（一次）：
     * original 追加新增切片（从 newStart 起，含本轮 user 消息）；
     * working 全删全插当前链（除 system）；
     * conversation 更新状态。
     */
    public synchronized void persistTerminal(String conversationId, String sessionId, String status,
                                             List<Message> originalChain, List<Message> workingChain,
                                             int originalAppendFrom) {
        // ① original 视图：追加新增切片。【增量添加】
        int startIndex = Math.max(0, originalAppendFrom);
        List<Message> noSystemOriginal = originalChain.stream()
                .filter(m -> !(m instanceof SystemMessage)).toList();
        // originalAppendFrom 基于无 system 视图的下标（loadHistory/构建时已保证）
        if (startIndex < noSystemOriginal.size()) {
            List<Message> slice = noSystemOriginal.subList(startIndex, noSystemOriginal.size());
            appendState(conversationId, sessionId, STATE_ORIGINAL, slice);
        }

        // ② working 视图：全删全插
        jdbc.update("DELETE FROM agentx_session WHERE conversation_id = ? AND state_key = ?",conversationId, STATE_WORKING);
        List<Message> noSystemWorking = workingChain.stream().filter(m -> !(m instanceof SystemMessage)).toList();
        if (!noSystemWorking.isEmpty()) {
            appendState(conversationId, sessionId, STATE_WORKING, noSystemWorking);
        }

        // ③ conversation 三种终态：【completed、error、interrupted】
        if ("completed".equals(status)) {
            jdbc.update("UPDATE agentx_conversation SET status = ?, completed_at = NOW() " +
                    "WHERE conversation_id = ?", status, conversationId);
        } else {
            jdbc.update("UPDATE agentx_conversation SET status = ? WHERE conversation_id = ?",
                    status, conversationId);
        }
    }

    private void appendState(String conversationId, String sessionId, String stateKey, List<Message> slice) {
        Integer maxIndex = jdbc.queryForObject(
                "SELECT COALESCE(MAX(item_index), -1) FROM agentx_session " +
                        "WHERE conversation_id = ? AND state_key = ?", Integer.class, conversationId, stateKey);
        int next = (maxIndex == null ? -1 : maxIndex) + 1;
        String json = serializer.toJsonArray(slice);
        jdbc.update("INSERT INTO agentx_session (id, conversation_id, session_id, state_key, item_index, state_data) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                SnowflakeIdGenerator.nextId(), conversationId, sessionId, stateKey, next, json);
    }

    // ==================== M7：offload_context（压缩原文归档） ====================

    /**
     * offload 原文归档：一行一条记录，state_data =
     * {@code {"uuid":"...","message":[消息JSON数组]}}（对齐 docs/02 §4.3 结构）。
     * 同 uuid 重复保存先删后插（覆盖语义）。
     */
    @Override
    public synchronized void save(String conversationId, String uuid, List<Message> messages) {
        try {
            jdbc.update("DELETE FROM agentx_session WHERE conversation_id = ? AND state_key = ? " +
                    "AND state_data LIKE ?", conversationId, STATE_OFFLOAD, "%\"" + uuid + "\"%");
            ObjectNode node = mapper.createObjectNode();
            node.put("uuid", uuid);
            node.set("message", mapper.readTree(serializer.toJsonArray(messages)));
            Integer maxIndex = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(item_index), -1) FROM agentx_session " +
                            "WHERE conversation_id = ? AND state_key = ?", Integer.class, conversationId, STATE_OFFLOAD);
            jdbc.update("INSERT INTO agentx_session (id, conversation_id, session_id, state_key, item_index, state_data) " +
                            "VALUES (?, ?, ?, ?, ?, ?)",
                    SnowflakeIdGenerator.nextId(), conversationId, sessionIdOf(conversationId),
                    STATE_OFFLOAD, (maxIndex == null ? -1 : maxIndex) + 1, node.toString());
        } catch (Exception e) {
            // 归档失败只影响"取回原文"，不影响主流程（替换文本已带预览）
            log.warn("offload 归档失败（context_reload 将取不到该段）: convId={}, uuid={}", conversationId, uuid, e);
        }
    }

    @Override
    public List<Message> load(String conversationId, String uuid) {
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT state_data FROM agentx_session WHERE conversation_id = ? AND state_key = ? " +
                            "ORDER BY item_index ASC", String.class, conversationId, STATE_OFFLOAD);
            for (String row : rows) {
                JsonNode node = mapper.readTree(row);
                if (uuid.equals(node.path("uuid").asText(null))) {
                    JsonNode messageArr = node.get("message");
                    return messageArr == null ? null
                            : serializer.fromJsonArray(messageArr.toString());
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("offload 取回失败: convId={}, uuid={}", conversationId, uuid, e);
            return null;
        }
    }

    /** offload 行无明确归属 session（跨轮共享），取该会话最近一条 session_id 兜底 */
    private String sessionIdOf(String conversationId) {
        try {
            return jdbc.queryForObject(
                    "SELECT session_id FROM agentx_conversation WHERE conversation_id = ? " +
                            "ORDER BY created_at DESC LIMIT 1", String.class, conversationId);
        } catch (Exception e) {
            return SnowflakeIdGenerator.nextIdStr();
        }
    }
}
