package com.llmetor.dataagent.dataagent.monitor.detect;

import com.llmetor.dataagent.dataagent.monitor.dto.DetectRuleView;
import com.llmetor.dataagent.dataagent.monitor.dto.DetectSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 异常检测服务（Step 3 主流程，03 方案 §4）：一次提问一次检测——
 * ① 读 session 行 + trace 瘦行（一次 SQL 按 round 排序）；
 * ② 惰性取 payload（toolCalls / 次轮观察，观察在 SQL 侧截前 2000 字符不整拉大字段）；
 * ③ 规则逐条 eval（配置表开关+阈值，缺行回退代码默认）；
 * ④ RiskStore.replace 幂等落库 + 回填 session 检测列。
 *
 * <p>入口三处：closeSession 后 DetectDispatcher 异步、DetectSweepJob 每 5 分钟兜底、
 * 详情页「重新检测」POST /detect 同步。yaml 总开关 = 进程级熔断（关闭时三处全部跳过）。
 */
@Slf4j
@Service
public class AnomalyDetectService {

    private final JdbcTemplate jdbc;
    private final RiskStore riskStore;
    private final DetectRuleRegistry registry;
    /** yaml 总开关（data-agent.monitor.detect.enabled，进程级熔断） */
    private final boolean globalEnabled;

