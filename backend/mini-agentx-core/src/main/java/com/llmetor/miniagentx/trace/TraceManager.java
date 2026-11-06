package com.llmetor.miniagentx.trace;

import com.llmetor.miniagentx.memory.MessageJsonSerializer;
import com.llmetor.miniagentx.utils.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.util.List;

/**
 * trace 审计：每轮 LLM 调用写一行 agentx_trace。
 *
 * <p>input_data = 完整请求消息 JSON；工具轮 output_data = {"tool_calls":[...]}，
 * 文本轮 = 最终文本；失败轮 success=0 + error_message。写失败仅 warn 不影响主流程
 * （这份数据是 M8 评测/诊断体系的根基）。
 */
public class TraceManager {

    private static final Logger log = LoggerFactory.getLogger(TraceManager.class);

    private final JdbcTemplate jdbc;
    private final MessageJsonSerializer serializer = new MessageJsonSerializer();

    public TraceManager(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        createTable();
    }

    public void createTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agentx_trace (
                  id bigint NOT NULL,
                  session_id varchar(100) NOT NULL,
                  conversation_id varchar(100) NOT NULL,
                  round int NOT NULL,
                  input_data text NULL,
                  output_data text NULL,
                  think text NULL,
                  prompt_tokens bigint NULL DEFAULT 0,
                  completion_tokens bigint NULL DEFAULT 0,
                  duration_ms bigint NULL DEFAULT 0,
                  success int NULL DEFAULT 1,
                  error_message text NULL,
                  created_at timestamp NULL DEFAULT CURRENT_TIMESTAMP,
                  PRIMARY KEY (id)
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_agentx_trace_session ON agentx_trace (session_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_agentx_trace_conv ON agentx_trace (conversation_id)");
    }

    /**
     * 记一轮。sessionId 用字符串（表列 varchar）。
     */
    public void trace(String sessionId, String conversationId, int round,
                      List<Message> inputMessages, String outputData, String think,
                      long promptTokens, long completionTokens, long durationMs,
                      boolean success, String errorMessage) {
        try {
            String inputJson = serializer.toJsonArray(inputMessages);
            jdbc.update("INSERT INTO agentx_trace (id, session_id, conversation_id, round, input_data, " +
                            "output_data, think, prompt_tokens, completion_tokens, duration_ms, success, error_message) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    SnowflakeIdGenerator.nextId(), sessionId, conversationId, round, inputJson,
                    outputData, think, promptTokens, completionTokens, durationMs,
                    success ? 1 : 0, errorMessage);
        } catch (Exception e) {
            log.warn("trace 写入失败（不影响主流程）: convId={}, round={}", conversationId, round, e);
        }
    }
}
