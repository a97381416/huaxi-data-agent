package com.llmetor.dataagent.dataagent.monitor;

import com.llmetor.dataagent.dataagent.eval.trace.TraceQueryService;
import com.llmetor.dataagent.dataagent.monitor.dto.RoundListItem;
import com.llmetor.dataagent.dataagent.monitor.dto.SessionTimelineVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-TT 监控链路跟踪子Agent穿透单测（ing-02 方案 §6 T1.4）：H2（PostgreSQL 模式）真实跑 SQL。
 *
 * <p>场景矩阵：
 * U1 无子会话现状回归（single 模式逐字段一致）/ U2 单子会话挂载委派 callId /
 * U3 同轮双委派 / U4 跨轮委派 / U5 失败子会话 / U6 旧数据孤儿兜底 /
 * U7 子事件 seq 命名空间自洽 / U9 conversationDetail 轮次 agentCode 标注。
 */
class MonitorQueryServiceTimelineTest {

    private static final String CONV = "tt_conv_1";
    private static final String ROOT = "tt_root_1";
    private static final String SUB1 = "tt_sub_1";
    private static final String SUB2 = "tt_sub_2";
    private static final String SUB_ORPH = "tt_sub_orph";
    private static final String SUB_EMPTY = "tt_sub_empty";

    private JdbcTemplate jdbc;
    private MonitorQueryService service;

