package com.llmetor.dataagent.dataagent.safety;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * EXPLAIN 预检（SQL 安全链第 ③ 步，docs/01 §2.2③）：执行前先让 PostgreSQL 优化器
 * 估算执行计划，把"明显要扫几十万行的明细查询"拦在执行之前。
 *
 * <p><b>保守拦截 + fail-open</b>——预检是成本控制不是安全边界（安全边界是 guard +
 * 只读连接 + LIMIT），判断失误的代价应该是"多跑一条慢查询"，而不是"业务被挡死"：
 * <ul>
 *   <li>聚合查询（GROUP BY/HAVING/COUNT(/SUM(/AVG(/MIN(/MAX(）直接放行——聚合必然
 *       过大量数据但输出行数有界，只拦"明细形态"的扫描</li>
 *   <li>EXPLAIN 本身失败（非超时）→ 放行；EXPLAIN 超时（PG SQLState 57014 query_canceled，
 *       含 statement_timeout 与驱动 cancel）→ 拦截</li>
 *   <li>规则 1 大表明细扫描：任一计划节点 Node Type=Seq Scan 且估算 Plan Rows ≥ max-estimated-rows</li>
 *   <li>规则 2 JOIN 扩张：计划节点 Plan Rows 乘积 ≥ join 阈值（10 倍）且有 JOIN 风险信号
 *       （非首节点全扫 / ≥2 节点没走索引；PG 版无 Using join buffer 信号，Hash Join
 *       内层 Seq Scan 已由"非首节点全扫"覆盖）</li>
 * </ul>
 * M-H1 PG 改造：MySQL 表格输出（type/rows/table/Extra）→ EXPLAIN (FORMAT JSON)
 * 递归展平计划树（Node Type/Relation Name/Plan Rows）。
 */
@Slf4j
@Service
public class ExplainPrecheckService {

    /** 计划 JSON 解析器（EXPLAIN (FORMAT JSON) 输出） */
    private static final ObjectMapper PLAN_MAPPER = new ObjectMapper();

    /** 聚合特征（归一化文本判断，子查询里的聚合也算聚合） */
    private static final Pattern AGGREGATE_PATTERN = Pattern.compile(
            "(?i)\\bgroup\\s+by\\b|\\bhaving\\b|\\bcount\\s*\\(|\\bsum\\s*\\(|\\bavg\\s*\\(|\\bmin\\s*\\(|\\bmax\\s*\\(");

    /** PG 执行取消/超时：SQLState 57014 query_canceled（statement_timeout 与驱动 setQueryTimeout cancel 同码） */
    private static final String PG_TIMEOUT_SQLSTATE = "57014";

    private final DataSource dataSource;

    @Value("${data-agent.safety.explain.enabled:true}")
    private boolean enabled;

    @Value("${data-agent.safety.explain.timeout-seconds:5}")
    private int timeoutSeconds;

    @Value("${data-agent.safety.explain.max-estimated-rows:100000}")
    private long maxEstimatedRows;

    public ExplainPrecheckService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 预检 SQL 执行计划。
     *
     * @param sql 待执行 SQL（已经过 guard 校验与数据权限改写）
     * @return allowed=false 时 reason 为拦截说明与改写引导
     */
    public ExplainVerdict precheck(String sql) {
        if (!enabled) {
            return ExplainVerdict.allow(0);
        }
        if (isAggregateQuery(sql)) {
            return ExplainVerdict.allow(0);
        }

        ExplainOutcome outcome = runExplain(sql);
        if (outcome.error() != null) {
            if (outcome.timeout()) {
                return ExplainVerdict.block(
                        "执行计划预检超时（EXPLAIN " + timeoutSeconds + "s 未完成），查询复杂度过高。"
                                + "请改写为聚合查询（GROUP BY），或先加更严的 WHERE 条件缩小数据范围。",
                        0);
            }
            // fail-open：EXPLAIN 失败不挡业务
            log.warn("[explain-precheck] EXPLAIN 失败放行（fail-open）：{}", truncate(outcome.error(), 200));
            return ExplainVerdict.allow(0);
        }
        return evaluate(outcome.planRows());
    }

    /** 两条拦截规则的评估（独立方法，单测可直接构造计划行验证，不需要数据库） */
    ExplainVerdict evaluate(List<PlanRow> rows) {
        BigInteger product = BigInteger.ONE;
        int fullScanRows = 0;
        boolean nonFirstRowFullScan = false;
        boolean joinBuffer = false;
        long maxRows = 0;

        for (int i = 0; i < rows.size(); i++) {
            PlanRow row = rows.get(i);
            product = product.multiply(BigInteger.valueOf(Math.max(1, row.rows())));
            maxRows = Math.max(maxRows, row.rows());

            // M-H1 PG：Seq Scan=全表扫描（对应 MySQL type=ALL）；Index/Index Only Scan 等视为有索引
            boolean noIndex = row.accessType().equalsIgnoreCase("Seq Scan");
            if (noIndex && row.rows() >= maxEstimatedRows) {
                // 规则 1：大表明细扫描（单表估算行数超阈值）
                return ExplainVerdict.block(
                        "大表明细扫描：表 " + (row.tableName().isEmpty() ? "?" : row.tableName())
                                + " 预计扫描 " + humanize(row.rows()) + " 行（无索引或索引全扫），"
                                + "且查询是明细形态。" + guide(),
                        row.rows());
            }
            if (noIndex) {
                fullScanRows++;
                if (i > 0) {
                    nonFirstRowFullScan = true;
                }
            }
            if (row.extra() != null
                    && row.extra().toLowerCase(Locale.ROOT).contains("using join buffer")) {
                joinBuffer = true;
            }
        }

        BigInteger joinThreshold = BigInteger.valueOf(maxEstimatedRows).multiply(BigInteger.TEN);
        if (rows.size() >= 2 && product.compareTo(joinThreshold) >= 0 && hasJoinRisk(joinBuffer, nonFirstRowFullScan, fullScanRows)) {
            return ExplainVerdict.block(
                    "JOIN 扩张风险：多表 JOIN 估算结果行数约 " + humanizeBigInteger(product)
                            + "（阈值 " + humanizeBigInteger(joinThreshold) + "），且命中 JOIN 风险信号："
                            + riskSignals(joinBuffer, nonFirstRowFullScan, fullScanRows)
                            + "。" + guide(),
                    maxRows);
        }
        return ExplainVerdict.allow(rows.isEmpty() ? 0 : rows.get(0).rows());
    }

    private String guide() {
        return "引导：统计问题请改写为 GROUP BY 聚合查询；确需明细请先按主表加 WHERE 条件"
                + "（时间窗/主键/状态）缩小范围，或先聚合得到 ID 清单再按主键钻取。";
    }

    private boolean isAggregateQuery(String sql) {
        String normalized = sql.replaceAll("\\s+", " ").trim();
        return AGGREGATE_PATTERN.matcher(normalized).find();
    }

    /** 执行 EXPLAIN 读取执行计划；错误字段非 null 时按 timeout 标记分流 */
    private ExplainOutcome runExplain(String sql) {
        long t0 = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(timeoutSeconds);
                // M-H1 PG：JSON 格式输出（单行单列），比文本格式可稳定解析
                try (ResultSet rs = stmt.executeQuery("EXPLAIN (FORMAT JSON) " + sql)) {
                    List<PlanRow> rows = rs.next() ? readJsonPlan(rs.getString(1)) : List.of();
                    log.info("[explain-precheck] EXPLAIN 完成 | {} 行计划 | 耗时 {}ms",
                            rows.size(), System.currentTimeMillis() - t0);
                    return new ExplainOutcome(null, false, rows);
                }
            }
        } catch (SQLException e) {
            if (isTimeout(e)) {
                log.warn("[explain-precheck] EXPLAIN 超时（{}s）", timeoutSeconds);
                return new ExplainOutcome(null, true, List.of());
            }
            return new ExplainOutcome(rootMessage(e), false, List.of());
        } catch (Exception e) {
            return new ExplainOutcome(e.getClass().getSimpleName() + ": " + e.getMessage(), false, List.of());
        }
    }

    /** EXPLAIN (FORMAT JSON) 输出 → 计划行（递归展平计划树：Node Type=访问方式 /
     *  Relation Name=表名 / Plan Rows=估算行数；PG 无 Extra 列，恒 null） */
    private List<PlanRow> readJsonPlan(String json) throws SQLException {
        try {
            JsonNode root = PLAN_MAPPER.readTree(json);      // [ { "Plan": {...} } ]
            List<PlanRow> rows = new ArrayList<>();
            collectPlanNodes(root.path(0).path("Plan"), rows);
            return rows;
        } catch (Exception e) {
            throw new SQLException("EXPLAIN JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /** 深度优先展平：Join 节点的子计划顺序即 MySQL 计划行顺序（驱动表在前） */
    private void collectPlanNodes(JsonNode node, List<PlanRow> out) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        String nodeType = node.path("Node Type").asText("UNKNOWN");
        String relation = node.path("Relation Name").asText("");
        long planRows = node.path("Plan Rows").asLong(0);
        out.add(new PlanRow(relation, nodeType, planRows, null));
        for (JsonNode child : node.path("Plans")) {
            collectPlanNodes(child, out);
        }
    }

    private boolean isTimeout(SQLException e) {
        // M-H1 PG：statement_timeout 与驱动 setQueryTimeout 到点 cancel 均 SQLState 57014
        if (PG_TIMEOUT_SQLSTATE.equals(e.getSQLState())) {
            return true;
        }
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return msg.contains("canceling statement due to statement timeout")
                || msg.contains("cancelled due to timeout")
                || msg.contains("query_canceled");
    }

    private String rootMessage(SQLException e) {
        SQLException cur = e;
        SQLException deepest = e;
        while (cur != null) {
            deepest = cur;
            cur = cur.getNextException();
        }
        return deepest.getMessage() == null ? deepest.getClass().getSimpleName() : deepest.getMessage();
    }

    private boolean hasJoinRisk(boolean joinBuffer, boolean nonFirstRowFullScan, int fullScanRows) {
        return joinBuffer || nonFirstRowFullScan || fullScanRows >= 2;
    }

    private String riskSignals(boolean joinBuffer, boolean nonFirstRowFullScan, int fullScanRows) {
        List<String> signals = new ArrayList<>();
        if (joinBuffer) signals.add("Using join buffer");
        if (nonFirstRowFullScan) signals.add("非驱动表全表扫描");
        if (fullScanRows >= 2) signals.add(fullScanRows + " 个表未走索引");
        return String.join("、", signals);
    }

    private static String humanize(long n) {
        if (n >= 100_000_000) return String.format("%.1f 亿", n / 100_000_000.0);
        if (n >= 10_000) return String.format("%.1f 万", n / 10_000.0);
        return String.valueOf(n);
    }

    private static String humanizeBigInteger(BigInteger n) {
        if (n.compareTo(BigInteger.valueOf(100_000_000_000L)) >= 0) {
            return "超过上限";
        }
        return humanize(n.longValue());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 单个执行计划行：表名 / 访问方式（access_type）/ 估算行数 / Extra */
    record PlanRow(String tableName, String accessType, long rows, String extra) {
    }

    /** EXPLAIN 运行结果：error 非空即失败（timeout 区分是否超时）；成功带计划行 */
    record ExplainOutcome(String error, boolean timeout, List<PlanRow> planRows) {
    }
}
