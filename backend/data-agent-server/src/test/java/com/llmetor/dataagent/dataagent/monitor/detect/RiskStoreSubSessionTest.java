package com.llmetor.dataagent.dataagent.monitor.detect;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-TT 风险查询穿透子会话单测（ing-02 方案 §6 T2.1 / §3.3）：H2 真实跑 SQL。
 *
 * <p>场景：U8 根会话风险 + 子会话风险一次返回，RiskItem.sessionId/agentCode 正确标注，
 * severity 仍是第一排序键（同级别内根会话排前），无关会话风险不串入。
 */
class RiskStoreSubSessionTest {

    private static final String CONV = "risk_conv_1";
    private static final String ROOT = "risk_root_1";
    private static final String SUB1 = "risk_sub_1";
    private static final String OTHER = "risk_other_root";

    private JdbcTemplate jdbc;
    private RiskStore store;

    @BeforeEach
    void setUp() {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:risksub" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        // 先建测试表（evidence 用 CLOB 替代 jsonb），RiskStore.ensureSchema 的 IF NOT EXISTS 即为 no-op
        jdbc.execute("""
                CREATE TABLE agentx_monitor_risk (
                  id bigint PRIMARY KEY, conversation_id varchar(100), session_id varchar(100),
                  rule_id varchar(20), severity varchar(10), round int, trace_id bigint,
                  rounds_hit varchar(100), title varchar(200), detail CLOB, evidence CLOB,
                  created_at timestamp DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE agentx_monitor_session (
                  id bigint PRIMARY KEY, conversation_id varchar(100), session_id varchar(100),
                  seq int, user_id varchar(100), question CLOB, status varchar(20),
                  agent_code varchar(50) DEFAULT 'router',
                  parent_session_id varchar(100) NULL,
                  parent_tool_call_id varchar(100) NULL,
                  created_at timestamp DEFAULT CURRENT_TIMESTAMP)
                """);
        store = new RiskStore(ds);
    }

    private void insertSession(String sessionId, String agentCode, String parentSessionId) {
        jdbc.update("""
                INSERT INTO agentx_monitor_session
                  (id, conversation_id, session_id, seq, user_id, question, status, agent_code, parent_session_id)
                VALUES (?, ?, ?, 1, '20', 'q', 'completed', ?, ?)
                """, sessionId.hashCode(), CONV, sessionId, agentCode, parentSessionId);
    }

    private void insertRisk(long id, String sessionId, String ruleId, String severity,
                            Integer round, String roundsHit) {
        jdbc.update("""
                INSERT INTO agentx_monitor_risk
                  (id, conversation_id, session_id, rule_id, severity, round, trace_id, rounds_hit, title, detail, evidence)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, CONV, sessionId, ruleId, severity, round, (long) id, roundsHit,
                "标题" + id, "明细" + id, "{\"k\":\"v\"}");
    }

    @Test
    void listBySession_根会话与子会话风险一次返回并标注归属() {
        insertSession(ROOT, "router", null);
        insertSession(SUB1, "data-analysis", ROOT);
        insertSession(OTHER, "router", null);

        // 根会话 medium（R5）+ 子会话 high（Sub-R2，跨轮 2,3）+ 无关会话 medium（不得串入）
        insertRisk(1L, ROOT, "TOOL-001", "medium", 5, null);
        insertRisk(2L, SUB1, "REACT-002", "high", 2, "2,3");
        insertRisk(3L, OTHER, "TOOL-001", "high", 1, null);

        var items = store.listBySession(ROOT);
        // 无关会话风险不串入
        assertThat(items).hasSize(2);
        // severity 仍是第一排序键：子会话 high 排在根会话 medium 前（方案 §4.4 示意口径）
        var first = items.get(0);
        assertThat(first.ruleId()).isEqualTo("REACT-002");
        assertThat(first.severity()).isEqualTo("high");
        assertThat(first.sessionId()).isEqualTo(SUB1);
        assertThat(first.agentCode()).isEqualTo("data-analysis");
        assertThat(first.round()).isEqualTo(2);
        assertThat(first.roundsHit()).containsExactly(2, 3);
        // 根会话风险：sessionId = 根、agentCode = router（前端展示「主链路」不打子Agent徽标）
        var second = items.get(1);
        assertThat(second.sessionId()).isEqualTo(ROOT);
        assertThat(second.agentCode()).isEqualTo("router");
        assertThat(second.round()).isEqualTo(5);
    }

    @Test
    void listBySession_无子会话时行为与现状一致() {
        insertSession(ROOT, "router", null);
        insertRisk(1L, ROOT, "TOOL-001", "high", 3, null);
        var items = store.listBySession(ROOT);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).sessionId()).isEqualTo(ROOT);
        // 不存在的会话 → 空列表
        assertThat(store.listBySession("no_such")).isEmpty();
    }
}
