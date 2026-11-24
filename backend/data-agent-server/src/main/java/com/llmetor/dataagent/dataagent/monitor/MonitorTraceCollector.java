package com.llmetor.dataagent.dataagent.monitor;

import com.llmetor.miniagentx.memory.MessageJsonSerializer;
import com.llmetor.miniagentx.trace.TraceCollector;
import com.llmetor.miniagentx.trace.TraceManager;
import com.llmetor.miniagentx.utils.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 监控中心自有表采集器（监控中心自有表方案 §4.2，T4）：写入新轨三表——
 * <ul>
 *   <li>{@code agentx_monitor_conversation} 窗口（一窗口一行，聚合快照）</li>
 *   <li>{@code agentx_monitor_session} 提问（一问一行，可检索，终态回填 answer）</li>
 *   <li>{@code agentx_monitor_trace} 轮次（一轮一行，瘦行；大字段复用 agentx_trace_payload）</li>
 * </ul>
 *
 * <p>写入模式（D5 双轨）：旧 TraceManager 照旧写系统表，本类互不影响；
 * 全部方法自吞异常——失败仅 warn + 失败计数进 {@code agentx_monitor_metric}
 * （metric=trace_write_failures, dim_user='collector'，避开 MetricAggregateJob 的
 * DELETE dim_user IS NULL 清桶），不阻断主流程。
 *
 * <p>与旧 TraceManager 同构的落库姿势：JdbcTemplate 直查 + REQUIRES_NEW 独立小事务 +
 * 启动幂等建表（sql/trace-monitor/V05__monitor_own_tables.sql 与本类 DDL 同构）。
 */
@Slf4j
public class MonitorTraceCollector implements TraceCollector {

    /** 窗口标题截断长度（列表展示） */
    private static final int TITLE_MAX_LENGTH = 120;
    /** 写失败计数指标名（总览 KPI「trace 写入失败数」读同一名字） */
    public static final String METRIC_WRITE_FAILURES = "trace_write_failures";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;
    private final MessageJsonSerializer serializer = new MessageJsonSerializer();
    /** 异常检测派发器（Step 3，D3）：closeSession 终态后异步检测；Spring 侧启动后由 DodoAgent 注入 */
    private volatile com.llmetor.dataagent.dataagent.monitor.detect.DetectDispatcher detectDispatcher;

