package com.llmetor.dataagent.dataagent.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每日 09:00 日报（M5/T5.7，设计 §5.4）：聚合昨日指标 → 取 top N 失败会话逐个轻量诊断
 * （复用诊断 Agent，created_by=auto）→ 归因分布 + 改进建议 → 落 agentx_monitor_report(type=daily)。
 *
 * <p>幂等：昨日已有日报直接跳过；单会话轻量诊断失败不阻断整份日报（该条标注诊断失败）。
 * 指标源优先 agentx_monitor_metric（分钟粒度聚合表），缺数据回退监控轮次表直查（首次上线兜底）。
 * 数据源（监控中心自有表方案 §5，T7）：agentx_monitor_trace / agentx_monitor_session。
 */
@Slf4j
@Service
public class ReportScheduleService {

    /** 归因行提取：正则集中在 DiagnoseReportMetrics（与 MonitorDiagnoseService 同口径，v1/v2 报告双兼容） */

    private final JdbcTemplate jdbc;
    private final MonitorDiagnoseService diagnoseService;
    private final MonitorReportStore reportStore;
    private final ObjectMapper objectMapper;
    /** 日报轻量诊断的失败会话数上限 */
    private final int dailyTop;
    private final boolean enabled;

    public ReportScheduleService(DataSource dataSource,
                                 MonitorDiagnoseService diagnoseService,
                                 MonitorReportStore reportStore,
                                 ObjectMapper objectMapper,
                                 @Value("${data-agent.monitor.report.daily-top:3}") int dailyTop,
                                 @Value("${data-agent.monitor.report.daily-enabled:true}") boolean enabled) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.diagnoseService = diagnoseService;
        this.reportStore = reportStore;
        this.objectMapper = objectMapper;
        this.dailyTop = dailyTop;
        this.enabled = enabled;
    }

    /** 每日 09:00（yml 可覆盖：data-agent.monitor.report.daily-cron） */
    @Scheduled(cron = "${data-agent.monitor.report.daily-cron:0 0 9 * * *}")
    public void dailyReport() {
        if (!enabled) {
            return;
        }
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDateTime start = yesterday.atStartOfDay();
        LocalDateTime end = yesterday.plusDays(1).atStartOfDay();
        try {
            if (reportStore.existsDaily(start)) {
                log.info("[monitor] 昨日日报已存在，跳过: {}", yesterday);
                return;
            }
            log.info("[monitor] 开始生成日报: {}", yesterday);
            String report = buildDailyReport(start, end);
            long reportId = reportStore.insert("daily", null, start, end,
                    "done", firstLine(report), report, metricsJson(start, end), "auto");
            log.info("[monitor] 日报生成完成: {} reportId={} length={}", yesterday, reportId, report.length());
        } catch (Exception e) {
            log.warn("[monitor] 日报生成失败: {}", e.getMessage(), e);
        }
    }

    /** 组日报 markdown：指标概览 → 失败会话轻量诊断（top N，逐个落库 single/auto）→ 归因分布 → 建议 */
    String buildDailyReport(LocalDateTime start, LocalDateTime end) throws Exception {
        Timestamp startTs = Timestamp.valueOf(start);
        Timestamp endTs = Timestamp.valueOf(end);
        Map<String, Object> kpi = yesterdayKpi(startTs, endTs);

        List<Map<String, Object>> topFailed = jdbc.queryForList(
                "SELECT conversation_id, session_id, question, fail_rounds, first_error, " +
                        "COALESCE(completed_at, created_at) AS last_at " +
                        "FROM agentx_monitor_session " +
                        "WHERE COALESCE(fail_rounds, 0) > 0 AND created_at >= ? AND created_at < ? " +
                        "ORDER BY fail_rounds DESC, created_at DESC LIMIT ?",
                startTs, endTs, dailyTop);

        StringBuilder sb = new StringBuilder();
        sb.append("# 数据分析 Agent 日报\n\n");
        sb.append("## 指标概览（").append(start.toLocalDate()).append("）\n\n");
        sb.append("- 提问总数：").append(kpi.get("convs")).append('\n');
        sb.append("- 轮次总数：").append(kpi.get("rounds"))
                .append("（失败 ").append(kpi.get("failRounds")).append("，成功率 ")
                .append(kpi.get("successRate")).append("%）\n");
        sb.append("- 失败提问数：").append(kpi.get("errorConvs")).append('\n');
        sb.append("- Tokens 消耗：输入 ").append(kpi.get("promptTokens"))
                .append(" / 输出 ").append(kpi.get("completionTokens")).append("\n\n");

        // 归因分布：本次轻量诊断的 top N（采样口径，按失败提问逐个诊断）
        Map<String, Integer> attributionCount = new LinkedHashMap<>();
        sb.append("## 失败提问轻量诊断（TOP ").append(topFailed.size()).append("，逐个 AI 归因）\n\n");
        int index = 1;
        for (Map<String, Object> f : topFailed) {
            String convId = String.valueOf(f.get("conversation_id"));
            String sessionId = String.valueOf(f.get("session_id"));
            sb.append("### ").append(index++).append(". ").append(abbreviate(sessionId, 24))
                    .append("（失败 ").append(f.get("fail_rounds")).append(" 轮）\n\n");
            Object question = f.get("question");
            if (question != null) {
                sb.append("- 问题：").append(abbreviate(String.valueOf(question), 80)).append('\n');
            }
            sb.append("- 首个错误：").append(abbreviate(String.valueOf(f.get("first_error")), 100)).append('\n');
            try {
                String text = diagnoseService.diagnoseOnce(convId, sessionId);
                String attribution = extractAttribution(text);
                attributionCount.merge(attribution.isEmpty() ? "未识别" : attribution, 1, Integer::sum);
                sb.append("- 诊断结论：").append(extractSummarySection(text)).append("\n\n");
            } catch (Exception e) {
                log.warn("[monitor] 日报轻量诊断失败 sessionId={}: {}", sessionId, e.getMessage());
                attributionCount.merge("诊断失败", 1, Integer::sum);
                sb.append("- 诊断结论：本次诊断执行失败（").append(abbreviate(e.getMessage(), 80))
                        .append("），可从报告中心手动重试\n\n");
            }
        }

        sb.append("## 归因分布（本次采样 ").append(attributionCount.values().stream().mapToInt(Integer::intValue).sum())
                .append(" 个失败会话）\n\n");
        for (Map.Entry<String, Integer> e : attributionCount.entrySet()) {
            sb.append("- ").append(e.getKey()).append("：").append(e.getValue()).append(" 个\n");
        }
        sb.append('\n');
        sb.append("## 改进建议\n\n");
        sb.append("① 按「归因分布」优先治理占比最高的类别（工具类先查工具实现与超时配置，提示词类先补 Schema 描述）\n");
        sb.append("② 对失败会话逐个打开报告中心详情，按证据链深链定位到具体轮次修复\n");
        sb.append("③ 若失败集中在特定工具，可先在工具提示词中强制约束（如 SQL 必须 LIMIT）再观察次日成功率\n");
        return sb.toString();
    }

    /** 昨日 KPI：优先 metric 聚合表，无数据回退瘦表直查（首次上线兜底） */
    private Map<String, Object> yesterdayKpi(Timestamp startTs, Timestamp endTs) {
        Long rounds = jdbc.queryForObject(
                "SELECT COALESCE(SUM(value), 0) FROM agentx_monitor_metric " +
                        "WHERE metric = 'rounds' AND dim_user IS NULL AND minute >= ? AND minute < ?",
                Long.class, startTs, endTs);
        boolean fromMetric = rounds != null && rounds > 0;
        long roundsVal, failVal, convVal, promptVal, completionVal;
        if (fromMetric) {
            Long fails = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(value), 0) FROM agentx_monitor_metric " +
                            "WHERE metric = 'fail_rounds' AND dim_user IS NULL AND minute >= ? AND minute < ?",
                    Long.class, startTs, endTs);
            Long convs = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(value), 0) FROM agentx_monitor_metric " +
                            "WHERE metric = 'convs' AND dim_user IS NULL AND minute >= ? AND minute < ?",
                    Long.class, startTs, endTs);
            Long prompt = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(value), 0) FROM agentx_monitor_metric " +
                            "WHERE metric = 'prompt_tokens' AND dim_user IS NULL AND minute >= ? AND minute < ?",
                    Long.class, startTs, endTs);
            Long completion = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(value), 0) FROM agentx_monitor_metric " +
                            "WHERE metric = 'completion_tokens' AND dim_user IS NULL AND minute >= ? AND minute < ?",
                    Long.class, startTs, endTs);
            roundsVal = rounds;
            failVal = fails == null ? 0 : fails;
            convVal = convs == null ? 0 : convs;
            promptVal = prompt == null ? 0 : prompt;
            completionVal = completion == null ? 0 : completion;
        } else {
            Long r = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agentx_monitor_trace WHERE created_at >= ? AND created_at < ?",
                    Long.class, startTs, endTs);
            Long f = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agentx_monitor_trace WHERE success = 0 AND created_at >= ? AND created_at < ?",
                    Long.class, startTs, endTs);
            Long c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agentx_monitor_session WHERE created_at >= ? AND created_at < ?",
                    Long.class, startTs, endTs);
            Long p = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(COALESCE(prompt_tokens, 0)), 0) FROM agentx_monitor_trace " +
                            "WHERE created_at >= ? AND created_at < ?", Long.class, startTs, endTs);
            Long ct = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(COALESCE(completion_tokens, 0)), 0) FROM agentx_monitor_trace " +
                            "WHERE created_at >= ? AND created_at < ?", Long.class, startTs, endTs);
            roundsVal = r == null ? 0 : r;
            failVal = f == null ? 0 : f;
            convVal = c == null ? 0 : c;
            promptVal = p == null ? 0 : p;
            completionVal = ct == null ? 0 : ct;
        }
        Long errConvs = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT conversation_id) FROM agentx_monitor_trace " +
                        "WHERE success = 0 AND created_at >= ? AND created_at < ?",
                Long.class, startTs, endTs);

        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("convs", convVal);
        kpi.put("rounds", roundsVal);
        kpi.put("failRounds", failVal);
        kpi.put("successRate", roundsVal > 0 ? Math.round((1.0 - (double) failVal / roundsVal) * 1000) / 10.0 : 100.0);
        kpi.put("errorConvs", errConvs == null ? 0 : errConvs);
        kpi.put("promptTokens", promptVal);
        kpi.put("completionTokens", completionVal);
        kpi.put("source", fromMetric ? "metric" : "trace");
        return kpi;
    }

    /** 报告 metrics_json：昨日 KPI + top 失败会话清单 */
    private String metricsJson(LocalDateTime start, LocalDateTime end) {
        try {
            Timestamp startTs = Timestamp.valueOf(start);
            Timestamp endTs = Timestamp.valueOf(end);
            Map<String, Object> kpi = yesterdayKpi(startTs, endTs);
            List<String> topFailed = jdbc.query(
                    "SELECT session_id FROM agentx_monitor_session WHERE COALESCE(fail_rounds, 0) > 0 " +
                            "AND created_at >= ? AND created_at < ? " +
                            "ORDER BY fail_rounds DESC, created_at DESC LIMIT " + dailyTop,
                    (rs, i) -> rs.getString(1), startTs, endTs);
            Map<String, Object> metrics = new LinkedHashMap<>(kpi);
            metrics.put("topFailedSessions", topFailed);
            return objectMapper.writeValueAsString(metrics);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String extractAttribution(String text) {
        return DiagnoseReportMetrics.extract(text, DiagnoseReportMetrics.ATTRIBUTION);
    }

    /** 「## 结论摘要」章节正文（日报里做轻量诊断的结论引用）；包内可见供单测 */
    static String extractSummarySection(String text) {
        if (text == null) {
            return "";
        }
        int begin = text.indexOf("## 结论摘要");
        if (begin < 0) {
            return abbreviate(text, 160);
        }
        int from = text.indexOf('\n', begin);
        int next = text.indexOf("## ", from + 1);
        String section = text.substring(from < 0 ? begin : from, next > 0 ? next : Math.min(text.length(), begin + 600));
        StringBuilder compressed = new StringBuilder();
        for (String line : section.split("\n")) {
            String t = line.trim();
            // 表格行不进单行引用（v2 §1 表格拍扁成管道串不可读；归因/影响轮/根因句在表格前的固定行里）
            if (!t.isEmpty() && !t.startsWith("|")) {
                compressed.append(t).append(' ');
            }
            if (compressed.length() > 300) {
                break;
            }
        }
        return compressed.toString().trim();
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                return line.trim();
            }
        }
        return "";
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace("\r", " ").replace("\n", " ").trim();
        return oneLine.length() > max ? oneLine.substring(0, max) + "…" : oneLine;
    }
}