    @BeforeEach
    void setUp() {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:monitortt" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE agentx_monitor_session (
                  id bigint PRIMARY KEY, conversation_id varchar(100), session_id varchar(100),
                  seq int, user_id varchar(100), question CLOB, status varchar(20),
                  answer CLOB NULL, answer_chars int NULL, answer_trace_id bigint NULL,
                  rounds int NULL, fail_rounds int NULL,
                  prompt_tokens bigint DEFAULT 0, completion_tokens bigint DEFAULT 0,
                  duration_ms bigint NULL, first_error CLOB NULL,
                  agent_code varchar(50) DEFAULT 'router',
                  parent_session_id varchar(100) NULL,
                  parent_tool_call_id varchar(100) NULL,
                  detect_status varchar(20) NULL, risk_total int DEFAULT 0, risk_high int DEFAULT 0,
                  created_at timestamp DEFAULT CURRENT_TIMESTAMP, completed_at timestamp NULL)
                """);
        jdbc.execute("""
                CREATE TABLE agentx_monitor_trace (
                  id bigint PRIMARY KEY, session_id varchar(100), conversation_id varchar(100),
                  round int, duration_ms bigint DEFAULT 0, success int DEFAULT 1, error_message CLOB NULL,
                  created_at timestamp DEFAULT CURRENT_TIMESTAMP,
                  round_type varchar(10) NULL, input_chars int NULL,
                  prompt_tokens bigint DEFAULT 0, completion_tokens bigint DEFAULT 0,
                  think_duration_ms bigint NULL, tool_names varchar array NULL,
                  agent_code varchar(50) DEFAULT 'router',
                  parent_session_id varchar(100) NULL)
                """);
        jdbc.execute("""
                CREATE TABLE agentx_trace_payload (
                  trace_id bigint PRIMARY KEY,
                  input_data CLOB NULL, output_data CLOB NULL, think CLOB NULL,
                  created_at timestamp DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE agentx_monitor_conversation (
                  id bigint PRIMARY KEY, conversation_id varchar(100), user_id varchar(100),
                  title CLOB NULL, question_count int DEFAULT 0, total_rounds int DEFAULT 0,
                  fail_rounds int DEFAULT 0, prompt_tokens bigint DEFAULT 0, completion_tokens bigint DEFAULT 0,
                  status varchar(20) NULL, started_at timestamp NULL, last_active_at timestamp NULL,
                  created_at timestamp DEFAULT CURRENT_TIMESTAMP)
                """);
        service = new MonitorQueryService(ds, new TraceQueryService(ds), 10);
    }

    // ==================== 造数工具 ====================

    /** 会话行 id：模拟生产自增主键（保证 ORDER BY created_at, id 的创建序稳定） */
    private long sessionIdSeq = 1000L;

    private void insertSession(String sessionId, String question, String agentCode,
                               String parentSessionId, String parentToolCallId,
                               int rounds, int failRounds) {
        jdbc.update("""
                INSERT INTO agentx_monitor_session
                  (id, conversation_id, session_id, seq, user_id, question, status,
                   agent_code, parent_session_id, parent_tool_call_id, rounds, fail_rounds)
                VALUES (?, ?, ?, 1, '20', ?, 'completed', ?, ?, ?, ?, ?)
                """,
                sessionIdSeq++, CONV, sessionId, question,
                agentCode == null ? "router" : agentCode, parentSessionId, parentToolCallId,
                rounds, failRounds);
    }

    /** 造一轮：output 为原文文本（文本/终答轮） */
    private void insertTextRound(long id, String sessionId, String conversationId, int round,
                                 String inputData, String output, boolean success, String agentCode) {
        insertRound(id, sessionId, conversationId, round, inputData, output, success, null, agentCode);
    }

    /** 造一轮：output 为 JSON 结构（工具轮 tool_calls / 带 content 的文本轮） */
    private void insertJsonRound(long id, String sessionId, String conversationId, int round,
                                 String inputData, String outputJson, boolean success,
                                 String error, String agentCode) {
        insertRound(id, sessionId, conversationId, round, inputData, outputJson, success, error, agentCode);
    }

    private void insertRound(long id, String sessionId, String conversationId, int round,
                             String inputData, String output, boolean success,
                             String error, String agentCode) {
        jdbc.update("""
                INSERT INTO agentx_monitor_trace
                  (id, session_id, conversation_id, round, duration_ms, success, error_message,
                   round_type, input_chars, prompt_tokens, completion_tokens, agent_code)
                VALUES (?, ?, ?, ?, 3200, ?, ?, ?, ?, 100, 50, ?)
                """, id, sessionId, conversationId, round, success ? 1 : 0, error,
                success ? "text" : "error", inputData == null ? 0 : inputData.length(),
                agentCode == null ? "router" : agentCode);
        jdbc.update("INSERT INTO agentx_trace_payload (trace_id, input_data, output_data) VALUES (?, ?, ?)",
                id, inputData, output);
    }

    // ==================== U1 无子会话回归 ====================

    @Test
    void timeline_无子会话时结构与现状一致零回归() {
        insertSession(ROOT, "统计上季度付款情况", null, null, null, 2, 0);
        // R1 委派工具轮 → R2 终答轮
        insertJsonRound(11, ROOT, CONV, 1,
                "[{\"role\":\"user\",\"content\":\"统计上季度付款情况\"}]",
                "{\"tool_calls\":[{\"id\":\"call_1\",\"name\":\"query_table\",\"arguments\":\"{}\"}]}", true, null, null);
        insertTextRound(12, ROOT, CONV, 2,
                "[{\"role\":\"user\",\"content\":\"统计上季度付款情况\"}," +
                        "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"call_1\",\"name\":\"query_table\",\"arguments\":\"{}\"}]}," +
                        "{\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"content\":\"table rows\"}," +
                        "{\"role\":\"user\",\"content\":\"继续\"}]",
                "上季度付款总额 328 万", true, null);

        SessionTimelineVO vo = service.sessionTimeline(ROOT);
        assertThat(vo).isNotNull();
        assertThat(vo.sessionId()).isEqualTo(ROOT);
        assertThat(vo.roundCount()).isEqualTo(2);
        // 事件序列：user(0) llm(1) tool(2) answer(3)
        assertThat(vo.events()).extracting(SessionTimelineVO.Event::type)
                .containsExactly("user", "llm", "tool", "answer");
        assertThat(vo.events()).extracting(SessionTimelineVO.Event::seq)
                .containsExactly(0, 1, 2, 3);
        // 所有事件无子会话挂载 + 孤儿兜底为空数组（single 模式零回归）
        assertThat(vo.events()).allSatisfy(e -> assertThat(e.subSession()).isNull());
        assertThat(vo.orphanSubSessions()).isEmpty();
        // tool 事件结果照常配对
        SessionTimelineVO.Event tool = vo.events().get(2);
        assertThat(tool.callId()).isEqualTo("call_1");
        assertThat(tool.resultOk()).isTrue();
        assertThat(tool.resultRefSeq()).isEqualTo(3);
    }

    // ==================== U2 单子会话挂载 ====================

    @Test
    void timeline_子会话按parentToolCallId挂载到委派tool事件() {
        insertSession(ROOT, "统计上季度项目付款情况", null, null, null, 2, 0);
        insertJsonRound(11, ROOT, CONV, 1,
                "[{\"role\":\"user\",\"content\":\"统计上季度项目付款情况\"}]",
                "{\"tool_calls\":[{\"id\":\"call_1\",\"name\":\"call_data_analysis\",\"arguments\":\"{\\\"task\\\":\\\"分析付款\\\"}\"}]}",
                true, null, null);
        insertTextRound(12, ROOT, CONV, 2,
                "[{\"role\":\"user\",\"content\":\"统计上季度项目付款情况\"}," +
                        "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"call_1\",\"name\":\"call_data_analysis\",\"arguments\":\"{}\"}]}," +
                        "{\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"content\":\"{\\\"summary\\\":\\\"上季度付款328万\\\"}\"}]",
                "统计完成：上季度付款总额 328 万", true, null);

        // 子会话：任务文本进 question，两轮（executeSql 工具轮 + 产出轮）
        insertSession(SUB1, "分析上季度付款数据并输出结论", "data-analysis", ROOT, "call_1", 2, 0);
        insertJsonRound(21, SUB1, CONV, 1,
                "[{\"role\":\"user\",\"content\":\"分析上季度付款数据并输出结论\"}]",
                "{\"tool_calls\":[{\"id\":\"sub_c1\",\"name\":\"executeSql\",\"arguments\":\"{\\\"sql\\\":\\\"select 1\\\"}\"}]}",
                true, null, "data-analysis");
        insertTextRound(22, SUB1, CONV, 2,
                "[{\"role\":\"user\",\"content\":\"分析上季度付款数据并输出结论\"}," +
                        "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"sub_c1\",\"name\":\"executeSql\",\"arguments\":\"{}\"}]}," +
                        "{\"role\":\"tool\",\"tool_call_id\":\"sub_c1\",\"content\":\"rows\"}]",
                "子Agent产出：付款 328 万", true, "data-analysis");

        SessionTimelineVO vo = service.sessionTimeline(ROOT);
        SessionTimelineVO.Event delegate = vo.events().stream()
                .filter(e -> "call_data_analysis".equals(e.toolName())).findFirst().orElseThrow();
        SessionTimelineVO.SubSession sub = delegate.subSession();
        assertThat(sub).isNotNull();
        assertThat(sub.agentCode()).isEqualTo("data-analysis");
        assertThat(sub.sessionId()).isEqualTo(SUB1);
        assertThat(sub.task()).isEqualTo("分析上季度付款数据并输出结论");
        assertThat(sub.status()).isEqualTo("completed");
        assertThat(sub.roundCount()).isEqualTo(2);
        assertThat(sub.failRounds()).isEqualTo(0);
        // 子事件树完整：user(任务) → llm(决策) → tool(executeSql) → answer(产出)
        assertThat(sub.events()).extracting(SessionTimelineVO.Event::type)
                .containsExactly("user", "llm", "tool", "answer");
        assertThat(sub.events().get(0).assistantText()).isEqualTo("分析上季度付款数据并输出结论");
        assertThat(sub.events().get(2).toolName()).isEqualTo("executeSql");
        assertThat(sub.events().get(3).assistantText()).isEqualTo("子Agent产出：付款 328 万");
        // 子 tool 事件结果照常配对（子命名空间内自洽）
        assertThat(sub.events().get(2).resultOk()).isTrue();
        assertThat(sub.events().get(2).parentSeq()).isEqualTo(sub.events().get(1).seq());
        // 主轴其余事件不受影响 + 无孤儿
        assertThat(vo.orphanSubSessions()).isEmpty();
        assertThat(vo.events()).extracting(SessionTimelineVO.Event::type)
                .containsExactly("user", "llm", "tool", "answer");
    }

    // ==================== U3 同轮双委派 ====================

    @Test
    void timeline_同轮双委派各自挂载不串() {
        insertSession(ROOT, "并行做两件事", null, null, null, 2, 0);
        insertJsonRound(11, ROOT, CONV, 1,
                "[{\"role\":\"user\",\"content\":\"并行做两件事\"}]",
                "{\"tool_calls\":[{\"id\":\"call_1\",\"name\":\"call_data_analysis\",\"arguments\":\"{}\"}," +
                        "{\"id\":\"call_2\",\"name\":\"call_declarant_fill\",\"arguments\":\"{}\"}]}",
                true, null, null);
        insertTextRound(12, ROOT, CONV, 2,
                "[{\"role\":\"user\",\"content\":\"并行做两件事\"}," +
                        "{\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"content\":\"A done\"}," +
                        "{\"role\":\"tool\",\"tool_call_id\":\"call_2\",\"content\":\"B done\"}]",
                "两件事都完成", true, null);

        insertSession(SUB1, "任务A：付款统计", "data-analysis", ROOT, "call_1", 1, 0);
        insertTextRound(21, SUB1, CONV, 1,
                "[{\"role\":\"user\",\"content\":\"任务A：付款统计\"}]", "子产出A", true, "data-analysis");
        insertSession(SUB2, "任务B：填报表", "declarant-fill", ROOT, "call_2", 1, 0);
        insertTextRound(22, SUB2, CONV, 1,
                "[{\"role\":\"user\",\"content\":\"任务B：填报表\"}]", "子产出B", true, "declarant-fill");

        SessionTimelineVO vo = service.sessionTimeline(ROOT);
        SessionTimelineVO.Event d1 = vo.events().stream()
                .filter(e -> "call_1".equals(e.callId())).findFirst().orElseThrow();
        SessionTimelineVO.Event d2 = vo.events().stream()
                .filter(e -> "call_2".equals(e.callId())).findFirst().orElseThrow();
        assertThat(d1.subSession()).isNotNull();
        assertThat(d1.subSession().agentCode()).isEqualTo("data-analysis");
        assertThat(d1.subSession().events()).extracting(SessionTimelineVO.Event::assistantText)
                .contains("子产出A");
        assertThat(d2.subSession()).isNotNull();
        assertThat(d2.subSession().agentCode()).isEqualTo("declarant-fill");
        assertThat(d2.subSession().events()).extracting(SessionTimelineVO.Event::assistantText)
                .contains("子产出B");
        assertThat(vo.orphanSubSessions()).isEmpty();
    }

    // ==================== U4 跨轮委派 ====================

    @Test
    void timeline_跨轮委派各自挂载() {
        insertSession(ROOT, "两轮两次委派", null, null, null, 3, 0);
        insertJsonRound(11, ROOT, CONV, 1,
                "[{\"role\":\"user\",\"content\":\"两轮两次委派\"}]",
                "{\"tool_calls\":[{\"id\":\"call_1\",\"name\":\"call_data_analysis\",\"arguments\":\"{}\"}]}", true, null, null);
        insertJsonRound(12, ROOT, CONV, 2,
                "[{\"role\":\"user\",\"content\":\"两轮两次委派\"}," +
                        "{\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"content\":\"A done\"}]",
                "{\"tool_calls\":[{\"id\":\"call_2\",\"name\":\"call_declarant_fill\",\"arguments\":\"{}\"}]}", true, null, null);
        insertTextRound(13, ROOT, CONV, 3,
                "[{\"role\":\"user\",\"content\":\"两轮两次委派\"}," +
                        "{\"role\":\"tool\",\"tool_call_id\":\"call_2\",\"content\":\"B done\"}]",
                "完成", true, null);

        insertSession(SUB1, "任务A", "data-analysis", ROOT, "call_1", 1, 0);
        insertTextRound(21, SUB1, CONV, 1, "[{\"role\":\"user\",\"content\":\"任务A\"}]", "子产出A", true, "data-analysis");
        insertSession(SUB2, "任务B", "declarant-fill", ROOT, "call_2", 1, 0);
        insertTextRound(22, SUB2, CONV, 1, "[{\"role\":\"user\",\"content\":\"任务B\"}]", "子产出B", true, "declarant-fill");

        SessionTimelineVO vo = service.sessionTimeline(ROOT);
        SessionTimelineVO.Event d1 = vo.events().stream()
                .filter(e -> "call_1".equals(e.callId())).findFirst().orElseThrow();
        SessionTimelineVO.Event d2 = vo.events().stream()
                .filter(e -> "call_2".equals(e.callId())).findFirst().orElseThrow();
        assertThat(d1.round()).isEqualTo(1);
        assertThat(d1.subSession().sessionId()).isEqualTo(SUB1);
        assertThat(d2.round()).isEqualTo(2);
        assertThat(d2.subSession().sessionId()).isEqualTo(SUB2);
        assertThat(vo.orphanSubSessions()).isEmpty();
    }

    // ==================== U5 失败子会话 ====================

    @Test
    void timeline_失败子会话轮次统计与失败原因透传() {
        insertSession(ROOT, "委派一个会失败的任务", null, null, null, 2, 0);
        insertJsonRound(11, ROOT, CONV, 1,
                "[{\"role\":\"user\",\"content\":\"委派一个会失败的任务\"}]",
                "{\"tool_calls\":[{\"id\":\"call_1\",\"name\":\"call_data_analysis\",\"arguments\":\"{}\"}]}", true, null, null);
        insertTextRound(12, ROOT, CONV, 2,
                "[{\"role\":\"user\",\"content\":\"委派一个会失败的任务\"}," +
                        "{\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"content\":\"子任务失败\"}]",
                "子任务失败了", true, null);

        // 子会话：R1 成功 → R2 失败（SQL 语法错误）→ R3 成功产出（自愈）
        insertSession(SUB1, "分析付款并输出结论", "data-analysis", ROOT, "call_1", 3, 1);
        insertJsonRound(21, SUB1, CONV, 1,
                "[{\"role\":\"user\",\"content\":\"分析付款并输出结论\"}]",
                "{\"tool_calls\":[{\"id\":\"s1\",\"name\":\"executeSql\",\"arguments\":\"{}\"}]}", true, null, "data-analysis");
        insertJsonRound(22, SUB1, CONV, 2,
                "[{\"role\":\"user\",\"content\":\"分析付款并输出结论\"}," +
                        "{\"role\":\"tool\",\"tool_call_id\":\"s1\",\"content\":\"rows\"}]",
                "{\"tool_calls\":[{\"id\":\"s2\",\"name\":\"executeSql\",\"arguments\":\"{}\"}]}",
                false, "SQL 语法错误", "data-analysis");
        insertTextRound(23, SUB1, CONV, 3,
                "[{\"role\":\"user\",\"content\":\"分析付款并输出结论\"}," +
                        "{\"role\":\"tool\",\"tool_call_id\":\"s1\",\"content\":\"rows\"}," +
                        "{\"role\":\"tool\",\"tool_call_id\":\"s2\",\"content\":\"❌ 语法错误 near from\"}]",
                "修正后产出：付款 328 万", true, "data-analysis");

        SessionTimelineVO vo = service.sessionTimeline(ROOT);
        SessionTimelineVO.SubSession sub = vo.events().stream()
                .filter(e -> e.subSession() != null).findFirst().orElseThrow().subSession();
        assertThat(sub.failRounds()).isEqualTo(1);
        assertThat(sub.roundCount()).isEqualTo(3);
        // 子事件树：user llm tool llm(失败) tool(❌) answer
        assertThat(sub.events()).extracting(SessionTimelineVO.Event::type)
                .containsExactly("user", "llm", "tool", "llm", "tool", "answer");
        SessionTimelineVO.Event failLlm = sub.events().get(3);
        assertThat(failLlm.success()).isFalse();
        assertThat(failLlm.errorMessage()).isEqualTo("SQL 语法错误");
        SessionTimelineVO.Event failTool = sub.events().get(4);
        assertThat(failTool.resultOk()).isFalse();
        // 失败轮的 tool 结果全文在下一轮（子命名空间内 resultRefSeq）可回查
        assertThat(failTool.resultRefSeq()).isEqualTo(sub.events().get(5).seq());
    }

    // ==================== U6 孤儿兜底 ====================

    @Test
    void timeline_挂不上委派点的子会话进孤儿兜底区() {
        insertSession(ROOT, "旧数据孤儿场景", null, null, null, 2, 0);
        insertJsonRound(11, ROOT, CONV, 1,
                "[{\"role\":\"user\",\"content\":\"旧数据孤儿场景\"}]",
                "{\"tool_calls\":[{\"id\":\"call_1\",\"name\":\"query_table\",\"arguments\":\"{}\"}]}", true, null, null);
        insertTextRound(12, ROOT, CONV, 2,
                "[{\"role\":\"user\",\"content\":\"旧数据孤儿场景\"}," +
                        "{\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"content\":\"rows\"}]",
                "完成", true, null);

        // 旧数据：M-SA3 前子会话无 parent_tool_call_id（null）→ 挂不上
        insertSession(SUB_ORPH, "历史孤儿任务", "data-analysis", ROOT, null, 1, 0);
        insertTextRound(21, SUB_ORPH, CONV, 1,
                "[{\"role\":\"user\",\"content\":\"历史孤儿任务\"}]", "孤儿产出", true, "data-analysis");
        // callId 对不上任何主轴委派（脏数据）+ 零轮次子会话（委派即失败）
        insertSession(SUB_EMPTY, "零轮次任务", "data-analysis", ROOT, "call_nx", 0, 0);

        SessionTimelineVO vo = service.sessionTimeline(ROOT);
        // 主轴无任何挂载（call_1 是普通 query_table，非委派）
        assertThat(vo.events()).allSatisfy(e -> assertThat(e.subSession()).isNull());
        // 孤儿兜底区可见不静默丢失：两个子会话都在（按创建序）
        assertThat(vo.orphanSubSessions()).extracting(SessionTimelineVO.SubSession::sessionId)
                .containsExactly(SUB_ORPH, SUB_EMPTY);
        SessionTimelineVO.SubSession orph = vo.orphanSubSessions().get(0);
        assertThat(orph.agentCode()).isEqualTo("data-analysis");
        assertThat(orph.task()).isEqualTo("历史孤儿任务");
        assertThat(orph.events()).extracting(SessionTimelineVO.Event::assistantText).contains("孤儿产出");
        // 零轮次子会话：events 空列表（非 null）
        assertThat(vo.orphanSubSessions().get(1).events()).isEmpty();
    }

    // ==================== U7 子事件 seq 命名空间自洽 ====================

    @Test
    void timeline_子事件seq独立命名空间且引用自洽() {
        // 主轴先烧掉 seq 0..3（user/llm/tool/answer），子事件必须独立从 0 起编
        insertSession(ROOT, "seq 命名空间", null, null, null, 2, 0);
        insertJsonRound(11, ROOT, CONV, 1,
                "[{\"role\":\"user\",\"content\":\"seq 命名空间\"}]",
                "{\"tool_calls\":[{\"id\":\"call_1\",\"name\":\"call_data_analysis\",\"arguments\":\"{}\"}]}", true, null, null);
        insertTextRound(12, ROOT, CONV, 2,
                "[{\"role\":\"user\",\"content\":\"seq 命名空间\"}," +
                        "{\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"content\":\"done\"}]",
                "完成", true, null);

        insertSession(SUB1, "子任务", "data-analysis", ROOT, "call_1", 2, 0);
        insertJsonRound(21, SUB1, CONV, 1,
                "[{\"role\":\"user\",\"content\":\"子任务\"}]",
                "{\"tool_calls\":[{\"id\":\"s1\",\"name\":\"executeSql\",\"arguments\":\"{}\"}]}", true, null, "data-analysis");
        insertTextRound(22, SUB1, CONV, 2,
                "[{\"role\":\"user\",\"content\":\"子任务\"}," +
                        "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"s1\",\"name\":\"executeSql\",\"arguments\":\"{}\"}]}," +
                        "{\"role\":\"tool\",\"tool_call_id\":\"s1\",\"content\":\"rows\"}]",
                "子产出", true, "data-analysis");

        SessionTimelineVO vo = service.sessionTimeline(ROOT);
        List<SessionTimelineVO.Event> main = vo.events();
        // 主轴 seq 0..3 连续
        assertThat(main).extracting(SessionTimelineVO.Event::seq).containsExactly(0, 1, 2, 3);
        List<SessionTimelineVO.Event> subEvents = main.get(2).subSession().events();
        // 子事件 seq 独立从 0 编号（user llm tool answer），不受主轴影响
        assertThat(subEvents).extracting(SessionTimelineVO.Event::seq).containsExactly(0, 1, 2, 3);
        // 子块内 parentSeq / resultRefSeq 在子命名空间内自洽
        assertThat(subEvents.get(2).parentSeq()).isEqualTo(1);
        assertThat(subEvents.get(2).resultRefSeq()).isEqualTo(3);
        // 子 tool 事件全文按 callId 在子事件 resultRefSeq 指向的 delta 内可回查
        SessionTimelineVO.Event subTool = subEvents.get(2);
        SessionTimelineVO.Event subAnswer = subEvents.get(subTool.resultRefSeq());
        assertThat(subAnswer.deltaMessages()).anySatisfy(m -> {
            assertThat(m.role()).isEqualTo("tool");
            assertThat(m.toolCallId()).isEqualTo("s1");
            assertThat(m.content()).isEqualTo("rows");
        });
    }

    // ==================== U9 conversationDetail 轮次 agentCode 标注 ====================

    @Test
    void conversationDetail_rounds带agentCode与父子归属() {
        jdbc.update("INSERT INTO agentx_monitor_conversation (id, conversation_id, user_id, title, status) " +
                "VALUES (?, ?, '20', '窗口标题', 'completed')", CONV.hashCode(), CONV);
        insertSession(ROOT, "带子轮次的提问", null, null, null, 1, 0);
        insertTextRound(11, ROOT, CONV, 1,
                "[{\"role\":\"user\",\"content\":\"带子轮次的提问\"}]", "主答", true, null);
        insertSession(SUB1, "子任务", "data-analysis", ROOT, "call_1", 1, 0);
        insertTextRound(21, SUB1, CONV, 1,
                "[{\"role\":\"user\",\"content\":\"子任务\"}]", "子产出", true, "data-analysis");
        // 子轮次归属（写侧 M-SA3 口径：monitor_trace.parent_session_id = 根会话）
        jdbc.update("UPDATE agentx_monitor_trace SET parent_session_id = ? WHERE id = 21", ROOT);

        var detail = service.conversationDetail(CONV);
        assertThat(detail).isNotNull();
        // 一问一行口径不回归：sessions 只有根会话行
        assertThat(detail.sessions()).hasSize(1);
        assertThat(detail.sessions().get(0).sessionId()).isEqualTo(ROOT);
        // rounds 混合主+子轮次，agentCode/parentSessionId 标注归属
        List<RoundListItem> rounds = detail.rounds();
        assertThat(rounds).hasSize(2);
        RoundListItem mainRound = rounds.stream().filter(r -> ROOT.equals(r.sessionId())).findFirst().orElseThrow();
        assertThat(mainRound.agentCode()).isEqualTo("router");
        assertThat(mainRound.parentSessionId()).isNull();
        RoundListItem subRound = rounds.stream().filter(r -> SUB1.equals(r.sessionId())).findFirst().orElseThrow();
        assertThat(subRound.agentCode()).isEqualTo("data-analysis");
        assertThat(subRound.parentSessionId()).isEqualTo(ROOT);
    }
}
