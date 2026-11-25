package com.llmetor.dataagent.dataagent.monitor.detect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmetor.dataagent.dataagent.monitor.dto.RiskItem;
import com.llmetor.miniagentx.utils.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 风险落库（03 方案 §4.1，T4）：检测幂等 = 事务内 DELETE 该 session 旧风险 → INSERT 命中行
 * → 回填 session 检测列（detect_status/risk_total/risk_high/first_risk/detector_version）。
 * 失败不产生半截风险行；检测异常由调用方置 detect_status='failed' 待 SweepJob 重试。
 *
 * <p>建表与 sql/trace-monitor/V06 同构（启动幂等执行，V06 供 DBA 审核提前执行）。
 */
@Slf4j
@Service
public class RiskStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;

    public RiskStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        DefaultTransactionDefinition txDef = new DefaultTransactionDefinition();
        txDef.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.txTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource), txDef);
        ensureSchema();
    }

    /** 幂等建表/加列（与 V06__monitor_risk.sql 同构） */
    public void ensureSchema() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS agentx_monitor_risk (
                      id              bigint PRIMARY KEY,
                      conversation_id varchar(100) NOT NULL,
                      session_id      varchar(100) NOT NULL,
                      rule_id         varchar(20)  NOT NULL,
                      severity        varchar(10)  NOT NULL,
                      round           int,
                      trace_id        bigint,
                      rounds_hit      varchar(100),
                      title           varchar(200) NOT NULL,
                      detail          text,
                      evidence        jsonb,
                      created_at      timestamp DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_amr_session ON agentx_monitor_risk (session_id)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_amr_conv    ON agentx_monitor_risk (conversation_id, created_at DESC)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_amr_rule    ON agentx_monitor_risk (rule_id, severity)");
            jdbc.execute("ALTER TABLE agentx_monitor_session ADD COLUMN IF NOT EXISTS detect_status    varchar(20)");
            jdbc.execute("ALTER TABLE agentx_monitor_session ADD COLUMN IF NOT EXISTS risk_total       int  DEFAULT 0");
            jdbc.execute("ALTER TABLE agentx_monitor_session ADD COLUMN IF NOT EXISTS risk_high        int  DEFAULT 0");
            jdbc.execute("ALTER TABLE agentx_monitor_session ADD COLUMN IF NOT EXISTS first_risk       text");
            jdbc.execute("ALTER TABLE agentx_monitor_session ADD COLUMN IF NOT EXISTS detector_version varchar(10)");
            try {
                jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ams_undetected ON agentx_monitor_session (created_at) " +
                        "WHERE detect_status IS NULL OR detect_status <> 'done'");
            } catch (Exception e) {
                log.warn("[detect] undetected 部分索引创建失败（不影响启动）: {}", e.getMessage());
            }
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS agentx_monitor_detect_rule (
                      rule_id     varchar(20) PRIMARY KEY,
                      enabled     boolean NOT NULL DEFAULT true,
                      params      jsonb NOT NULL DEFAULT '{}',
                      updated_by  varchar(100),
                      updated_at  timestamp DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        } catch (Exception e) {
            // 数据库暂不可达等：不阻断启动，检测链路运行期再暴露（自吞异常纪律）
            log.warn("[detect] 风险表结构初始化失败（启动继续）: {}", e.getMessage());
        }
    }

    /** replace 结果（回填 session 列的计数） */
    public record ReplaceResult(int riskTotal, int riskHigh, String firstRisk) {
    }

    /**
     * 幂等覆盖：DELETE 旧风险 → INSERT 命中行 → 回填 session 检测列。
     * title 由 registry 反查（RiskHit 不携带）；无命中也执行（清零 = 「已检测无风险」）。
     */
    public ReplaceResult replace(String conversationId, String sessionId,
                                 List<RiskHit> hits, DetectRuleRegistry registry, String version) {
        List<RiskHit> safeHits = hits == null ? List.of() : hits;
        return txTemplate.execute(txStatus -> {
            jdbc.update("DELETE FROM agentx_monitor_risk WHERE session_id = ?", sessionId);
            for (RiskHit hit : safeHits) {
                jdbc.update("""
                        INSERT INTO agentx_monitor_risk
                          (id, conversation_id, session_id, rule_id, severity, round, trace_id,
                           rounds_hit, title, detail, evidence)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                        """, (PreparedStatement ps) -> {
                    int i = 1;
                    ps.setLong(i++, SnowflakeIdGenerator.nextId());
                    ps.setString(i++, conversationId);
                    ps.setString(i++, sessionId);
                    ps.setString(i++, hit.ruleId());
                    ps.setString(i++, hit.severity());
                    if (hit.round() != null) {
                        ps.setInt(i++, hit.round());
                    } else {
                        ps.setNull(i++, java.sql.Types.INTEGER);
                    }
                    if (hit.traceId() != null) {
                        ps.setLong(i++, hit.traceId());
                    } else {
                        ps.setNull(i++, java.sql.Types.BIGINT);
                    }
                    ps.setString(i++, hit.roundsHit());
                    ps.setString(i++, registry.titleOf(hit.ruleId()));
                    ps.setString(i++, hit.detail());
                    ps.setString(i++, hit.evidenceJson() == null ? "{}" : hit.evidenceJson());
                });
            }
            int high = 0;
            for (RiskHit hit : safeHits) {
                if ("high".equals(hit.severity())) {
                    high++;
                }
            }
            String firstRisk = firstRiskOf(safeHits, registry);
            jdbc.update("""
                    UPDATE agentx_monitor_session SET
                      detect_status = 'done', risk_total = ?, risk_high = ?,
                      first_risk = ?, detector_version = ?
                    WHERE session_id = ?
                    """, safeHits.size(), high, firstRisk, version, sessionId);
            return new ReplaceResult(safeHits.size(), high, firstRisk);
        });
    }

    /** 检测异常置 failed（SweepJob 下轮重试）；自吞异常不外抛 */
    public void markFailed(String sessionId) {
        try {
            jdbc.update("UPDATE agentx_monitor_session SET detect_status = 'failed' WHERE session_id = ?",
                    sessionId);
        } catch (Exception e) {
            log.warn("[detect] detect_status 置 failed 失败: sessionId={}, err={}", sessionId, e.getMessage());
        }
    }

    /**
     * 风险列表（详情页检测区域数据源）：severity 降序（high→medium→low）→ round 升序（NULL 最后）。
     *
     * <p>子会话穿透（M-TT，§3.3）：本会话风险 + 全部子会话风险一次返回（检测兜底任务对
     * 子会话终态同样落风险行）；RiskItem 带 sessionId/agentCode（join agentx_monitor_session），
     * 前端打「子Agent」徽标并按 ${subSessionId}:${round} 挂到子事件上。根会话风险排前面。
     */
    public List<RiskItem> listBySession(String sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.rule_id, r.severity, r.round, r.trace_id, r.rounds_hit, r.title, r.detail,
                       r.evidence, r.created_at, r.session_id AS risk_session_id, s.agent_code
                FROM agentx_monitor_risk r
                LEFT JOIN agentx_monitor_session s ON s.session_id = r.session_id
                WHERE r.session_id = ?
                   OR r.session_id IN (SELECT session_id FROM agentx_monitor_session WHERE parent_session_id = ?)
                ORDER BY CASE r.severity WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END ASC,
                         CASE WHEN r.session_id = ? THEN 0 ELSE 1 END ASC,
                         r.round ASC NULLS LAST, r.id ASC
                """, sessionId, sessionId, sessionId);
        List<RiskItem> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            out.add(new RiskItem(
                    str(row.get("rule_id")),
                    str(row.get("severity")),
                    row.get("round") == null ? null : ((Number) row.get("round")).intValue(),
                    row.get("trace_id") == null ? null : String.valueOf(((Number) row.get("trace_id")).longValue()),
                    parseRoundsHit(str(row.get("rounds_hit"))),
                    str(row.get("title")),
                    str(row.get("detail")),
                    parseEvidence(row.get("evidence")),
                    row.get("created_at") == null ? null : String.valueOf(row.get("created_at")),
                    str(row.get("risk_session_id")),
                    str(row.get("agent_code"))));
        }
        return out;
    }

    // ==================== 小工具 ====================

    /** first_risk：最高级别（high>medium>low）中 round 最小的一条 detail（同 first_error 免点开直出） */
    private static String firstRiskOf(List<RiskHit> hits, DetectRuleRegistry registry) {
        if (hits.isEmpty()) {
            return null;
        }
        RiskHit best = null;
        int bestRank = 99;
        for (RiskHit hit : hits) {
            int rank = switch (hit.severity() == null ? "" : hit.severity()) {
                case "high" -> 0;
                case "medium" -> 1;
                default -> 2;
            };
            boolean better = rank < bestRank
                    || (rank == bestRank && best != null
                    && hit.round() != null && (best.round() == null || hit.round() < best.round()));
            if (better) {
                best = hit;
                bestRank = rank;
            }
        }
        if (best == null) {
            return null;
        }
        String title = registry.titleOf(best.ruleId());
        String detail = best.detail() == null ? "" : best.detail();
        String line = "(" + severityLabel(best.severity()) + ")" + title + "：" + detail;
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }

    /** V2-D9 三档口径：high=异常 / medium=待人工确认 / low=风险提示（与前端、AI 提示词统一） */
    static String severityLabel(String severity) {
        return switch (severity == null ? "" : severity) {
            case "high" -> "异常";
            case "medium" -> "待人工确认";
            default -> "风险提示";
        };
    }

    /** "5,6,7" → [5,6,7]（前端渲染轮次胶囊；脏数据安全返回空） */
    private static List<Integer> parseRoundsHit(String csv) {
        List<Integer> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String part : csv.split(",")) {
            try {
                out.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // 脏数据略过
            }
        }
        return out;
    }

    private static Map<String, Object> parseEvidence(Object raw) {
        try {
            String json = String.valueOf(raw); // PGobject.toString() 即 JSON 原文（驱动 runtime 依赖，不引编译期类型）
            if (json == null || json.isBlank() || "null".equals(json)) {
                return new LinkedHashMap<>();
            }
            JsonNode node = MAPPER.readTree(json);
            Map<String, Object> out = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().isValueNode()
                    ? e.getValue().asText() : e.getValue().toString()));
            return out;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
