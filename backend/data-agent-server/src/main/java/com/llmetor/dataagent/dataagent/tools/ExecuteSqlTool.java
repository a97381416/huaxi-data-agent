package com.llmetor.dataagent.dataagent.tools;

import com.llmetor.dataagent.dataagent.permission.DataScopeRewriter;
import com.llmetor.dataagent.dataagent.permission.RewriteException;
import com.llmetor.dataagent.dataagent.permission.RewriteResult;
import com.llmetor.dataagent.dataagent.safety.ExplainPrecheckService;
import com.llmetor.dataagent.dataagent.safety.ExplainVerdict;
import com.llmetor.dataagent.dataagent.safety.ReadOnlyQueryRunner;
import com.llmetor.dataagent.dataagent.safety.SensitiveFilter;
import com.llmetor.dataagent.dataagent.safety.SqlResult;
import com.llmetor.dataagent.dataagent.safety.ValidationResult;
import com.llmetor.dataagent.dataagent.safety.SqlSafetyGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SQL 执行工具（只读，业务库）。M4 完整六步编排（docs/01 §2.2，顺序固定）：
 * guard 静态校验 → 数据权限改写 → EXPLAIN 预检 → 只读执行 → 敏感字段脱敏 → 面向 LLM 格式化。
 *
 * <p>userId 由 mini-agentx 内核通过 toolParams 白名单注入（框架按 inputSchema 补参，
 * 不暴露给 LLM、不依赖 ThreadLocal——工具在异步线程执行）。
 *
 * <p>失败时错误信息回灌给 LLM，驱动 ReAct 循环重写 SQL（重试 ≤3 次）。
 */
@Slf4j
@Component
public class ExecuteSqlTool {

    /** markdown 表格预览行数上限 */
    private static final int PREVIEW_ROWS = 20;

    private final SqlSafetyGuard guard;
    private final ReadOnlyQueryRunner queryRunner;
    private final DataScopeRewriter dataScopeRewriter;
    private final ExplainPrecheckService explainPrecheck;
    private final SensitiveFilter sensitiveFilter;

    public ExecuteSqlTool(SqlSafetyGuard guard,
                          ReadOnlyQueryRunner queryRunner,
                          DataScopeRewriter dataScopeRewriter,
                          ExplainPrecheckService explainPrecheck,
                          SensitiveFilter sensitiveFilter) {
        this.guard = guard;
        this.queryRunner = queryRunner;
        this.dataScopeRewriter = dataScopeRewriter;
        this.explainPrecheck = explainPrecheck;
        this.sensitiveFilter = sensitiveFilter;
    }

    @Tool(name = "executeSql",
            description = "执行只读 SELECT/WITH 查询并返回结果数据。" +
                    "参数名严格为 sql 和 userId，严禁使用任何其他参数名，" +
                    "传错会收到 \"参数名错误\" 提示，必须改用 sql 重新调用。" +
                    "内部会强制做 SQL 安全校验（白名单/强制 LIMIT）、数据权限过滤（按当前用户可见范围自动注入 WHERE，" +
                    "无需手写权限条件）与执行计划预检（大表明细扫描/JOIN 扩张会被拦截），" +
                    "敏感列的返回值可能是 ********（系统脱敏，属正常数据，不是空值）。" +
                    "若执行失败或被拦截，会返回 error 字段——请根据错误信息重写 SQL（最多重试 3 次），" +
                    "常见错误：字段名拼错（参考 describeTables 输出）、表名错、JOIN 条件错、扫描行数过大（改聚合）。" +
                    "严禁写 INSERT/UPDATE/DELETE/DROP 等。")
    public String executeSql(
            @ToolParam(description = "要执行的 SELECT 或 WITH 查询语句（建议先用 validateSql 校验拿安全 SQL）。参数名必须是 sql")
            String sql,
            @ToolParam(description = "用户身份标识。参数名必须是 userId，由框架自动注入真实值", required = false)
            String userId) {
        if (sql == null || sql.isBlank()) {
            return "❌ 参数错误：sql 为空。本工具参数名严格为 `sql` 和 `userId`，禁止用任何其他名字。" +
                    "请重新调用 executeSql，把 SQL 语句放在 `sql` 参数中。";
        }

        log.info("[data-agent] executeSql 入参 | userId={}, sql={}", userId, sql);

        // ① 强制校验兜底（validateSql 通过后这里再验一次，双保险）
        ValidationResult vr = guard.validate(sql);
        if (!vr.valid()) {
            return "❌ 已拦截（未执行）。原因：" + vr.reason()
                    + "\n请根据原因重写 SQL：统计问题优先改为 GROUP BY 聚合；明细问题先按主表稳定分页；"
                    + "一对多明细请先聚合或按主键二次钻取，不要一次性展开大结果集。";
        }
        String safeSql = vr.safeSql();

        // ② 数据权限改写（按当前用户 dataScope 注入 WHERE/ON；ALL 范围原样返回）
        String executableSql;
        try {
            RewriteResult rr = dataScopeRewriter.rewrite(safeSql, parseUserId(userId));
            executableSql = rr.sql();
        } catch (RewriteException e) {
            log.warn("[data-agent] 数据权限改写拒绝 | userId={} | reason={}", userId, e.getMessage());
            return "❌ 已拦截（未执行）。原因：数据权限过滤无法安全注入——" + e.getMessage()
                    + "\n请改写 SQL：JOIN 使用明确的 ON 条件，不要用 USING/NATURAL JOIN。";
        }

        // ③ EXPLAIN 预检（拦大表明细扫描与 JOIN 扩张；fail-open，聚合直接放行）
        ExplainVerdict verdict = explainPrecheck.precheck(executableSql);
        if (!verdict.allowed()) {
            log.warn("[data-agent] EXPLAIN 预检拦截 | userId={} | reason={}", userId, verdict.reason());
            return "❌ 已拦截（未执行）：" + verdict.reason();
        }

        // ④ 只读执行
        SqlResult result = queryRunner.execute(executableSql);

        // ⑤ 敏感字段脱敏（原地替换 ********）
        sensitiveFilter.filter(result);

        // ⑥ 审计日志 + 面向 LLM 格式化
        if (result.success()) {
            log.info("[data-agent] SQL OK | rows={} truncated={} cost={}ms | sql={}",
                    result.rowCount(), result.truncated(), result.durationMs(), truncate(executableSql, 300));
        } else {
            log.warn("[data-agent] SQL FAIL | cost={}ms err={} | sql={}",
                    result.durationMs(), truncate(result.error(), 300), truncate(executableSql, 300));
        }

        return format(result);
    }

