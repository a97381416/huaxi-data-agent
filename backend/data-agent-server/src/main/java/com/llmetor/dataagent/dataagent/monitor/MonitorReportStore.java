package com.llmetor.dataagent.dataagent.monitor;

import com.llmetor.dataagent.dataagent.monitor.dto.PageResult;
import com.llmetor.miniagentx.utils.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 监控报告落库（T5.5，agentx_monitor_report，DDL sql/trace-monitor/V04）：
 * 诊断完成自动落库（MonitorDiagnoseService）+ 日报（ReportScheduleService）+ 报告中心查询。
 */
@Slf4j
@Service
public class MonitorReportStore {

    private final JdbcTemplate jdbc;

    public MonitorReportStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        createTable();
    }

    public void createTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agentx_monitor_report (
                  id bigint NOT NULL,
                  type varchar(20) NOT NULL,
                  conversation_id varchar(100) NULL,
                  period_start timestamp NULL,
                  period_end timestamp NULL,
                  status varchar(20) NOT NULL,
                  summary_md text NULL,
                  detail_md text NULL,
                  metrics_json jsonb NULL,
                  created_by varchar(20) NOT NULL,
                  created_at timestamp NULL DEFAULT CURRENT_TIMESTAMP,
                  PRIMARY KEY (id)
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_report_conv ON agentx_monitor_report (conversation_id)");
    }

    /** 报告列表行（列表页最小投影：detail 不带，摘要截断；id 用 String 避免前端 JS 大数精度丢失） */
    public record ReportItem(String id, String type, String conversationId, String periodStart,
                             String periodEnd, String status, String summaryMd,
                             String createdBy, String createdAt) {
    }

    /** 报告详情（含 markdown 全文） */
    public record ReportDetail(String id, String type, String conversationId, String periodStart,
                               String periodEnd, String status, String summaryMd, String detailMd,
                               String metricsJson, String createdBy, String createdAt) {
    }

    /** 落库一条报告，返回报告 id */
    public long insert(String type, String conversationId, LocalDateTime periodStart,
                       LocalDateTime periodEnd, String status, String summaryMd,
                       String detailMd, String metricsJson, String createdBy) {
        long id = SnowflakeIdGenerator.nextId();
        jdbc.update("INSERT INTO agentx_monitor_report (id, type, conversation_id, period_start, period_end, " +
                        "status, summary_md, detail_md, metrics_json, created_by) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)",
                id, type, conversationId,
                periodStart == null ? null : Timestamp.valueOf(periodStart),
                periodEnd == null ? null : Timestamp.valueOf(periodEnd),
                status, summaryMd, detailMd, metricsJson, createdBy);
        return id;
    }

    /** 报告列表（type/conversationId 可选过滤，created_at 倒序，服务端分页） */
    public PageResult<ReportItem> list(String type, String conversationId, Integer page, Integer size) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size <= 0 ? 20 : Math.min(size, 100);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (type != null && !type.isBlank()) {
            where.append(" AND type = ?");
            args.add(type.trim());
        }
        if (conversationId != null && !conversationId.isBlank()) {
            where.append(" AND conversation_id = ?");
            args.add(conversationId.trim());
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM agentx_monitor_report" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(p * s);
        List<ReportItem> items = jdbc.query(
                "SELECT id, type, conversation_id, period_start, period_end, status, summary_md, created_by, created_at " +
                        "FROM agentx_monitor_report" + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new ReportItem(String.valueOf(rs.getLong("id")), rs.getString("type"), rs.getString("conversation_id"),
                        formatTs(rs.getTimestamp("period_start")), formatTs(rs.getTimestamp("period_end")),
                        rs.getString("status"), abbreviate(rs.getString("summary_md"), 120),
                        rs.getString("created_by"), formatTs(rs.getTimestamp("created_at"))),
                pageArgs.toArray());
        return PageResult.of(total == null ? 0 : total, p, s, items);
    }

    /** 报告详情（不存在返回 null） */
    public ReportDetail getById(long id) {
        List<ReportDetail> rows = jdbc.query(
                "SELECT id, type, conversation_id, period_start, period_end, status, summary_md, detail_md, " +
                        "metrics_json, created_by, created_at FROM agentx_monitor_report WHERE id = ? LIMIT 1",
                (rs, i) -> new ReportDetail(String.valueOf(rs.getLong("id")), rs.getString("type"), rs.getString("conversation_id"),
                        formatTs(rs.getTimestamp("period_start")), formatTs(rs.getTimestamp("period_end")),
                        rs.getString("status"), rs.getString("summary_md"), rs.getString("detail_md"),
                        rs.getString("metrics_json"), rs.getString("created_by"),
                        formatTs(rs.getTimestamp("created_at"))),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 该会话已生成的诊断报告（详情页左栏「关联报告」，最新 10 条） */
    public List<ReportItem> listByConversation(String conversationId, int limit) {
        return jdbc.query(
                "SELECT id, type, conversation_id, period_start, period_end, status, summary_md, created_by, created_at " +
                        "FROM agentx_monitor_report WHERE conversation_id = ? " +
                        "ORDER BY created_at DESC, id DESC LIMIT " + Math.max(1, limit),
                (rs, i) -> new ReportItem(String.valueOf(rs.getLong("id")), rs.getString("type"), rs.getString("conversation_id"),
                        formatTs(rs.getTimestamp("period_start")), formatTs(rs.getTimestamp("period_end")),
                        rs.getString("status"), abbreviate(rs.getString("summary_md"), 120),
                        rs.getString("created_by"), formatTs(rs.getTimestamp("created_at"))),
                conversationId);
    }

    /** 日报幂等：该周期起点已有日报则跳过 */
    public boolean existsDaily(LocalDateTime periodStart) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agentx_monitor_report WHERE type = 'daily' AND period_start = ?",
                Long.class, Timestamp.valueOf(periodStart));
        return count != null && count > 0;
    }

    private static String formatTs(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return null;
        }
        String oneLine = text.replace("\r", " ").replace("\n", " ").trim();
        return oneLine.length() > max ? oneLine.substring(0, max) + "…" : oneLine;
    }
}
