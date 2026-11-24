package com.llmetor.miniagentx.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmetor.miniagentx.memory.MessageJsonSerializer;
import com.llmetor.miniagentx.utils.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.TransactionDefinition;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * trace 审计：每轮 LLM 调用写一行（瘦索引表 agentx_trace + 明细表 agentx_trace_payload 双写）。
 *
 * <p>M1 瘦表化改造（docs/trace追溯设计 §4）：input_data 是全量消息快照，长会话单轮可达数百 KB，
 * 监控列表/聚合/count 都不需要它——大字段（input/output/think）拆入 payload 表（trace_id 一对一），
 * 瘦表只留索引字段并新增写入时提取的分析列（round_type / tool_names / input_chars，避免运行时解析大 JSON）。
 * 双写在同一事务里提交（任一失败整批回滚），写失败仅 warn 不影响主流程
 * （这份数据是 M8 评测/诊断与监控体系的根基）。
 *
 * <p>迁移契约（docs/trace追溯设计 §4.5）：读侧 TraceQueryService 对 payload 缺行回退瘦表旧列，
 * 因此 {@link #WRITE_LEGACY_BIG_COLUMNS}=true（双写期）期间新旧行都完整可读；
 * 上线稳定一周后置 false 停写旧列（配合 sql/trace-monitor/V05 置 NULL 回收空间）。
 */
public class TraceManager implements TraceCollector {

    private static final Logger log = LoggerFactory.getLogger(TraceManager.class);

    /**
     * 是否继续写瘦表旧大字段列（input_data/output_data/think）。迁移期 true（双写）；
     * 上线稳定一周后（T1.6，执行 sql/trace-monitor/V05 前）改 false——payload 表成为唯一明细来源。
     */
    public static volatile boolean WRITE_LEGACY_BIG_COLUMNS = true;

    /** output_data 工具轮固定前缀（AgentLoopExecutor.toolCallsJson 的输出形态） */
    private static final String TOOL_CALLS_PREFIX = "{\"tool_calls\"";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;
    private final MessageJsonSerializer serializer = new MessageJsonSerializer();
    /** 轮次分析列提取用（无状态，静态共享；ObjectMapper 线程安全） */
    private static final ObjectMapper json = new ObjectMapper();

    public TraceManager(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        // 双写同事务：自建 DataSourceTransactionManager（TraceManager 由 DodoAgent 直接 new，
        // 不进容器，无全局 PlatformTransactionManager 可注入）
        DefaultTransactionDefinition txDef = new DefaultTransactionDefinition();
        txDef.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.txTemplate = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource), txDef);
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
        // 思考单独耗时（存量表补列；PG 支持 ADD COLUMN IF NOT EXISTS）
        jdbc.execute("ALTER TABLE agentx_trace ADD COLUMN IF NOT EXISTS think_duration_ms bigint NULL DEFAULT NULL");
        // ── M1 瘦表化：payload 明细表 + 瘦表分析列 + 瘦表专用索引（sql/trace-monitor/V01 同构）──
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agentx_trace_payload (
                  trace_id bigint PRIMARY KEY,
                  input_data text NULL,
                  output_data text NULL,
                  think text NULL,
                  created_at timestamp NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_payload_created ON agentx_trace_payload (created_at)");
        jdbc.execute("ALTER TABLE agentx_trace ADD COLUMN IF NOT EXISTS round_type varchar(10)");
        jdbc.execute("ALTER TABLE agentx_trace ADD COLUMN IF NOT EXISTS tool_names text[]");
        jdbc.execute("ALTER TABLE agentx_trace ADD COLUMN IF NOT EXISTS input_chars int");
        // 唯一/部分索引在存量脏数据（重复轮次）上会创建失败——只 warn 不阻断启动，
        // 由 V01 脚本在 DBA 核对后补建
        try {
            jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_trace_conv_round ON agentx_trace (conversation_id, round)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_trace_created ON agentx_trace (created_at)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_trace_failed ON agentx_trace (conversation_id) WHERE success = 0");
        } catch (Exception e) {
            log.warn("trace 瘦表索引创建失败（存量数据可能有重复轮次，请核对后执行 V01 脚本补建）: {}", e.getMessage());
        }
    }

    /**
     * 记一轮（同事务双写：瘦索引表 + payload 明细表）。sessionId 用字符串（表列 varchar）。
     *
     * @param thinkDurationMs 本轮"思考"单独耗时（首条 reasoning chunk → 最后一条 reasoning chunk）；
     *                        无思考/历史数据为 0 或负数时落 NULL
     */
    public void trace(String sessionId, String conversationId, int round,
                      List<Message> inputMessages, String outputData, String think,
                      long promptTokens, long completionTokens, long durationMs,
                      long thinkDurationMs, boolean success, String errorMessage) {
        try {
            String inputJson = serializer.toJsonArray(inputMessages);
            Long thinkMs = thinkDurationMs > 0 ? thinkDurationMs : null;
            long traceId = SnowflakeIdGenerator.nextId();
            // 分析列写入时顺手提取（读侧不再解析大 JSON）
            Analysis analysis = analyzeRound(outputData, success);
            Integer inputChars = inputJson == null ? null : inputJson.length();
            boolean legacy = WRITE_LEGACY_BIG_COLUMNS;

            txTemplate.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO agentx_trace (id, session_id, conversation_id, round, input_data, " +
                                "output_data, think, prompt_tokens, completion_tokens, duration_ms, think_duration_ms, " +
                                "success, error_message, round_type, tool_names, input_chars) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        (PreparedStatement ps) -> {
                            int i = 1;
                            ps.setLong(i++, traceId);
                            ps.setString(i++, sessionId);
                            ps.setString(i++, conversationId);
                            ps.setInt(i++, round);
                            ps.setString(i++, legacy ? inputJson : null);
                            ps.setString(i++, legacy ? outputData : null);
                            ps.setString(i++, legacy ? think : null);
                            ps.setLong(i++, promptTokens);
                            ps.setLong(i++, completionTokens);
                            ps.setLong(i++, durationMs);
                            if (thinkMs != null) {
                                ps.setLong(i++, thinkMs);
                            } else {
                                ps.setNull(i++, java.sql.Types.BIGINT);
                            }
                            ps.setInt(i++, success ? 1 : 0);
                            ps.setString(i++, errorMessage);
                            ps.setString(i++, analysis.roundType());
                            if (analysis.toolNames() != null) {
                                ArraySetter.set(ps, i++, analysis.toolNames());
                            } else {
                                ps.setNull(i++, java.sql.Types.ARRAY);
                            }
                            if (inputChars != null) {
                                ps.setInt(i++, inputChars);
                            } else {
                                ps.setNull(i++, java.sql.Types.INTEGER);
                            }
                        });
                jdbc.update("INSERT INTO agentx_trace_payload (trace_id, input_data, output_data, think) " +
                                "VALUES (?, ?, ?, ?)",
                        traceId, inputJson, outputData, think);
            });
        } catch (Exception e) {
            log.warn("trace 写入失败（不影响主流程）: convId={}, round={}", conversationId, round, e);
        }
    }

    /** 轮次分析结果：瘦表新列的数据源 */
    public record Analysis(String roundType, String[] toolNames) {
    }

    /**
     * 提取 round_type 与 tool_names：失败轮 = error；工具轮（output 为 {"tool_calls":[...]}）
     * = tool 并抽出本轮调用的工具名（去重保序）；其余 = text。
     * 解析失败按 output 形态兜底归类，不抛异常（分析列缺失不影响审计主数据）。
     * public：监控侧 MonitorTraceCollector 复用同一口径（分析列两轨一致）。
     */
    public static Analysis analyzeRound(String outputData, boolean success) {
        if (!success) {
            return new Analysis("error", null);
        }
        if (outputData != null && outputData.startsWith(TOOL_CALLS_PREFIX)) {
            List<String> names = new ArrayList<>();
            try {
                JsonNode calls = json.readTree(outputData).path("tool_calls");
                if (calls.isArray()) {
                    for (JsonNode c : calls) {
                        String name = c.path("name").asText(null);
                        if (name != null && !name.isBlank() && !names.contains(name)) {
                            names.add(name);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("tool_names 提取失败，按前缀兜底归类 tool: {}", e.getMessage());
            }
            return new Analysis("tool", names.isEmpty() ? null : names.toArray(new String[0]));
        }
        return new Analysis("text", null);
    }

    /** text[] 数组参数绑定（PG createArrayOf；连接不可用时退化为 NULL，仅丢分析列） */
    private static final class ArraySetter {

        static void set(PreparedStatement ps, int index, String[] values) {
            try {
                Connection conn = ps.getConnection();
                java.sql.Array arr = conn.createArrayOf("text", values);
                ps.setArray(index, arr);
            } catch (SQLException e) {
                log.debug("tool_names 数组绑定失败，落 NULL: {}", e.getMessage());
                try {
                    ps.setNull(index, java.sql.Types.ARRAY);
                } catch (SQLException ignored) {
                    // 连接已不可用，主键行仍会写入失败并走外层 warn
                }
            }
        }
    }
}