    public AnomalyDetectService(DataSource dataSource,
                                RiskStore riskStore,
                                DetectRuleRegistry registry,
                                @Value("${data-agent.monitor.detect.enabled:true}") boolean globalEnabled) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.riskStore = riskStore;
        this.registry = registry;
        this.globalEnabled = globalEnabled;
        // 首次启动 seed 全量规则行（缺行才插；表缺/库不可达吞掉，检测运行期回退代码默认）
        try {
            DetectConfig.seed(jdbc, registry.rules());
        } catch (Exception e) {
            log.warn("[detect] 规则配置 seed 失败（启动继续）: {}", e.getMessage());
        }
    }

    public boolean globalEnabled() {
        return globalEnabled;
    }

    /**
     * 同步检测一问（<1s 量级；异步/兜底/手动共用）。
     *
     * @throws IllegalStateException 提问进行中 / 提问不存在 / 总开关关闭（调用方转业务提示）
     */
    public DetectSummary detect(String sessionId) {
        if (!globalEnabled) {
            throw new IllegalStateException("检测总开关已关闭（data-agent.monitor.detect.enabled=false）");
        }
        // ① session 行
        List<Map<String, Object>> sessRows = jdbc.queryForList(
                "SELECT conversation_id, session_id, user_id, question, answer, status, rounds " +
                        "FROM agentx_monitor_session WHERE session_id = ?", sessionId);
        if (sessRows.isEmpty()) {
            throw new IllegalStateException("提问不存在: " + sessionId);
        }
        Map<String, Object> sess = sessRows.get(0);
        String status = str(sess.get("status"));
        if ("running".equals(status)) {
            throw new IllegalStateException("提问进行中，终态后才能检测");
        }
        String conversationId = str(sess.get("conversation_id"));

        // ② 轮次瘦行（一次 SQL 按 round 排序）
        List<DetectContext.TraceRow> rounds = jdbc.query(
                "SELECT id, round, round_type, tool_names, success, error_message, duration_ms " +
                        "FROM agentx_monitor_trace WHERE session_id = ? ORDER BY round ASC",
                (rs, i) -> new DetectContext.TraceRow(
                        rs.getLong("id"),
                        rs.getInt("round"),
                        rs.getString("round_type"),
                        strList(rs.getArray("tool_names")),
                        rs.getInt("success") == 1,
                        rs.getString("error_message"),
                        rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms")),
                sessionId);

        // ③ 配置（每问读一次配置表，缺行回退代码默认）+ 上下文（payload 惰性；
        //    V2-D2：观察截断 obsTruncateChars 默认 2000→8000，TOOL-001 全文兜底扫描有数据基础）
        DetectConfig config = DetectConfig.load(jdbc, globalEnabled, registry.rules());
        DetectContext ctx = new DetectContext(
                new DetectContext.SessionRow(sessionId, conversationId,
                        str(sess.get("user_id")), str(sess.get("question")),
                        str(sess.get("answer")), status,
                        sess.get("rounds") == null ? null : ((Number) sess.get("rounds")).intValue()),
                rounds, payloadAccessor(config.intParam("TOOL-001", "obsTruncateChars", 8000)), config);

        // ④ 规则逐条 eval（单规则失败跳过，不影响其余）
        List<RiskHit> allHits = new ArrayList<>();
        for (DetectRule rule : registry.rules()) {
            if (!rule.enabled(config)) {
                continue;
            }
            try {
                List<RiskHit> hits = rule.eval(ctx);
                ctx.addHits(rule.id(), hits);
                allHits.addAll(hits);
            } catch (Exception e) {
                log.warn("[detect] 规则执行失败（跳过）: sessionId={}, ruleId={}, err={}",
                        sessionId, rule.id(), e.getMessage());
            }
        }

        // ⑤ 幂等落库 + 回填（异常置 failed 由 SweepJob 下轮重试，不产生半截风险行）
        try {
            RiskStore.ReplaceResult result = riskStore.replace(conversationId, sessionId,
                    allHits, registry, DetectRuleRegistry.VERSION);
            log.info("[detect] 检测完成: sessionId={}, hits={}, high={}, version={}",
                    sessionId, result.riskTotal(), result.riskHigh(), DetectRuleRegistry.VERSION);
            return new DetectSummary(sessionId, "done", result.riskTotal(), result.riskHigh(),
                    result.firstRisk(), DetectRuleRegistry.VERSION);
        } catch (Exception e) {
            log.warn("[detect] 风险落库失败（置 failed 待兜底重试）: sessionId={}, err={}",
                    sessionId, e.getMessage(), e);
            riskStore.markFailed(sessionId);
            throw new IllegalStateException("风险落库失败: " + e.getMessage(), e);
        }
    }

    // ==================== T5.5 规则轻量 web 管理 ====================

    /** 规则注册表视图（弹层数据源）：合并配置表行与代码默认 */
    public List<DetectRuleView> rulesView() {
        Map<String, Object[]> rows;
        try {
            rows = readRawRows();
        } catch (Exception e) {
            log.warn("[detect] 规则配置表读取失败（按代码默认展示）: {}", e.getMessage());
            rows = Map.of();
        }
        List<DetectRuleView> out = new ArrayList<>();
        for (DetectRule rule : registry.rules()) {
            Map<String, Object> merged = new LinkedHashMap<>(rule.defaultParams());
            boolean enabled = rule.defaultEnabled();
            String remark = null;
            String updatedBy = null;
            String updatedAt = null;
            Object[] row = rows.get(rule.id());
            if (row != null) {
                enabled = Boolean.TRUE.equals(row[0]);
                Map<String, Object> rowParams = DetectConfig.parseParams(row[1]);
                if (rowParams != null) {
                    merged.putAll(rowParams);
                }
                remark = (String) row[4];
                updatedBy = (String) row[2];
                updatedAt = row[3] == null ? null : String.valueOf(row[3]);
            }
            out.add(new DetectRuleView(rule.id(), rule.title(), rule.severity(), rule.description(), remark,
                    enabled, merged, rule.defaultParams(), rule.defaultEnabled(), updatedBy, updatedAt));
        }
        return out;
    }

    /**
     * 改单条规则开关/阈值/备注（D7：落配置表即时生效，影响后续检测，历史命中不自动重算）。
     * 校验：规则 ID 必须在注册表；params 键必须都在代码默认参数内（防手滑拼错永远不生效）。
     * remark 仅人工维护说明（清空传 ""）；传 null 视为不改（保留原值）。
     * updated_by 留痕可审计（管理入口 adminOnly 由前端路由 meta 控制，同评测报告页约定）。
     *
     * @return 合并后的配置（供回显）
     */
    public DetectRuleView updateRule(String ruleId, Boolean enabled, Map<String, Object> params, String remark) {
        DetectRule rule = registry.byId(ruleId);
        if (rule == null) {
            throw new IllegalArgumentException("未知规则: " + ruleId);
        }
        if (params != null) {
            Set<String> known = rule.defaultParams().keySet();
            for (String key : params.keySet()) {
                if (!known.contains(key)) {
                    throw new IllegalArgumentException("未知参数: " + ruleId + "." + key + "（合法参数: " + known + "）");
                }
            }
        }
        Map<String, Object[]> rows;
        try {
            rows = readRawRows();
        } catch (Exception e) {
            rows = Map.of();
        }
        boolean newEnabled = enabled != null ? enabled
                : (rows.containsKey(ruleId) ? Boolean.TRUE.equals(rows.get(ruleId)[0]) : rule.defaultEnabled());
        String newParamsJson;
        try {
            // 只存传入的增量键（ON CONFLICT 时 jsonb || 合并保留未提及键；INSERT 缺键走代码默认）
            newParamsJson = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(params != null ? params : Map.of());
        } catch (Exception e) {
            throw new IllegalArgumentException("参数序列化失败: " + e.getMessage());
        }
        try {
            jdbc.update("""
                    INSERT INTO agentx_monitor_detect_rule (rule_id, enabled, params, remark, updated_by, updated_at)
                    VALUES (?, ?, ?::jsonb, ?, 'admin', CURRENT_TIMESTAMP)
                    ON CONFLICT (rule_id) DO UPDATE SET
                      enabled = EXCLUDED.enabled, params = agentx_monitor_detect_rule.params || EXCLUDED.params,
                      remark = COALESCE(EXCLUDED.remark, agentx_monitor_detect_rule.remark),
                      updated_by = EXCLUDED.updated_by, updated_at = CURRENT_TIMESTAMP
                    """, ruleId, newEnabled, newParamsJson, remark);
        } catch (Exception e) {
            throw new IllegalStateException("规则配置写入失败: " + e.getMessage(), e);
        }
        log.info("[detect] 规则配置更新: ruleId={}, enabled={}, params={}, remark={}, updatedBy=admin",
                ruleId, newEnabled, newParamsJson, remark);
        return rulesView().stream().filter(v -> v.ruleId().equals(ruleId)).findFirst().orElse(null);
    }

    /** 原始配置行：rule_id → [enabled, params, updated_by, updated_at, remark] */
    private Map<String, Object[]> readRawRows() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT rule_id, enabled, params, updated_by, updated_at, remark FROM agentx_monitor_detect_rule");
        Map<String, Object[]> out = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            out.put(str(row.get("rule_id")), new Object[]{row.get("enabled"), row.get("params"),
                    str(row.get("updated_by")), row.get("updated_at") instanceof Timestamp t ? t.toString() : row.get("updated_at"),
                    row.get("remark")});
        }
        return out;
    }

    // ==================== payload 惰性访问器（SQL 侧截取，不整拉大字段） ====================

    /**
     * 生产 payload 访问器：
     * <ul>
     *   <li>toolCalls：读 output_data（只有工具轮会取；text 轮 output 是回答文本不碰）</li>
     *   <li>toolObservations：次轮 input 的「末个带 tool_calls 的 assistant 之后」的 role=tool
     *       消息（尾段=本轮观察），name 截 100 / content 截 obsTruncateChars（V2 默认 8000），SQL 侧完成</li>
     * </ul>
     * 读取异常由 DetectContext.safeCalls/safeObs 吞掉返回空（漏报不误报）。
     */
    private DetectContext.PayloadAccessor payloadAccessor(int obsTruncateChars) {
        return new DetectContext.PayloadAccessor() {
            @Override
            public List<DetectContext.ToolCallView> toolCalls(long traceId) {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT output_data FROM agentx_trace_payload WHERE trace_id = ?", traceId);
                if (rows.isEmpty()) {
                    return List.of();
                }
                Object raw = rows.get(0).get("output_data");
                // PGobject.toString() 即 JSON 原文（驱动是 runtime 依赖，不引编译期类型）
                String json = String.valueOf(raw);
                try {
                    com.fasterxml.jackson.databind.JsonNode node =
                            new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
                    com.fasterxml.jackson.databind.JsonNode calls = node.get("tool_calls");
                    if (calls == null || !calls.isArray() || calls.isEmpty()) {
                        return List.of();
                    }
                    List<DetectContext.ToolCallView> out = new ArrayList<>();
                    for (com.fasterxml.jackson.databind.JsonNode call : calls) {
                        out.add(new DetectContext.ToolCallView(
                                call.path("name").asText(""),
                                call.path("arguments").asText("{}")));
                    }
                    return out;
                } catch (Exception e) {
                    return List.of();
                }
            }

            @Override
            public List<DetectContext.ToolObservation> toolObservations(long traceId) {
                // 尾段 role=tool 消息 = 本轮决策的观察（避开 input 里累积的历史 tool 消息重复命中）
                String sql = """
                        WITH arr AS (
                          SELECT t.m, t.ord
                          FROM agentx_trace_payload p
                          CROSS JOIN LATERAL jsonb_array_elements(p.input_data::jsonb) WITH ORDINALITY AS t(m, ord)
                          WHERE p.trace_id = ?
                        ), last_calls AS (
                          SELECT COALESCE(MAX(ord), 0) AS ord FROM arr
                          WHERE m->>'role' = 'assistant' AND jsonb_typeof(m->'tool_calls') = 'array'
                        )
                        SELECT substring(a.m->>'name', 1, 100) AS name,
                               substring(a.m->>'content', 1, %d) AS content
                        FROM arr a CROSS JOIN last_calls lc
                        WHERE a.ord > lc.ord AND a.m->>'role' = 'tool'
                        ORDER BY a.ord
                        """.formatted(Math.max(2000, obsTruncateChars));
                List<Map<String, Object>> rows = jdbc.queryForList(sql, traceId);
                List<DetectContext.ToolObservation> out = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    out.add(new DetectContext.ToolObservation(str(row.get("name")), str(row.get("content"))));
                }
                return out;
            }
        };
    }

    // ==================== 小工具 ====================

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
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
}