    public MonitorTraceCollector(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        // 独立小事务（REQUIRES_NEW），同 TraceManager：由 DodoAgent 直接 new，不进容器
        DefaultTransactionDefinition txDef = new DefaultTransactionDefinition();
        txDef.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.txTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource), txDef);
        createTable();
    }

    /** DodoAgent 装配时注入（本类由 new 创建不进容器，无法自注入；null 时跳过检测） */
    public void setDetectDispatcher(com.llmetor.dataagent.dataagent.monitor.detect.DetectDispatcher dispatcher) {
        this.detectDispatcher = dispatcher;
    }

    /** 幂等建表（与 sql/trace-monitor/V05 同构；metric 表 DDL 同 MetricAggregateJob） */
    public void createTable() {
        // ① 窗口表：列表页主表，聚合列终态递增（refreshConversation 全量重算，自愈不漂移）
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agentx_monitor_conversation (
                  id                bigint PRIMARY KEY,
                  conversation_id   varchar(100) NOT NULL UNIQUE,
                  user_id           varchar(100),
                  title             varchar(120),
                  question_count    int  DEFAULT 0,
                  total_rounds      int  DEFAULT 0,
                  fail_rounds       int  DEFAULT 0,
                  prompt_tokens     bigint DEFAULT 0,
                  completion_tokens bigint DEFAULT 0,
                  status            varchar(20),
                  started_at        timestamp,
                  last_active_at    timestamp,
                  created_at        timestamp DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_amc_user   ON agentx_monitor_conversation (user_id, last_active_at DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_amc_active ON agentx_monitor_conversation (last_active_at DESC)");

        // ② 提问表：一次提问一行（检索入口）
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agentx_monitor_session (
                  id              bigint PRIMARY KEY,
                  conversation_id varchar(100) NOT NULL,
                  session_id      varchar(100) NOT NULL UNIQUE,
                  seq             int NOT NULL,
                  user_id         varchar(100),
                  question        text NOT NULL,
                  status          varchar(20) DEFAULT 'running',
                  answer          text,
                  answer_chars    int,
                  answer_trace_id bigint,
                  rounds          int,
                  fail_rounds     int,
                  prompt_tokens   bigint,
                  completion_tokens bigint,
                  duration_ms     bigint,
                  first_error     text,
                  created_at      timestamp DEFAULT CURRENT_TIMESTAMP,
                  completed_at    timestamp
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ams_conv    ON agentx_monitor_session (conversation_id, seq)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ams_created ON agentx_monitor_session (created_at)");
        try {
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ams_running ON agentx_monitor_session (created_at) WHERE status = 'running'");
        } catch (Exception e) {
            log.warn("[monitor-collector] running 部分索引创建失败（不影响启动）: {}", e.getMessage());
        }

        // ③ 轮次表：一轮 LLM 调用一行；唯一键 = session_id + round（一问内一轮，绝不用 conversation_id+round）
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agentx_monitor_trace (
                  id              bigint PRIMARY KEY,
                  conversation_id varchar(100) NOT NULL,
                  session_id      varchar(100) NOT NULL,
                  round           int NOT NULL,
                  round_type      varchar(10),
                  tool_names      text[],
                  input_chars     int,
                  prompt_tokens   bigint,
                  completion_tokens bigint,
                  duration_ms     bigint,
                  think_duration_ms bigint,
                  success         int DEFAULT 1,
                  error_message   text,
                  created_at      timestamp DEFAULT CURRENT_TIMESTAMP
                )
                """);
        try {
            jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_amt_session_round ON agentx_monitor_trace (session_id, round)");
        } catch (Exception e) {
            log.warn("[monitor-collector] uk_amt_session_round 创建失败（存量脏数据，请核对后手工补建）: {}", e.getMessage());
        }
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_amt_conv    ON agentx_monitor_trace (conversation_id, created_at)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_amt_created ON agentx_monitor_trace (created_at)");
        try {
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_amt_failed  ON agentx_monitor_trace (session_id) WHERE success = 0");
        } catch (Exception e) {
            log.warn("[monitor-collector] failed 部分索引创建失败（不影响启动）: {}", e.getMessage());
        }

        // 失败计数落 metric 表（DDL 与 MetricAggregateJob 同构；此处幂等兜底，规避 bean 初始化顺序）
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agentx_monitor_metric (
                  id bigint NOT NULL,
                  minute timestamp NOT NULL,
                  metric varchar(50) NOT NULL,
                  dim_user varchar(100) NULL,
                  value double precision NOT NULL,
                  PRIMARY KEY (id)
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_metric ON agentx_monitor_metric (metric, minute)");
    }

    // ==================== 挂点一：提问开始 ====================

    /**
     * ① 首问 INSERT 窗口行（ON CONFLICT DO NOTHING，title=首问截断）；
     * ② INSERT 提问行（seq=MAX+1，同窗口前端串行提问，冲突概率极低）；
     * ③ 窗口刷新（status→running、question_count、last_active_at）。
     */
    @Override
    public void openSession(String sessionId, String conversationId, String userId, String question) {
        try {
            String q = question == null ? "" : question;
            jdbc.update("""
                    INSERT INTO agentx_monitor_conversation
                      (id, conversation_id, user_id, title, status, started_at, last_active_at)
                    VALUES (?, ?, ?, ?, 'running', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT (conversation_id) DO NOTHING
                    """, SnowflakeIdGenerator.nextId(), conversationId, userId, abbreviate(q, TITLE_MAX_LENGTH));
            Integer seq = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(seq), 0) + 1 FROM agentx_monitor_session WHERE conversation_id = ?",
                    Integer.class, conversationId);
            jdbc.update("""
                    INSERT INTO agentx_monitor_session
                      (id, conversation_id, session_id, seq, user_id, question, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'running', CURRENT_TIMESTAMP)
                    """, SnowflakeIdGenerator.nextId(), conversationId, sessionId,
                    seq == null ? 1 : seq, userId, q);
            refreshConversation(conversationId);
        } catch (Exception e) {
            log.warn("[monitor-collector] openSession 失败（不影响主流程）: convId={}, sessionId={}",
                    conversationId, sessionId, e);
            recordFailure();
        }
    }

    // ==================== 挂点二：每轮 ====================

    /**
     * 同事务双 INSERT：monitor_trace 瘦行 + agentx_trace_payload 大字段（复用不动）。
     * 分析列（round_type/tool_names/input_chars）复用 TraceManager.analyzeRound 同一口径。
     */
    @Override
    public void trace(String sessionId, String conversationId, int round,
                      List<Message> inputMessages, String outputData, String think,
                      long promptTokens, long completionTokens, long durationMs,
                      long thinkDurationMs, boolean success, String errorMessage) {
        try {
            String inputJson = serializer.toJsonArray(inputMessages);
            long traceId = SnowflakeIdGenerator.nextId();
            TraceManager.Analysis analysis = TraceManager.analyzeRound(outputData, success);
            Integer inputChars = inputJson == null ? null : inputJson.length();
            Long thinkMs = thinkDurationMs > 0 ? thinkDurationMs : null;

            txTemplate.executeWithoutResult(txStatus -> {
                jdbc.update("""
                        INSERT INTO agentx_monitor_trace
                          (id, conversation_id, session_id, round, round_type, tool_names, input_chars,
                           prompt_tokens, completion_tokens, duration_ms, think_duration_ms, success, error_message)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, (PreparedStatement ps) -> {
                    int i = 1;
                    ps.setLong(i++, traceId);
                    ps.setString(i++, conversationId);
                    ps.setString(i++, sessionId);
                    ps.setInt(i++, round);
                    ps.setString(i++, analysis.roundType());
                    if (analysis.toolNames() != null) {
                        ps.setArray(i++, ps.getConnection().createArrayOf("text", analysis.toolNames()));
                    } else {
                        ps.setNull(i++, java.sql.Types.ARRAY);
                    }
                    if (inputChars != null) {
                        ps.setInt(i++, inputChars);
                    } else {
                        ps.setNull(i++, java.sql.Types.INTEGER);
                    }
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
                });
                jdbc.update("INSERT INTO agentx_trace_payload (trace_id, input_data, output_data, think) " +
                        "VALUES (?, ?, ?, ?)", traceId, inputJson, outputData, think);
            });
        } catch (Exception e) {
            log.warn("[monitor-collector] trace 写入失败（不影响主流程）: convId={}, sessionId={}, round={}",
                    conversationId, sessionId, round, e);
            recordFailure();
        }
    }

    // ==================== 挂点三：提问终态 ====================

    /**
     * UPDATE 提问行：answer / answer_chars / answer_trace_id（末个成功 text 轮，输出与
     * finalAnswer 全等优先）/ rounds / fail_rounds / tokens / duration_ms（墙钟）/
     * first_error（首个失败轮错误）/ status / completed_at；随后窗口聚合刷新。
     * UPDATE 落 0 行（恢复通道原行缺失等）跳过并计失败。
     */
    @Override
    public void closeSession(String sessionId, String conversationId, String status, String finalAnswer) {
        try {
            Map<String, Object> agg = jdbc.queryForMap(
                    "SELECT COUNT(*) AS rounds, " +
                            "COALESCE(SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END), 0) AS fail_rounds, " +
                            "COALESCE(SUM(COALESCE(prompt_tokens, 0)), 0) AS prompt_tokens, " +
                            "COALESCE(SUM(COALESCE(completion_tokens, 0)), 0) AS completion_tokens " +
                            "FROM agentx_monitor_trace WHERE session_id = ?", sessionId);
            // 首个失败轮错误：无失败轮是常态（0 行），queryForObject 空结果会抛异常，用 queryForList 空安全取值
            List<String> errRows = jdbc.queryForList(
                    "SELECT error_message FROM agentx_monitor_trace " +
                            "WHERE session_id = ? AND success = 0 ORDER BY round ASC LIMIT 1",
                    String.class, sessionId);
            String firstError = errRows.isEmpty() ? null : errRows.get(0);

            Integer answerChars = null;
            Long answerTraceId = null;
            if (finalAnswer != null && !finalAnswer.isEmpty()) {
                answerChars = finalAnswer.length();
                answerTraceId = findAnswerTraceId(sessionId, finalAnswer);
            }
            Timestamp now = new Timestamp(System.currentTimeMillis());

            int updated = jdbc.update("""
                    UPDATE agentx_monitor_session SET
                      status = ?, answer = ?, answer_chars = ?, answer_trace_id = ?,
                      rounds = ?, fail_rounds = ?, prompt_tokens = ?, completion_tokens = ?,
                      duration_ms = GREATEST(0, EXTRACT(EPOCH FROM (? - created_at)) * 1000)::bigint,
                      first_error = ?, completed_at = ?
                    WHERE session_id = ?
                    """, status, finalAnswer, answerChars, answerTraceId,
                    longOf(agg.get("rounds")), longOf(agg.get("fail_rounds")),
                    longOf(agg.get("prompt_tokens")), longOf(agg.get("completion_tokens")),
                    now, firstError, now, sessionId);
            if (updated == 0) {
                log.warn("[monitor-collector] closeSession 未命中 session 行（跳过）: convId={}, sessionId={}, status={}",
                        conversationId, sessionId, status);
                recordFailure();
                return;
            }
            refreshConversation(conversationId);
            // Step 3 找到异常（03 方案 §4）：终态回填后异步检测（单线程池自吞异常，不阻断主流程）
            com.llmetor.dataagent.dataagent.monitor.detect.DetectDispatcher dispatcher = this.detectDispatcher;
            if (dispatcher != null) {
                dispatcher.submitAfterClose(sessionId, status);
            }
        } catch (Exception e) {
            log.warn("[monitor-collector] closeSession 失败（不影响主流程）: convId={}, sessionId={}, status={}",
                    conversationId, sessionId, status, e);
            recordFailure();
        }
    }

    /**
     * answer_trace_id 定位：末个成功 text 轮里挑 output_data 与 finalAnswer 全等的轮
     * （extractAnswer 口径倒序首个实质性回答，通常就是末个 text 轮；非实质性收尾时可能
     * 落在更早的 text 轮，LIMIT 5 窗口内全等匹配），匹配不上回退末个 text 轮。
     */
    private Long findAnswerTraceId(String sessionId, String answer) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT t.id, p.output_data FROM agentx_monitor_trace t " +
                        "JOIN agentx_trace_payload p ON p.trace_id = t.id " +
                        "WHERE t.session_id = ? AND t.success = 1 AND t.round_type = 'text' " +
                        "ORDER BY t.round DESC LIMIT 5", sessionId);
        Long fallback = null;
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            if (fallback == null) {
                fallback = id;
            }
            if (answer.equals(row.get("output_data"))) {
                return id;
            }
        }
        return fallback;
    }

    /**
     * 窗口聚合刷新：全部聚合列从 monitor_session 重算（会话级聚合已在 closeSession 固化，
     * running 行聚合列视为 0）。单行 UPDATE，一个窗口最多几十问，代价可忽略；
     * 相比增量递增自愈（重试/补偿不会双计）。
     */
    private void refreshConversation(String conversationId) {
        jdbc.update("""
                UPDATE agentx_monitor_conversation c SET
                  question_count    = a.question_count,
                  total_rounds      = a.total_rounds,
                  fail_rounds       = a.fail_rounds,
                  prompt_tokens     = a.prompt_tokens,
                  completion_tokens = a.completion_tokens,
                  status            = a.status,
                  started_at        = a.started_at,
                  last_active_at    = a.last_active_at
                FROM (
                  SELECT COUNT(*) AS question_count,
                         COALESCE(SUM(COALESCE(rounds, 0)), 0) AS total_rounds,
                         COALESCE(SUM(COALESCE(fail_rounds, 0)), 0) AS fail_rounds,
                         COALESCE(SUM(COALESCE(prompt_tokens, 0)), 0) AS prompt_tokens,
                         COALESCE(SUM(COALESCE(completion_tokens, 0)), 0) AS completion_tokens,
                         CASE WHEN COUNT(*) FILTER (WHERE status = 'running') > 0 THEN 'running'
                              WHEN COUNT(*) FILTER (WHERE status = 'error') > 0 THEN 'error'
                              WHEN COUNT(*) FILTER (WHERE status = 'interrupted') > 0 THEN 'interrupted'
                              ELSE 'completed' END AS status,
                         MIN(created_at) AS started_at,
                         MAX(COALESCE(completed_at, created_at)) AS last_active_at
                  FROM agentx_monitor_session WHERE conversation_id = ?
                ) a
                WHERE c.conversation_id = ?
                """, conversationId, conversationId);
    }

    // ==================== 失败可观测 ====================

    /**
     * 写失败计数（方案 §7）：每 5 分钟桶一行（dim_user='collector'，躲开聚合任务的
     * DELETE dim_user IS NULL 清桶），UPSERT = 先 UPDATE 递增、无行再 INSERT。
     * 并发双插理论可能（失败本就罕见），KPI 只做 SUM 不受影响。
     */
    private void recordFailure() {
        try {
            LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
            LocalDateTime bucket = now.truncatedTo(ChronoUnit.HOURS)
                    .plusMinutes((now.getMinute() / 5) * 5L);
            Timestamp minute = Timestamp.valueOf(bucket);
            int updated = jdbc.update(
                    "UPDATE agentx_monitor_metric SET value = value + 1 " +
                            "WHERE minute = ? AND metric = ? AND dim_user = 'collector'",
                    minute, METRIC_WRITE_FAILURES);
            if (updated == 0) {
                jdbc.update("INSERT INTO agentx_monitor_metric (id, minute, metric, dim_user, value) " +
                                "VALUES (?, ?, ?, 'collector', 1)",
                        SnowflakeIdGenerator.nextId(), minute, METRIC_WRITE_FAILURES);
            }
        } catch (Exception e) {
            log.debug("[monitor-collector] 失败计数写入异常（放弃本次计数）: {}", e.getMessage());
        }
    }

    // ==================== 小工具 ====================

    private static long longOf(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static String abbreviate(String text, int max) {
        String oneLine = text.replace("\r", " ").replace("\n", " ").trim();
        return oneLine.length() > max ? oneLine.substring(0, max) + "…" : oneLine;
    }
}
