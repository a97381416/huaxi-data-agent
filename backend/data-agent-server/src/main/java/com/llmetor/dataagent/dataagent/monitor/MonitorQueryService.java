package com.llmetor.dataagent.dataagent.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmetor.dataagent.dataagent.eval.trace.TraceQueryService;
import com.llmetor.dataagent.dataagent.monitor.dto.ConversationDetailVO;
import com.llmetor.dataagent.dataagent.monitor.dto.OverviewVO;
import com.llmetor.dataagent.dataagent.monitor.dto.PageResult;
import com.llmetor.dataagent.dataagent.monitor.dto.RoundDetailVO;
import com.llmetor.dataagent.dataagent.monitor.dto.RoundListItem;
import com.llmetor.dataagent.dataagent.monitor.dto.SearchItem;
import com.llmetor.dataagent.dataagent.monitor.dto.SessionPageItem;
import com.llmetor.dataagent.dataagent.monitor.dto.SessionTimelineVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 监控查询服务（人工排查查询，监控中心自有表方案 §5）：提问列表/窗口详情/单轮明细/快捷搜索/总览。
 *
 * <p>读模式（D6）：<b>只读监控自有三表</b>（agentx_monitor_conversation / _session / _trace），
 * 明细大字段仍按 trace_id 读 agentx_trace_payload（复用不动）。
 *
 * <p>提问维度（2026-11-19 评审反馈）：主列表 = <b>一次提问一行</b>（agentx_monitor_session
 * 单表分页，问题/回答/状态/轮次/耗时行内直出），窗口全貌在详情页呈现；
 * 问题模糊搜索直接命中提问行。
 * 与 SessionService 同一套访问模式（DataSource 自建 JdbcTemplate 直查，不建业务实体）；
 * SQL 按 PG 方言写（EXTRACT(EPOCH ...) / FILTER (WHERE ...)）。
 */
