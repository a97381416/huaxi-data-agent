package com.llmetor.dataagent.dataagent.action;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动作审计单测（M-E0）：H2 验证 agentx_action_log 自动建表 + 落库字段完整
 * （含 confirmToken 关联与失败留痕）。
 */
class ActionAuditLoggerTest {

    private JdbcTemplate jdbc;
    private ActionAuditLogger logger;

    @BeforeEach
    void setUp() {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:actionlog" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(ds);
        logger = new ActionAuditLogger(ds); // 构造即建表
    }

    @Test
    void createsTableAndInsertsEntry() {
        logger.log(ActionAuditLogger.entry()
                .conversationId("conv_001")
                .userId(1001L)
                .tool("resubmitLedger")
                .action("resubmit")
                .businessObject("ledger:DOC-100001-002")
                .confirmToken("ct_abc123")
                .success(true)
                .detail("patchCount=2")
                .build());

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agentx_action_log WHERE conversation_id = ? AND tool = ?",
                Integer.class, "conv_001", "resubmitLedger");
        assertThat(count).isEqualTo(1);

        String token = jdbc.queryForObject(
                "SELECT confirm_token FROM agentx_action_log WHERE conversation_id = ?",
                String.class, "conv_001");
        assertThat(token).isEqualTo("ct_abc123");
    }

    @Test
    void failureEntryKeepsErrorMessage() {
        logger.log(ActionAuditLogger.entry()
                .conversationId("conv_002")
                .userId(1001L)
                .tool("deleteProject")
                .action("delete")
                .businessObject("project:200003")
                .success(false)
                .errorMessage("403 无权限")
                .build());

        Integer success = jdbc.queryForObject(
                "SELECT success FROM agentx_action_log WHERE tool = 'deleteProject'",
                Integer.class);
        String err = jdbc.queryForObject(
                "SELECT error_message FROM agentx_action_log WHERE tool = 'deleteProject'",
                String.class);
        assertThat(success).isEqualTo(0);
        assertThat(err).isEqualTo("403 无权限");
    }

    @Test
    void longBusinessObjectTruncatedToColumnLimit() {
        logger.log(ActionAuditLogger.entry()
                .userId(1L)
                .tool("t")
                .businessObject("x".repeat(600)) // 超 varchar(500)
                .build());

        String stored = jdbc.queryForObject(
                "SELECT business_object FROM agentx_action_log", String.class);
        assertThat(stored).hasSize(500);
    }
}