    private Long parseUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(userId.trim());
        } catch (NumberFormatException e) {
            log.warn("[data-agent] userId 非数字，数据权限按最小权限处理: {}", userId);
            return null;
        }
    }

    // ── 面向 LLM 的输出格式化 ──

    private String format(SqlResult r) {
        StringBuilder sb = new StringBuilder();
        if (!r.success()) {
            sb.append("❌ 执行失败（耗时 ").append(r.durationMs()).append("ms）：")
                    .append(r.error()).append('\n');
            sb.append("执行的 SQL：\n```sql\n").append(r.executedSql()).append("\n```\n");
            sb.append("请根据上述报错重写 SQL（最多重试 3 次）。")
                    .append("常见原因：字段名拼错、表名错、JOIN 条件错、类型不兼容。")
                    .append("建议重新调用 describeTables 确认真实字段名；")
                    .append("如果 JOIN 结果异常放大，请先按主表分页或对子表聚合后再 JOIN。");
            return sb.toString().trim();
        }

        sb.append("✅ 执行成功（耗时 ").append(r.durationMs())
                .append("ms，返回 ").append(r.rowCount()).append(" 行")
                .append(r.truncated() ? "，已截断到 max-rows 上限" : "")
                .append("）\n\n");
        sb.append("执行的 SQL：\n```sql\n").append(r.executedSql()).append("\n```\n\n");

        if (r.rowCount() == 0) {
            sb.append("结果：空结果集（0 行）。\n")
                    .append("若这不符合预期，可能是 WHERE 条件过严或 JOIN 关联键错误——")
                    .append("建议去掉部分条件重查，或先 SELECT COUNT(*) 看真实命中量。")
                    .append("注意：系统会按当前用户的数据权限自动注入范围过滤，")
                    .append("若上方 SQL 含非你手写的 WHERE 条件，那是权限注入，属正常行为。");
            return sb.toString().trim();
        }

        sb.append("结果（").append(r.rowCount()).append("行");
        if (r.rowCount() > PREVIEW_ROWS) {
            sb.append("，预览前 ").append(PREVIEW_ROWS).append(" 行");
        }
        sb.append("）：\n\n").append(renderTable(r.columns(), r.rows(), PREVIEW_ROWS));

        if (r.rowCount() > PREVIEW_ROWS) {
            sb.append("\n\n⚠ 共 ").append(r.rowCount()).append(" 行，仅展示前 ").append(PREVIEW_ROWS)
                    .append(" 行，剩余 ").append(r.rowCount() - PREVIEW_ROWS).append(" 行未在上下文中展示——")
                    .append("如需明细请按主表稳定排序分页重查，并注明当前页范围；")
                    .append("如是一对多关系，请优先对子表聚合成数量/金额/最新时间后再 JOIN，")
                    .append("用户点名某条主记录时再按主键二次查询明细；")
                    .append("如是分析/汇总任务，请改写为聚合 SQL（GROUP BY + SUM/COUNT/AVG）后重新查询，")
                    .append("不要试图从预览行里手算总量或均值。");
        }
        return sb.toString().trim();
    }

    private String renderTable(List<String> columns, List<Map<String, Object>> rows, int preview) {
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", columns)).append(" |\n");
        sb.append("|").append("---|".repeat(columns.size())).append("\n");
        int n = Math.min(preview, rows.size());
        for (int i = 0; i < n; i++) {
            Map<String, Object> row = rows.get(i);
            sb.append("| ");
            List<String> vals = new ArrayList<>(columns.size());
            for (String c : columns) {
                vals.add(renderCell(row.get(c)));
            }
            sb.append(String.join(" | ", vals)).append(" |\n");
        }
        return sb.toString().trim();
    }

    private String renderCell(Object v) {
        if (v == null) return "NULL";
        String s = String.valueOf(v);
        s = s.replace("|", "\\|").replace("\r", " ").replace("\n", " ").replace("\t", " ");
        return truncate(s, 200);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
