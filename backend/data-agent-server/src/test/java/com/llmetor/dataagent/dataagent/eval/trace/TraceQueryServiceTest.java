package com.llmetor.dataagent.dataagent.eval.trace;

import com.llmetor.miniagentx.memory.MessageJsonSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M8 trace 查询三工具单测：H2（MySQL 模式）真实跑 SQL，
 * 覆盖 overview 三段式 / 失败轮与压缩点 / 预览截断省思考 / roundDetail 渲染 /
 * firstAppear 四类段落命中与思考提示。
 */
class TraceQueryServiceTest {

    private static final String CONV = "eval_conv_1";
    private static final String SESSION = "1001";

    private final MessageJsonSerializer serializer = new MessageJsonSerializer();
    private JdbcTemplate jdbc;
    private TraceQueryService service;

    @BeforeEach
    void setUp() {
        // 每个测试实例独立内存库（DB_CLOSE_DELAY 关闭时同名库会跨实例残留建表冲突）
        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:tracequery" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE agentx_conversation (
                  id bigint PRIMARY KEY, conversation_id varchar(100), session_id varchar(100),
                  user_id varchar(100), question CLOB, status varchar(20),
                  created_at timestamp DEFAULT CURRENT_TIMESTAMP, completed_at timestamp NULL)
                """);
        jdbc.execute("""
                CREATE TABLE agentx_session (
                  id bigint PRIMARY KEY, conversation_id varchar(100), session_id varchar(100),
                  state_key varchar(255), item_index int, state_data CLOB,
                  created_at timestamp DEFAULT CURRENT_TIMESTAMP,
                  updated_at timestamp DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE agentx_trace (
                  id bigint PRIMARY KEY, session_id varchar(100), conversation_id varchar(100),
                  round int, input_data CLOB NULL, output_data CLOB NULL, think CLOB NULL,
                  prompt_tokens bigint DEFAULT 0, completion_tokens bigint DEFAULT 0,
                  duration_ms bigint DEFAULT 0, success int DEFAULT 1, error_message CLOB NULL,
                  created_at timestamp DEFAULT CURRENT_TIMESTAMP)
                """);
        service = new TraceQueryService(ds);
        insertFixtures();
    }

    private void insertFixtures() {
        jdbc.update("INSERT INTO agentx_conversation (id, conversation_id, session_id, user_id, question, status) " +
                        "VALUES (1, ?, ?, '20', '帮我查一下华东大区的订单总额', 'completed')", CONV, SESSION);

        // original_messages 四条消息：user → assistant(round1, 带工具调用) → tool 结果(含 3280000) → assistant(round2, 带思考)
        String toolResultText = "华东大区 | 3280000 | 2025-08 | ".repeat(10);  // > 200 字，验证截断
        String item0 = serializer.toJsonArray(List.of(new UserMessage("帮我查一下华东大区的订单总额")));
        String item1 = serializer.toJsonArray(List.of(AssistantMessage.builder()
                .content("先看一下订单表结构，再执行聚合查询")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "executeSql",
                        "{\"sql\":\"SELECT region, SUM(amount) FROM orders GROUP BY region\"}")))
                .build()));
        String item2 = serializer.toJsonArray(List.of(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "executeSql", toolResultText)))
                .build()));
        AssistantMessage finalMsg = AssistantMessage.builder()
                .content("华东大区订单总额约 328 万")
                .properties(Map.of(MessageJsonSerializer.KEY_REASONING,
                        "用户说的大区要映射到 region 字段，328 万是四舍五入的推导口径XYZ"))
                .build();
        String item3 = serializer.toJsonArray(List.of(finalMsg));

        Object[][] rows = {
                {1L, CONV, SESSION, "original_messages", 0, item0},
                {2L, CONV, SESSION, "original_messages", 1, item1},
                {3L, CONV, SESSION, "original_messages", 2, item2},
                {4L, CONV, SESSION, "original_messages", 3, item3},
        };
        for (Object[] r : rows) {
            jdbc.update("INSERT INTO agentx_session (id, conversation_id, session_id, state_key, item_index, state_data) " +
                            "VALUES (?, ?, ?, ?, ?, ?)", r);
        }

        // trace 四轮：1、2 成功，3 失败，4 成功且 input 字符量骤降（压缩点）
        insertTrace(1, 1000, 1, null);
        insertTrace(2, 900, 1, null);
        insertTrace(3, 1500, 0, "SQL 执行超时");
        insertTrace(4, 800, 1, null);
    }

    private void insertTrace(int round, int inputChars, int success, String error) {
        jdbc.update("INSERT INTO agentx_trace (id, session_id, conversation_id, round, input_data, output_data, " +
                        "duration_ms, success, error_message) VALUES (?, ?, ?, ?, ?, ?, 3200, ?, ?)",
                (long) round, SESSION, CONV, round, "x".repeat(inputChars), "[{}]", success, error);
    }

    // ── traceOverview ──

    @Test
    void overview_三段式与失败轮压缩点() {
        String out = service.overview(CONV);
        assertThat(out).contains("# 执行过程全貌 conversation_id=" + CONV);
        assertThat(out).contains("- session " + SESSION + " | 用户 20 | 状态 completed | 问题：帮我查一下华东大区的订单总额");
        assertThat(out).contains("- round 3 | 失败 | 错误：SQL 执行超时");
        assertThat(out).contains("- round 4 | input 字符量 1500→800 骤降，之前发生过上下文压缩");
        assertThat(out).contains("item 0 | user：帮我查一下华东大区的订单总额");
        // assistant 行标注成功轮（第1、2 个 assistant 对应 round 1、2）
        assertThat(out).contains("item 1 | assistant（round 1）：先看一下订单表结构");
        assertThat(out).contains("调用 executeSql(");
        assertThat(out).contains("item 3 | assistant（round 2）：华东大区订单总额约 328 万");
    }

    @Test
    void overview_预览截断且省略思考() {
        String out = service.overview(CONV);
        // 思考内容不出现在通读预览
        assertThat(out).doesNotContain("推导口径XYZ");
        // 工具结果 >200 字被截断成一行
        assertThat(out).containsPattern("结果 executeSql：.{200,260}\\.\\.\\.");
    }

    @Test
    void overview_会话不存在给出提示() {
        String out = service.overview("no_such_conv");
        assertThat(out).contains("未找到 conversation_id=no_such_conv");
    }

    // ── traceRound ──

    @Test
    void roundDetail_失败轮完整入参出参() {
        String out = service.roundDetail(SESSION, 3, false);
        assertThat(out).contains("# 轮次详情 session_id=" + SESSION + " round=3");
        assertThat(out).contains("失败 | 错误：SQL 执行超时");
        assertThat(out).contains("## input_data");
        assertThat(out).contains("## output_data（模型本轮输出原文）");
    }

    @Test
    void roundDetail_参数错误与未命中() {
        assertThat(service.roundDetail(" ", 1, false)).contains("参数错误");
        assertThat(service.roundDetail(SESSION, 99, false)).contains("未找到 session_id=" + SESSION + " round=99");
    }

    @Test
    void roundDetail_inputData含tools字段时按includeTools开关() {
        // 直接造一条带 tools 字段的 input_data（本内核 trace 天然无 tools，此为渲染逻辑契约）
        String inputWithTools = "{\"model\":\"m\",\"tools\":[{\"name\":\"executeSql\"}],\"messages\":[]}";
        jdbc.update("INSERT INTO agentx_trace (id, session_id, conversation_id, round, input_data, output_data, " +
                        "duration_ms, success, error_message) VALUES (10, '2002', ?, 1, ?, 'out', 100, 1, NULL)", CONV, inputWithTools);

        String omitted = service.roundDetail("2002", 1, false);
        assertThat(omitted).doesNotContain("executeSql");
        assertThat(omitted).contains("tools 工具定义已省略");

        String included = service.roundDetail("2002", 1, true);
        assertThat(included).contains("## tools（工具定义）");
        assertThat(included).contains("executeSql");
    }

    // ── traceFirstAppear ──

    @Test
    void firstAppear_命中工具结果() {
        String out = service.firstAppear(CONV, "3280000");
        assertThat(out).contains("首次出现在 item_index=2");
        assertThat(out).contains("工具结果 executeSql");
        assertThat(out).contains("3280000");
        assertThat(out).doesNotContain("命中模型思考");
    }

    @Test
    void firstAppear_命中思考带提示() {
        String out = service.firstAppear(CONV, "推导口径XYZ");
        assertThat(out).contains("assistant 思考");
        assertThat(out).contains("命中模型思考");
    }

    @Test
    void firstAppear_命中正文与未命中() {
        assertThat(service.firstAppear(CONV, "订单总额约")).contains("assistant 正文");
        assertThat(service.firstAppear(CONV, "不存在的词"))
                .contains("未出现").contains("换一个更短或更关键的特征值");
    }

    @Test
    void firstAppear_空关键词报参数错() {
        assertThat(service.firstAppear(CONV, " ")).contains("参数错误");
    }
}