@Slf4j
@Service
public class MonitorQueryService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 模糊问题搜索返回的会话建议上限（设计 §5.2：最多 10 条） */
    private static final int SEARCH_SUGGEST_LIMIT = 10;

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final TraceQueryService traceQueryService;
    /** 卡死判定阈值：running 超过该分钟数标 stuck（前端给「置为 interrupted」确认框） */
    private final long stuckThresholdMinutes;

    public MonitorQueryService(DataSource dataSource,
                               TraceQueryService traceQueryService,
                               @Value("${data-agent.monitor.stuck-threshold-minutes:10}") long stuckThresholdMinutes) {
        // 刻意注入 DataSource 自建 JdbcTemplate（同 SessionService：避免拿到 pgVectorJdbcTemplate）
        this.jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        this.traceQueryService = traceQueryService;
        this.stuckThresholdMinutes = stuckThresholdMinutes;
    }

    // ==================== T2.1 提问列表（主视图：一次提问一行） ====================

    /**
     * 提问列表（主视图，2026-11-19 评审反馈：核心是每个提问的列表，不是会话列表）。
     * 分页查 agentx_monitor_session LEFT JOIN agentx_monitor_conversation（取窗口 title 作会话摘要），
     * 筛选全部 WHERE 下推；每行自带该提问全链路瘦字段（问题原文/最终回答预览/状态/轮次/失败轮/耗时），
     * 无需进详情即可定位。
     *
     * <p>排序（2026-11-19 评审反馈）：先按会话创建时间降序（同窗口提问聚在一起），
     * 再按提问创建时间升序（窗口内按对话顺序正排）。
     *
     * @param userId         精确匹配
     * @param sessionId      精确匹配
     * @param conversationId 精确匹配（同窗口的多次提问各自成行）
     * @param summary        会话摘要模糊匹配（窗口 title，大小写不敏感）
     * @param status         提问状态，支持逗号分隔多值（前端多选框）
     * @param minMs/maxMs    提问墙钟耗时范围（毫秒，closeSession 回填）
     * @param start/end      提问时间范围 yyyy-MM-dd HH:mm:ss
     * @param q              问题原文模糊匹配（大小写不敏感）
     * @param minRounds/maxRounds 轮数阈值（closeSession 回填的 rounds）
     */
    public PageResult<SessionPageItem> sessions(String userId, String sessionId,
                                                String conversationId, String summary, String status,
                                                Long minMs, Long maxMs,
                                                String start, String end,
                                                String q, Long minRounds, Long maxRounds,
                                                Boolean riskOnly, String severity, String ruleId,
                                                String detectorVersion,
                                                Integer page, Integer size) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size <= 0 ? 20 : Math.min(size, 100);

        StringBuilder where = new StringBuilder(" WHERE s.parent_session_id IS NULL");
        List<Object> args = new ArrayList<>();
        if (hasText(conversationId)) {
            where.append(" AND s.conversation_id = ?");
            args.add(conversationId.trim());
        }
        if (hasText(sessionId)) {
            where.append(" AND s.session_id = ?");
            args.add(sessionId.trim());
        }
        if (hasText(userId)) {
            where.append(" AND s.user_id = ?");
            args.add(userId.trim());
        }
        if (hasText(status)) {
            List<String> statuses = Arrays.stream(status.split(","))
                    .map(String::trim).filter(v -> !v.isEmpty()).toList();
            if (!statuses.isEmpty()) {
                where.append(" AND s.status IN (")
                        .append(String.join(",", statuses.stream().map(v -> "?").toList()))
                        .append(")");
                args.addAll(statuses);
            }
        }
        Timestamp startTs = parseTs(start, true);
        Timestamp endTs = parseTs(end, false);
        if (startTs != null) {
            where.append(" AND s.created_at >= ?");
            args.add(startTs);
        }
        if (endTs != null) {
            where.append(" AND s.created_at < ?");
            args.add(endTs);
        }
        if (minMs != null) {
            where.append(" AND COALESCE(s.duration_ms, 0) >= ?");
            args.add(minMs);
        }
        if (maxMs != null) {
            where.append(" AND COALESCE(s.duration_ms, 0) < ?");
            args.add(maxMs);
        }
        if (hasText(q)) {
            where.append(" AND LOWER(s.question) LIKE ?");
            args.add("%" + q.trim().toLowerCase() + "%");
        }
        if (hasText(summary)) {
            where.append(" AND LOWER(c.title) LIKE ?");
            args.add("%" + summary.trim().toLowerCase() + "%");
        }
        if (minRounds != null) {
            where.append(" AND COALESCE(s.rounds, 0) >= ?");
            args.add(minRounds);
        }
        if (maxRounds != null) {
            where.append(" AND COALESCE(s.rounds, 0) < ?");
            args.add(maxRounds);
        }
        // ── Step3 风险筛选（03 方案 §6.2）：仅看有风险 / 级别 / 规则 ──
        // 级别=high 走 sessions 新列（免 join）；medium/low 与规则筛选 join risk 表（低频筛选接受）
        if (Boolean.TRUE.equals(riskOnly)) {
            where.append(" AND COALESCE(s.risk_total, 0) > 0");
        }
        if (hasText(severity)) {
            String sev = severity.trim().toLowerCase();
            if ("high".equals(sev)) {
                where.append(" AND COALESCE(s.risk_high, 0) > 0");
            } else if ("medium".equals(sev) || "low".equals(sev)) {
                where.append(" AND EXISTS (SELECT 1 FROM agentx_monitor_risk r " +
                        "WHERE r.session_id = s.session_id AND r.severity = ?)");
                args.add(sev);
            }
        }
        if (hasText(ruleId)) {
            where.append(" AND EXISTS (SELECT 1 FROM agentx_monitor_risk r " +
                    "WHERE r.session_id = s.session_id AND r.rule_id = ?)");
            args.add(ruleId.trim());
        }
        // V2：检测引擎版本筛选（v1 历史行 / v2 新检测；空=不过滤）
        if (hasText(detectorVersion)) {
            where.append(" AND COALESCE(s.detector_version, '') = ?");
            args.add(detectorVersion.trim());
        }

        // LEFT JOIN 窗口表：会话摘要（title）行内直出 + 会话创建时间参与排序
        String fromJoin = " FROM agentx_monitor_session s" +
                " LEFT JOIN agentx_monitor_conversation c ON c.conversation_id = s.conversation_id";

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*)" + fromJoin + where,
                Long.class, args.toArray());

        String pageSql = """
                SELECT s.conversation_id, c.title AS conversation_title, s.session_id, s.seq, s.user_id, s.question, s.status,
                       s.answer, s.answer_chars, s.answer_trace_id, s.rounds, s.fail_rounds,
                       s.first_error, s.prompt_tokens, s.completion_tokens, s.duration_ms,
                       s.created_at, s.completed_at,
                       s.detect_status, s.risk_total, s.risk_high, s.first_risk, s.detector_version
                """ + fromJoin + where
                + " ORDER BY COALESCE(c.started_at, c.created_at) DESC NULLS LAST, s.created_at ASC, s.seq ASC LIMIT ? OFFSET ?";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(p * s);
        List<SessionPageItem> items = jdbc.query(pageSql, (rs, i) -> new SessionPageItem(
                rs.getString("conversation_id"),
                rs.getString("conversation_title"),
                rs.getString("session_id"),
                rs.getInt("seq"),
                rs.getString("user_id"),
                rs.getString("question"),
                rs.getString("status"),
                abbreviate(rs.getString("answer"), 160),
                rs.getObject("answer_chars") == null ? null : rs.getInt("answer_chars"),
                rs.getObject("answer_trace_id") == null ? null : String.valueOf(rs.getLong("answer_trace_id")),
                rs.getObject("rounds") == null ? null : rs.getLong("rounds"),
                rs.getObject("fail_rounds") == null ? null : rs.getLong("fail_rounds"),
                abbreviate(rs.getString("first_error"), 120),
                rs.getLong("prompt_tokens"),
                rs.getLong("completion_tokens"),
                rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"),
                formatTs(rs.getTimestamp("created_at")),
                formatTs(rs.getTimestamp("completed_at")),
                rs.getObject("risk_total") == null ? null : rs.getInt("risk_total"),
                rs.getObject("risk_high") == null ? null : rs.getInt("risk_high"),
                abbreviate(rs.getString("first_risk"), 160),
                rs.getString("detect_status"),
                rs.getString("detector_version")), pageArgs.toArray());
        return PageResult.of(total == null ? 0 : total, p, s, items);
    }

    // ==================== T2.2 会话详情 ====================

    /**
     * 单问最终回答全文（提问列表「最终回答」点击弹窗渲染 MD；未完成/未回填返回 null）。
     * 列表接口的 answerPreview 仅 160 字截断，弹窗按需取全文。
     */
    public String sessionAnswer(String sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT answer FROM agentx_monitor_session WHERE session_id = ?", sessionId);
        return rows.isEmpty() ? null : str(rows.get(0).get("answer"));
    }

    /**
     * 会话详情：窗口物化聚合 + 全部提问（一次提问一行）+ 轮次瘦字段列表
     * （前端按 session_id 分组渲染 Q·R 时间轴，深链参数 ?sessionId=&round= 自行定位高亮）。
     */
    public ConversationDetailVO conversationDetail(String conversationId) {
        List<Map<String, Object>> convs = jdbc.queryForList(
                "SELECT conversation_id, user_id, title, question_count, total_rounds, fail_rounds, " +
                        "prompt_tokens, completion_tokens, status, started_at, last_active_at " +
                        "FROM agentx_monitor_conversation WHERE conversation_id = ?", conversationId);
        if (convs.isEmpty()) {
            return null;
        }
        Map<String, Object> conv = convs.get(0);
        Timestamp started = ts(conv.get("started_at"));
        Timestamp lastActive = ts(conv.get("last_active_at"));

        List<ConversationDetailVO.SessionItem> sessions = jdbc.query(
                "SELECT session_id, seq, user_id, question, answer, answer_chars, answer_trace_id, status, " +
                        "rounds, fail_rounds, duration_ms, prompt_tokens, completion_tokens, created_at, completed_at, " +
                        "detect_status, risk_total, risk_high " +
                        "FROM agentx_monitor_session WHERE conversation_id = ? AND parent_session_id IS NULL ORDER BY seq ASC",
                (rs, i) -> new ConversationDetailVO.SessionItem(
                        rs.getString("session_id"),
                        rs.getInt("seq"),
                        rs.getString("user_id"),
                        rs.getString("question"),
                        rs.getString("answer"),
                        rs.getObject("answer_chars") == null ? null : rs.getInt("answer_chars"),
                        rs.getObject("answer_trace_id") == null ? null : String.valueOf(rs.getLong("answer_trace_id")),
                        rs.getString("status"),
                        rs.getObject("rounds") == null ? null : rs.getInt("rounds"),
                        rs.getObject("fail_rounds") == null ? null : rs.getInt("fail_rounds"),
                        rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"),
                        rs.getObject("prompt_tokens") == null ? null : rs.getLong("prompt_tokens"),
                        rs.getObject("completion_tokens") == null ? null : rs.getLong("completion_tokens"),
                        formatTs(rs.getTimestamp("created_at")),
                        formatTs(rs.getTimestamp("completed_at")),
                        rs.getObject("risk_total") == null ? null : rs.getInt("risk_total"),
                        rs.getObject("risk_high") == null ? null : rs.getInt("risk_high"),
                        rs.getString("detect_status")),
                conversationId);

        List<RoundListItem> rounds = jdbc.query(
                "SELECT id, session_id, round, round_type, tool_names, prompt_tokens, completion_tokens, " +
                        "duration_ms, think_duration_ms, success, error_message, input_chars, created_at, " +
                        "agent_code, parent_session_id " +
                        "FROM agentx_monitor_trace WHERE conversation_id = ? ORDER BY created_at ASC, id ASC",
                (rs, i) -> roundRow(rs), conversationId);
        long promptTokens = rounds.stream().mapToLong(RoundListItem::promptTokens).sum();
        long completionTokens = rounds.stream().mapToLong(RoundListItem::completionTokens).sum();
        long failRounds = rounds.stream().filter(r -> !r.success()).count();

        return new ConversationDetailVO(conversationId,
                str(conv.get("user_id")),
                str(conv.get("title")),
                str(conv.get("status")),
                (int) longVal(conv.get("question_count")),
                wallMs(started, lastActive),
                promptTokens,
                completionTokens,
                failRounds,
                formatTs(started),
                formatTs(lastActive),
                rounds,
                sessions);
    }

    // ==================== 提问事件流时间轴（2026-11-20 排查视角改造） ====================

    private static final ObjectMapper TIMELINE_MAPPER = new ObjectMapper();
    /** 增量消息单条内容上限（超限截断；全文走「全量输入」懒加载兜底） */
    private static final int DELTA_CONTENT_CAP = 100_000;
    /** 工具结果预览长度（单行） */
    private static final int RESULT_PREVIEW_LEN = 160;
    /** 执行成功但未命中数据的前缀提示（左轴 ⓘ；启发式，宁漏勿错） */
    private static final List<String> RESULT_EMPTY_HINTS =
            List.of("未找到", "没有找到", "无结果", "未匹配", "没有匹配", "查无", "不存在名为");

    /**
     * 提问事件流：一轮 LLM 调用拆成 🤖 llm（思考+决策）+ N 个 🔧 tool（并发调用）事件，
     * 加 ❓ user 起点与 💬 answer 终答；工具结果按 tool_call_id 从下一轮 INPUT 配对回填，
     * 增量消息（本轮相比上一轮新传入）随 llm 事件直出。全文重字段（全量 INPUT）仍走
     * /monitor/rounds/{traceId} 懒加载，本接口只携带 delta/参数/预览等轻内容。
     *
     * <p>配对口径：R(n) 的工具结果 = R(n+1) INPUT 中 role=tool 且 tool_call_id 相同的消息；
     * assistant 原话 = R(n+1) INPUT 增量段中的 assistant 消息（answer 轮 = output 原文）。
     * 历史轮 payload 缺行时对应事件 delta 为空（前端提示「明细缺失」）。
     *
     * <p>子Agent穿透（M-TT，读侧补齐写侧零改动）：① 先查 parent_session_id = 本会话的
     * 子会话行，逐个用 buildEvents() 组装子事件树（eager 内嵌，D1）；② 子会话按
     * parent_tool_call_id == 委派 tool 事件 callId 挂载（同 callId 多行时首个挂载）；
     * ③ 挂不上的进顶层 orphanSubSessions 兜底区（旧数据/关联缺失，D4 可见不静默丢失）。
     */
    public SessionTimelineVO sessionTimeline(String sessionId) {
        List<Map<String, Object>> sess = jdbc.queryForList(
                "SELECT conversation_id, session_id, question, status FROM agentx_monitor_session WHERE session_id = ?",
                sessionId);
        if (sess.isEmpty()) {
            return null;
        }
        Map<String, Object> s = sess.get(0);
        String question = str(s.get("question"));

        // ① 子会话行查询（M-TT）：parent_session_id = 本会话，按创建序（D1 eager 内嵌）
        List<Map<String, Object>> subRows = jdbc.queryForList(
                "SELECT session_id, agent_code, question, status, parent_tool_call_id, rounds, fail_rounds, " +
                        "prompt_tokens, completion_tokens FROM agentx_monitor_session " +
                        "WHERE parent_session_id = ? ORDER BY created_at ASC, id ASC", sessionId);
        List<SessionTimelineVO.SubSession> subs = new ArrayList<>(subRows.size());
        Map<String, SessionTimelineVO.SubSession> subByCallId = new LinkedHashMap<>();
        for (Map<String, Object> row : subRows) {
            SessionTimelineVO.SubSession sub = new SessionTimelineVO.SubSession(
                    str(row.get("agent_code")),
                    str(row.get("session_id")),
                    str(row.get("question")),
                    str(row.get("status")),
                    intObj(row.get("rounds")),
                    intObj(row.get("fail_rounds")),
                    longObj(row.get("prompt_tokens")),
                    longObj(row.get("completion_tokens")),
                    buildEvents(str(row.get("session_id")), str(row.get("question")), Map.of()).events());
            subs.add(sub);
            String ptc = str(row.get("parent_tool_call_id"));
            if (!ptc.isBlank()) {
                subByCallId.putIfAbsent(ptc, sub);
            }
        }

        // ② 主会话事件组装（同一 buildEvents 复用）+ 委派 tool 事件挂载子会话
        BuiltTimeline main = buildEvents(sessionId, question, subByCallId);

        // ③ 未挂载的子会话进孤儿兜底区（D4：可见不静默丢失）
        java.util.Set<String> mountedIds = new java.util.HashSet<>();
        for (SessionTimelineVO.Event ev : main.events()) {
            if (ev.subSession() != null) {
                mountedIds.add(ev.subSession().sessionId());
            }
        }
        List<SessionTimelineVO.SubSession> orphans = subs.stream()
                .filter(sub -> !mountedIds.contains(sub.sessionId()))
                .toList();

        return new SessionTimelineVO(sessionId, str(s.get("conversation_id")),
                question, str(s.get("status")), main.roundCount(), main.events(), orphans);
    }

    /** buildEvents 组装结果：事件流 + 轮数（VO roundCount 口径 = monitor_trace 行数） */
    private record BuiltTimeline(List<SessionTimelineVO.Event> events, int roundCount) {
    }

    /**
     * 事件流组装（主/子会话共用，M-TT 抽取重构行为不变）：读该会话全部轮次瘦行 + payload，
     * 逐轮拆 user/llm/tool/answer 事件。subByCallId 非空时（仅主轴），委派 tool 事件
     * callId 命中即挂载对应 SubSession（record 不可变，构造时传入）。
     */
    private BuiltTimeline buildEvents(String sessionId, String question,
                                      Map<String, SessionTimelineVO.SubSession> subByCallId) {
        List<RoundListItem> rounds = jdbc.query(
                "SELECT t.id, t.session_id, t.round, t.round_type, t.tool_names, t.prompt_tokens, t.completion_tokens, " +
                        "t.duration_ms, t.think_duration_ms, t.success, t.error_message, t.input_chars, t.created_at, " +
                        "t.agent_code, t.parent_session_id " +
                        "FROM agentx_monitor_trace t WHERE t.session_id = ? ORDER BY t.round ASC, t.id ASC",
                (rs, i) -> roundRow(rs), sessionId);
        if (rounds.isEmpty()) {
            return new BuiltTimeline(List.of(), 0);
        }

        // payload 一次取回（input_data 大字段在 Java 侧解析后即弃，响应只含轻内容）
        String inClause = String.join(",", java.util.Collections.nCopies(rounds.size(), "?"));
        Object[] traceIds = rounds.stream().map(r -> Long.parseLong(r.traceId())).toArray();
        Map<Long, Map<String, Object>> payloads = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT trace_id, input_data, output_data, think FROM agentx_trace_payload WHERE trace_id IN (" + inClause + ")",
                traceIds)) {
            payloads.put(((Number) row.get("trace_id")).longValue(), row);
        }

        // 逐轮解析：消息列表 / 决策 tool_calls / 文本输出 / think
        int n = rounds.size();
        List<List<Map<String, Object>>> msgsByRound = new ArrayList<>(n);
        List<Integer> countByRound = new ArrayList<>(n);
        List<List<SessionTimelineVO.ToolCallItem>> callsByRound = new ArrayList<>(n);
        List<String> textOutByRound = new ArrayList<>(n);
        List<String> thinkByRound = new ArrayList<>(n);
        for (RoundListItem r : rounds) {
            Map<String, Object> p = payloads.get(Long.parseLong(r.traceId()));
            List<Map<String, Object>> msgs = parseMessages(p == null ? null : str(p.get("input_data")));
            JsonNode outNode = parseJsonObject(p == null ? null : str(p.get("output_data")));
            msgsByRound.add(msgs);
            countByRound.add(msgs.size());
            callsByRound.add(extractToolCalls(outNode));
            // 文本轮输出 = output 原文（markdown）；JSON 无 tool_calls 时取 content 字段兜底
            textOutByRound.add(outNode == null
                    ? (p == null ? null : blankToNull(str(p.get("output_data"))))
                    : blankToNull(outNode.path("content").asText(null)));
            thinkByRound.add(p == null ? null : blankToNull(str(p.get("think"))));
        }

        List<SessionTimelineVO.Event> events = new ArrayList<>();
        int seq = 0;
        int prevCount = -1;

        // ❓ 起点：首条 user 消息 + 首轮全量即其增量
        if (!msgsByRound.get(0).isEmpty()) {
            String userText = question;
            for (Map<String, Object> m : msgsByRound.get(0)) {
                if ("user".equals(m.get("role"))) {
                    userText = str(m.get("content"));
                    break;
                }
            }
            RoundListItem r0 = rounds.get(0);
            events.add(new SessionTimelineVO.Event(seq++, "user", r0.round(), r0.traceId(),
                    r0.success(), null, null, null, null, null, null,
                    msgsByRound.get(0).size(), r0.inputChars(), userText,
                    null, deltaMessages(msgsByRound.get(0), 0),
                    null, null, null, null, null, null, null, null, null,
                    null));
        }
        prevCount = countByRound.isEmpty() ? -1 : countByRound.get(0);

        for (int i = 0; i < n; i++) {
            RoundListItem r = rounds.get(i);
            boolean isAnswer = i == n - 1 && callsByRound.get(i).isEmpty() && textOutByRound.get(i) != null;
            String type = isAnswer ? "answer" : "llm";

            // 增量段 = 本轮 INPUT 尾部（上一轮条数之后）；上下文压缩导致变短时全量即增量
            int count = countByRound.get(i);
            int deltaFrom = prevCount < 0 || prevCount > count ? 0 : prevCount;
            List<SessionTimelineVO.DeltaMessage> delta = deltaMessages(msgsByRound.get(i), deltaFrom);

            // assistant 原话：文本轮 = output 原文；工具轮 = 下一轮增量段的 assistant 消息
            String assistantText = textOutByRound.get(i);
            if (assistantText == null && i + 1 < n) {
                int nextCount = countByRound.get(i + 1);
                int nextFrom = countByRound.get(i) > nextCount ? 0 : countByRound.get(i);
                for (int k = nextFrom; k < nextCount; k++) {
                    Map<String, Object> m = msgsByRound.get(i + 1).get(k);
                    if ("assistant".equals(m.get("role"))) {
                        assistantText = str(m.get("content"));
                        break;
                    }
                }
            }

            int llmSeq = seq;
            events.add(new SessionTimelineVO.Event(seq++, type, r.round(), r.traceId(),
                    r.success(), r.errorMessage(), r.durationMs(), r.promptTokens(), r.completionTokens(),
                    r.thinkDurationMs(), thinkByRound.get(i), count, r.inputChars(), assistantText,
                    callsByRound.get(i), delta,
                    null, null, null, null, null, null, null, null, null,
                    null));

            // 🔧 并发工具事件：结果在下一轮 INPUT 按 tool_call_id 配对；全文引用下轮 llm 事件 delta
            // 结果判定/预览前先解一层 JSON 字符串编码（tool content 历史上被双重编码，以 " 开头）
            List<SessionTimelineVO.ToolCallItem> calls = callsByRound.get(i);
            List<Map<String, Object>> nextMsgs = i + 1 < n ? msgsByRound.get(i + 1) : List.of();
            int nextLlmSeq = llmSeq + 1 + calls.size();
            for (SessionTimelineVO.ToolCallItem call : calls) {
                String result = null;
                for (Map<String, Object> m : nextMsgs) {
                    if ("tool".equals(m.get("role")) && call.callId() != null
                            && call.callId().equals(m.get("toolCallId"))) {
                        result = str(m.get("content"));
                        break;
                    }
                }
                String plain = unwrapJsonString(result);
                Boolean ok = result == null ? null : resultOk(plain);
                events.add(new SessionTimelineVO.Event(seq++, "tool", r.round(), r.traceId(),
                        r.success(), null, null, null, null, null, null,
                        null, null, null,
                        null, null,
                        llmSeq, call.callId(), call.name(), call.arguments(),
                        result == null ? null : abbreviate(plain, RESULT_PREVIEW_LEN),
                        result == null ? null : plain.length(),
                        ok,
                        Boolean.TRUE.equals(ok) && startsWithAny(plain, RESULT_EMPTY_HINTS),
                        result == null || i + 1 >= n ? null : nextLlmSeq,
                        call.callId() == null ? null : subByCallId.get(call.callId())));
            }
            prevCount = count;
        }
        return new BuiltTimeline(events, n);
    }

    /** 事件类型 ToolCallItem 便捷构造（解析 assistant 消息/tool_calls 输出共用） */
    private static List<SessionTimelineVO.ToolCallItem> extractToolCalls(JsonNode outNode) {
        if (outNode == null) {
            return List.of();
        }
        JsonNode calls = outNode.get("tool_calls");
        if (calls == null || !calls.isArray() || calls.isEmpty()) {
            return List.of();
        }
        List<SessionTimelineVO.ToolCallItem> out = new ArrayList<>();
        for (JsonNode c : calls) {
            out.add(new SessionTimelineVO.ToolCallItem(
                    c.path("id").asText(null),
                    c.path("name").asText(""),
                    c.path("arguments").isTextual() ? c.path("arguments").asText() : c.path("arguments").toString()));
        }
        return out;
    }

    /** INPUT 大字段 → 消息列表（OpenAI 风格行；解析失败/缺行返回空列表） */
    private List<Map<String, Object>> parseMessages(String inputData) {
        if (!hasText(inputData)) {
            return List.of();
        }
        JsonNode arr;
        try {
            arr = TIMELINE_MAPPER.readTree(inputData);
        } catch (Exception e) {
            log.warn("[monitor] timeline INPUT 解析失败（按缺行处理）: {}", e.getMessage());
            return List.of();
        }
        if (!arr.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode node : arr) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", node.path("role").asText(""));
            JsonNode content = node.get("content");
            m.put("content", content == null || content.isNull() ? ""
                    : content.isTextual() ? content.asText() : content.toString());
            m.put("name", node.path("name").asText(null));
            m.put("toolCallId", node.path("tool_call_id").asText(null));
            if ("assistant".equals(m.get("role")) && node.has("tool_calls")) {
                m.put("toolCalls", extractToolCalls(node));
            } else {
                m.put("toolCalls", List.of());
            }
            out.add(m);
        }
        return out;
    }

    /** 增量段 [from, size) → 增量消息（content 超限截断标记） */
    @SuppressWarnings("unchecked")
    private List<SessionTimelineVO.DeltaMessage> deltaMessages(List<Map<String, Object>> msgs, int from) {
        if (from >= msgs.size()) {
            return List.of();
        }
        List<SessionTimelineVO.DeltaMessage> out = new ArrayList<>();
        for (Map<String, Object> m : msgs.subList(from, msgs.size())) {
            String content = str(m.get("content"));
            boolean truncated = false;
            if (content.length() > DELTA_CONTENT_CAP) {
                content = content.substring(0, DELTA_CONTENT_CAP);
                truncated = true;
            }
            Object calls = m.get("toolCalls");
            out.add(new SessionTimelineVO.DeltaMessage(
                    str(m.get("role")), content,
                    str(m.get("name")), str(m.get("toolCallId")),
                    calls instanceof List ? (List<SessionTimelineVO.ToolCallItem>) calls : List.of(),
                    truncated));
        }
        return out;
    }

    /** 工具结果判定：❌/{"error" 开头 = 失败；其余成功 */
    private static boolean resultOk(String content) {
        String t = content.trim();
        return !t.startsWith("❌") && !t.startsWith("{\"error\"");
    }

    /** tool content 历史上被 JSON 字符串编码（"..." 包裹），判定/预览前解一层；非编码文本原样返回 */
    private static String unwrapJsonString(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        if (t.length() > 1 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
            try {
                JsonNode node = TIMELINE_MAPPER.readTree(t);
                if (node.isTextual()) {
                    return node.asText();
                }
            } catch (Exception ignored) {
                // 非 JSON 字符串字面量，按原文返回
            }
        }
        return value;
    }

    private static boolean startsWithAny(String text, List<String> prefixes) {
        String t = text == null ? "" : text.trim();
        return prefixes.stream().anyMatch(t::startsWith);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private JsonNode parseJsonObject(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            JsonNode node = TIMELINE_MAPPER.readTree(value);
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== T2.3 单轮明细 ====================

    /**
     * 单轮明细（payload 懒加载）。raw 取 payload 原文；rendered 复用 TraceQueryService
     * 契约渲染（includeTools 对齐其 tools 省略约定）。数据源 = monitor_trace + payload。
     */
    public RoundDetailVO roundDetail(long traceId, Boolean includeTools) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT t.id, t.session_id, t.conversation_id, t.round, t.round_type, t.tool_names, " +
                        "t.duration_ms, t.think_duration_ms, t.prompt_tokens, t.completion_tokens, t.success, " +
                        "t.error_message, t.input_chars, t.created_at, " +
                        "p.input_data, p.output_data, p.think " +
                        "FROM agentx_monitor_trace t LEFT JOIN agentx_trace_payload p ON p.trace_id = t.id " +
                        "WHERE t.id = ? LIMIT 1", traceId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> r = rows.get(0);
        String rendered = null;
        try {
            rendered = traceQueryService.roundDetail(str(r.get("session_id")),
                    ((Number) r.get("round")).intValue(), includeTools);
        } catch (Exception e) {
            log.warn("[monitor] 轮次渲染失败（返回 raw）: traceId={}, err={}", traceId, e.getMessage());
        }
        return new RoundDetailVO(
                String.valueOf(traceId),
                str(r.get("session_id")),
                str(r.get("conversation_id")),
                ((Number) r.get("round")).intValue(),
                str(r.get("round_type")),
                strList(r.get("tool_names")),
                intVal(r.get("success")) == 1,
                str(r.get("error_message")),
                longVal(r.get("duration_ms")),
                longObj(r.get("think_duration_ms")),
                longVal(r.get("prompt_tokens")),
                longVal(r.get("completion_tokens")),
                intObj(r.get("input_chars")),
                formatTs(ts(r.get("created_at"))),
                str(r.get("input_data")),
                str(r.get("output_data")),
                str(r.get("think")),
                rendered);
    }

    // ==================== T2.4 快捷搜索 ====================

    /**
     * 快捷查询（命令面板）：识别优先级 conversation_id 精确 → session_id 精确 → user_id →
     * question 模糊（提问维度，2026-11-19 评审反馈：模糊命中直接返回提问条目，
     * 问题原文 + 回答摘要，点击直达详情页定位该提问，最多 10 条建议）。
     */
    public List<SearchItem> search(String q) {
        if (!hasText(q)) {
            return List.of();
        }
        String query = q.trim();
        List<SearchItem> out = new ArrayList<>();

        // 1. conversation_id 精确命中（窗口条目）
        List<Map<String, Object>> convHits = jdbc.queryForList(
                "SELECT conversation_id FROM agentx_monitor_conversation WHERE conversation_id = ? LIMIT 1", query);
        for (Map<String, Object> row : convHits) {
            out.add(convSearchItem(String.valueOf(row.get("conversation_id"))));
        }

        // 2. session_id 精确命中（提问条目，深链直达该提问）
        List<Map<String, Object>> sessHits = jdbc.queryForList(
                "SELECT conversation_id, session_id, question, answer, status " +
                        "FROM agentx_monitor_session WHERE session_id = ? LIMIT 1", query);
        for (Map<String, Object> row : sessHits) {
            out.add(SearchItem.sessionHit(str(row.get("conversation_id")), str(row.get("session_id")),
                    abbreviate(str(row.get("question")), 60), abbreviate(str(row.get("answer")), 100),
                    str(row.get("status"))));
        }

        // 3. user_id 精确命中（近 7 天提问数 / 失败提问数）；用户项与其它项可共存
        boolean userHit = !jdbc.queryForList(
                "SELECT user_id FROM agentx_monitor_session WHERE user_id = ? LIMIT 1", query).isEmpty();
        if (userHit) {
            Timestamp since7d = Timestamp.valueOf(LocalDateTime.now().minusDays(7));
            Long conv7d = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agentx_monitor_session " +
                            "WHERE user_id = ? AND created_at >= ?", Long.class, query, since7d);
            Long failConv7d = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agentx_monitor_session " +
                            "WHERE user_id = ? AND COALESCE(fail_rounds, 0) > 0 AND created_at >= ?",
                    Long.class, query, since7d);
            out.add(SearchItem.user(query, conv7d == null ? 0 : conv7d, failConv7d == null ? 0 : failConv7d));
        }

        // 4. question 模糊建议（未命中精确项才给；一次提问一条，点击直达该提问）
        if (out.isEmpty()) {
            String like = "%" + query.toLowerCase() + "%";
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT conversation_id, session_id, question, answer, status " +
                            "FROM agentx_monitor_session WHERE LOWER(question) LIKE ? " +
                            "ORDER BY created_at DESC LIMIT " + SEARCH_SUGGEST_LIMIT, like);
            for (Map<String, Object> row : rows) {
                out.add(SearchItem.sessionHit(str(row.get("conversation_id")), str(row.get("session_id")),
                        abbreviate(str(row.get("question")), 60), abbreviate(str(row.get("answer")), 100),
                        str(row.get("status"))));
            }
        }
        return out;
    }

    /** 会话窗口搜索项（窗口物化聚合列，命令面板副标题展示） */
    private SearchItem convSearchItem(String convId) {
        List<Map<String, Object>> convs = jdbc.queryForList(
                "SELECT user_id, title, status, question_count, total_rounds, fail_rounds, started_at, last_active_at " +
                        "FROM agentx_monitor_conversation WHERE conversation_id = ?", convId);
        if (convs.isEmpty()) {
            return SearchItem.conversation(convId, null, null, 0, 0, null, null);
        }
        Map<String, Object> c = convs.get(0);
        return SearchItem.conversation(convId,
                abbreviate(str(c.get("title")), 60),
                str(c.get("status")),
                longVal(c.get("total_rounds")), longVal(c.get("fail_rounds")),
                formatTs(ts(c.get("started_at"))), formatTs(ts(c.get("last_active_at"))));
    }

    // ==================== T2.5 总览 ====================

    /** 总览：KPI + 趋势查 metric 表（含写失败计数）；失败 TOP / 会话 TOP / 进行中查监控自有表 */
    public OverviewVO overview(String range) {
        Duration window = switch (range == null ? "1h" : range) {
            case "10m" -> Duration.ofMinutes(10);
            case "30m" -> Duration.ofMinutes(30);
            case "1h" -> Duration.ofHours(1);
            case "7d" -> Duration.ofDays(7);
            default -> Duration.ofHours(24);
        };
        Timestamp since = Timestamp.valueOf(LocalDateTime.now().minus(window));

        // 趋势：5 分钟桶全部指标一次取回，Java 侧透视（桶缺失补零，断线仅出现在 perf 无轮次处）
        List<Map<String, Object>> metricRows = jdbc.queryForList(
                "SELECT minute, metric, SUM(value) AS value FROM agentx_monitor_metric " +
                        "WHERE minute >= ? AND metric IN " +
                        "('convs','rounds','fail_rounds','error_convs','avg_ms','p95_ms','prompt_tokens','completion_tokens','trace_write_failures') " +
                        "GROUP BY minute, metric ORDER BY minute ASC", since);
        Map<String, Map<String, Double>> byMinute = new LinkedHashMap<>();
        for (Map<String, Object> row : metricRows) {
            byMinute.computeIfAbsent(formatMinute(ts(row.get("minute"))), k -> new LinkedHashMap<>())
                    .put(str(row.get("metric")), ((Number) row.get("value")).doubleValue());
        }
        double sumConvs = 0, sumRounds = 0, sumFail = 0, sumErrConvs = 0, sumP95 = 0, p95Buckets = 0;
        double sumPrompt = 0, sumCompletion = 0, sumWriteFailures = 0;
        List<OverviewVO.TrendPoint> trend = new ArrayList<>();
        List<OverviewVO.PerfPoint> perf = new ArrayList<>();
        for (Map.Entry<String, Map<String, Double>> e : byMinute.entrySet()) {
            Map<String, Double> m = e.getValue();
            double convs = m.getOrDefault("convs", 0d);
            double rounds = m.getOrDefault("rounds", 0d);
            double fail = m.getOrDefault("fail_rounds", 0d);
            Double p95 = m.get("p95_ms");
            sumConvs += convs;
            sumRounds += rounds;
            sumFail += fail;
            sumErrConvs += m.getOrDefault("error_convs", 0d);
            sumPrompt += m.getOrDefault("prompt_tokens", 0d);
            sumCompletion += m.getOrDefault("completion_tokens", 0d);
            sumWriteFailures += m.getOrDefault(MonitorTraceCollector.METRIC_WRITE_FAILURES, 0d);
            if (p95 != null) {
                sumP95 += p95;
                p95Buckets++;
            }
            trend.add(new OverviewVO.TrendPoint(e.getKey(), (long) convs, (long) rounds, (long) fail));
            perf.add(new OverviewVO.PerfPoint(e.getKey(),
                    rounds > 0 ? fail / rounds : null, p95));
        }
        OverviewVO.Kpi kpi = new OverviewVO.Kpi((long) sumConvs, (long) sumRounds, (long) sumFail,
                sumRounds > 0 ? 1 - sumFail / sumRounds : null,
                (long) sumErrConvs, p95Buckets > 0 ? sumP95 / p95Buckets : null,
                (long) sumPrompt, (long) sumCompletion, (long) sumWriteFailures);

        return new OverviewVO(range, kpi, trend, perf, topFailed(since),
                topConversations(since, "EXTRACT(EPOCH FROM (c.last_active_at - c.started_at)) * 1000", "total_ms"),
                topConversations(since, "c.total_rounds", "rounds"),
                topConversations(since, "COALESCE(c.prompt_tokens, 0) + COALESCE(c.completion_tokens, 0)", "tokens"),
                runningSessions());
    }

    /**
     * 会话 TOP 10（三联看板共用查询：耗时/轮数/token 按窗口内指标倒序）。
     * 数据源 = monitor_conversation 物化聚合列（零运行时聚合，替代旧 trace 全表 GROUP BY）。
     */
    private List<OverviewVO.TopSessionItem> topConversations(Timestamp since, String orderExpr, String metricKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT c.conversation_id, c.title AS question, " +
                        "EXTRACT(EPOCH FROM (c.last_active_at - c.started_at)) * 1000 AS total_ms, " +
                        "COALESCE(c.total_rounds, 0) AS rounds, " +
                        "COALESCE(c.prompt_tokens, 0) + COALESCE(c.completion_tokens, 0) AS tokens " +
                        "FROM agentx_monitor_conversation c WHERE c.last_active_at >= ? " +
                        "ORDER BY " + orderExpr + " DESC LIMIT 10", since);
        return rows.stream()
                .map(r -> new OverviewVO.TopSessionItem(
                        str(r.get("conversation_id")), str(r.get("question")), longVal(r.get(metricKey))))
                .toList();
    }

    /** 失败提问 TOP 10（提问维度：monitor_session 失败行倒序；first_error 免点开直出） */
    private List<OverviewVO.TopFailedItem> topFailed(Timestamp since) {
        List<Map<String, Object>> failed = jdbc.queryForList(
                "SELECT s.conversation_id, s.session_id, s.user_id, s.question, s.status, " +
                        "s.fail_rounds, s.first_error, s.duration_ms, " +
                        "COALESCE(s.completed_at, s.created_at) AS last_at, " +
                        "(SELECT MIN(te.round) FROM agentx_monitor_trace te " +
                        "  WHERE te.session_id = s.session_id AND te.success = 0) AS first_failed_round " +
                        "FROM agentx_monitor_session s " +
                        "WHERE COALESCE(s.fail_rounds, 0) > 0 AND s.created_at >= ? " +
                        "ORDER BY s.fail_rounds DESC, s.created_at DESC LIMIT 10", since);
        List<OverviewVO.TopFailedItem> items = new ArrayList<>();
        for (Map<String, Object> f : failed) {
            items.add(new OverviewVO.TopFailedItem(
                    str(f.get("conversation_id")),
                    str(f.get("session_id")),
                    str(f.get("user_id")),
                    abbreviate(str(f.get("question")), 60),
                    str(f.get("status")),
                    longVal(f.get("fail_rounds")),
                    f.get("first_failed_round") == null ? null : ((Number) f.get("first_failed_round")).intValue(),
                    abbreviate(str(f.get("first_error")), 120),
                    longObj(f.get("duration_ms")),
                    formatTs(ts(f.get("last_at")))));
        }
        return items;
    }

    // ==================== T2.6 卡死会话与运维操作 ====================

    /**
     * 进行中提问（一次提问一行，仅 running，最多 50 条）：超阈值标 stuck（卡死检测）。
     * 数据源 = monitor_session（一问一行，比旧口径按窗口更精确）。
     */
    public List<OverviewVO.RunningSession> runningSessions() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT conversation_id, session_id, user_id, question, created_at " +
                        "FROM agentx_monitor_session WHERE status = 'running' ORDER BY created_at ASC LIMIT 50");
        List<OverviewVO.RunningSession> items = new ArrayList<>();
        long stuckMs = stuckThresholdMinutes * 60_000L;
        for (Map<String, Object> row : rows) {
            Timestamp started = ts(row.get("created_at"));
            long runningMs = started == null ? 0 : System.currentTimeMillis() - started.getTime();
            Long rounds = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agentx_monitor_trace WHERE session_id = ?",
                    Long.class, str(row.get("session_id")));
            items.add(new OverviewVO.RunningSession(str(row.get("conversation_id")), str(row.get("user_id")),
                    abbreviate(str(row.get("question")), 60), formatTs(started),
                    runningMs, rounds == null ? 0 : rounds, runningMs >= stuckMs));
        }
        return items;
    }

    /**
     * 运维操作：running 提问置为 interrupted（二次确认由前端承担）；窗口聚合态同步刷新。
     */
    public boolean interrupt(String conversationId) {
        int updated = jdbc.update(
                "UPDATE agentx_monitor_session SET status = 'interrupted', completed_at = CURRENT_TIMESTAMP " +
                        "WHERE conversation_id = ? AND status = 'running'",
                conversationId);
        if (updated > 0) {
            jdbc.update("""
                    UPDATE agentx_monitor_conversation c SET
                      status = (SELECT CASE WHEN COUNT(*) FILTER (WHERE s.status = 'running') > 0 THEN 'running'
                                            WHEN COUNT(*) FILTER (WHERE s.status = 'error') > 0 THEN 'error'
                                            WHEN COUNT(*) FILTER (WHERE s.status = 'interrupted') > 0 THEN 'interrupted'
                                            ELSE 'completed' END
                                 FROM agentx_monitor_session s WHERE s.conversation_id = c.conversation_id),
                      fail_rounds = (SELECT COALESCE(SUM(COALESCE(s.fail_rounds, 0)), 0)
                                     FROM agentx_monitor_session s WHERE s.conversation_id = c.conversation_id),
                      last_active_at = CURRENT_TIMESTAMP
                    WHERE c.conversation_id = ?
                    """, conversationId);
        }
        log.info("[monitor] 置为 interrupted: convId={}, updated={}", conversationId, updated);
        return updated > 0;
    }

    // ==================== 共用小工具 ====================

    private RoundListItem roundRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RoundListItem(
                String.valueOf(rs.getLong("id")),
                rs.getString("session_id"),
                rs.getInt("round"),
                rs.getString("round_type"),
                strList(rs.getArray("tool_names")),
                rs.getLong("prompt_tokens"),
                rs.getLong("completion_tokens"),
                rs.getLong("duration_ms"),
                rs.getObject("think_duration_ms") == null ? null : rs.getLong("think_duration_ms"),
                rs.getInt("success") == 1,
                rs.getString("error_message"),
                rs.getObject("input_chars") == null ? null : rs.getInt("input_chars"),
                formatTs(rs.getTimestamp("created_at")),
                rs.getString("agent_code"),
                rs.getString("parent_session_id"));
    }

    private static long wallMs(Timestamp started, Timestamp lastActive) {
        if (started == null || lastActive == null) {
            return 0;
        }
        return Math.max(0, lastActive.getTime() - started.getTime());
    }

    private static String formatMinute(Timestamp minute) {
        return minute == null ? "" : minute.toLocalDateTime().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
    }

    /** 解析前端时间参数（yyyy-MM-dd HH:mm:ss）；isStart 决定缺省时分秒补 00:00:00 / 23:59:59 */
    private static Timestamp parseTs(String value, boolean isStart) {
        if (!hasText(value)) {
            return null;
        }
        try {
            String v = value.trim();
            if (v.length() == 10) {  // 纯日期
                v = v + (isStart ? " 00:00:00" : " 23:59:59");
            }
            return Timestamp.valueOf(LocalDateTime.parse(v, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> strList(Object value) {
        if (value == null) {
            return List.of();
        }
        try {
            java.sql.Array arr = (java.sql.Array) value;
            String[] raw = (String[]) arr.getArray();
            return raw == null ? List.of() : List.of(raw);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Timestamp ts(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp t) {
            return t;
        }
        if (value instanceof java.sql.Date d) {
            return new Timestamp(d.getTime());
        }
        return Timestamp.valueOf(String.valueOf(value).replace('T', ' '));
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int intVal(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static Integer intObj(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static long longVal(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static Long longObj(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static String formatTs(Timestamp ts) {
        return ts == null ? null : TS.format(ts.toLocalDateTime());
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return null;
        }
        String oneLine = text.replace("\r", " ").replace("\n", " ").trim();
        return oneLine.length() > max ? oneLine.substring(0, max) + "…" : oneLine;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
