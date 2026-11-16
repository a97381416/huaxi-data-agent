package com.llmetor.dataagent.dataagent.action;

import com.llmetor.miniagentx.utils.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 写通道结构化动作日志（M-E0）：谁、何时、用哪个工具、对哪个业务对象、
 * 是否经确认令牌、结果如何。写操作「审计双写」之一（另一路：agentx_trace 天然记录
 * 工具轮 + 主后端 REST 侧操作日志）。
 *
 * <p>与 agentx_trace 的关联：同记 conversation_id——动作日志定位到会话后，
 * trace 侧按 conversation_id + 时间邻近即可回放对应 ReAct 轮。
 *
 * <p>建表 CREATE IF NOT EXISTS（对齐 TraceManager 模式）；写失败仅 warn 不影响
 * 业务主流程（审计尽力而为，业务结果以 REST 返回为准）。
 */
@Slf4j
@Component
public class ActionAuditLogger {

    /** 一条动作审计记录（builder 风格拼装，字段对齐表列） */
    public record Entry(String conversationId, Long userId, String tool, String action,
                        String businessObject, String confirmToken, boolean success,
                        String errorMessage, String detail) {
    }

    public static Builder entry() {
        return new Builder();
    }

    private final JdbcTemplate jdbc;

    public ActionAuditLogger(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        createTable();
    }

    private void createTable() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS agentx_action_log (
                      id bigint NOT NULL,
                      conversation_id varchar(100) NULL,
                      user_id bigint NULL,
                      tool varchar(100) NOT NULL,
                      action varchar(100) NULL,
                      business_object varchar(500) NULL,
                      confirm_token varchar(64) NULL,
                      success int NULL DEFAULT 1,
                      error_message text NULL,
                      detail text NULL,
                      created_at timestamp NULL DEFAULT CURRENT_TIMESTAMP,
                      PRIMARY KEY (id)
                    )
                    """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_agentx_action_log_conv ON agentx_action_log (conversation_id)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_agentx_action_log_user ON agentx_action_log (user_id)");
        } catch (Exception e) {
            // 建表失败不阻断启动（如只读账号）：审计写不进去会逐条 warn
            log.warn("[action] agentx_action_log 建表失败（审计将降级为日志输出）: {}", e.getMessage());
        }
    }

    /** 记一条动作日志；失败仅 warn（对齐 TraceManager 的尽力而为口径） */
    public void log(Entry entry) {
        if (entry == null) {
            return;
        }
        try {
            jdbc.update("INSERT INTO agentx_action_log "
                            + "(id, conversation_id, user_id, tool, action, business_object, "
                            + "confirm_token, success, error_message, detail) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    SnowflakeIdGenerator.nextId(),
                    entry.conversationId(), entry.userId(), entry.tool(), entry.action(),
                    truncate(entry.businessObject(), 500), entry.confirmToken(),
                    entry.success() ? 1 : 0, entry.errorMessage(), entry.detail());
        } catch (Exception e) {
            log.warn("[action] 动作日志写入失败（不影响主流程）: tool={}, userId={}, err={}",
                    entry.tool(), entry.userId(), e.getMessage());
        }
        // 结构化日志双轨：即便落库失败，日志里也留痕
        log.info("[action-audit] convId={} userId={} tool={} action={} bizObj={} confirmToken={} success={} err={}",
                entry.conversationId(), entry.userId(), entry.tool(), entry.action(),
                entry.businessObject(), entry.confirmToken(), entry.success(), entry.errorMessage());
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Entry 构建器 */
    public static class Builder {
        private String conversationId;
        private Long userId;
        private String tool;
        private String action;
        private String businessObject;
        private String confirmToken;
        private boolean success = true;
        private String errorMessage;
        private String detail;

        public Builder conversationId(String v) { this.conversationId = v; return this; }
        public Builder userId(Long v) { this.userId = v; return this; }
        public Builder tool(String v) { this.tool = v; return this; }
        public Builder action(String v) { this.action = v; return this; }
        public Builder businessObject(String v) { this.businessObject = v; return this; }
        public Builder confirmToken(String v) { this.confirmToken = v; return this; }
        public Builder success(boolean v) { this.success = v; return this; }
        public Builder errorMessage(String v) { this.errorMessage = v; return this; }
        public Builder detail(String v) { this.detail = v; return this; }

        public Entry build() {
            if (tool == null || tool.isBlank()) {
                throw new IllegalArgumentException("审计条目必须携带 tool");
            }
            return new Entry(conversationId, userId, tool, action, businessObject,
                    confirmToken, success, errorMessage, detail);
        }
    }
}
