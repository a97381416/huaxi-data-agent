package com.llmetor.dataagent.dataagent.monitor;

import com.llmetor.miniagentx.utils.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 指标聚合任务（T2.5）：每 5 分钟对监控轮次表滚动聚合 → upsert agentx_monitor_metric（minute=桶起点）。
 *
 * <p>桶口径：minute 整 5 分钟对齐，聚合 [minute, minute+5min) 半开区间。指标：
 * convs（窗口新提问数，monitor_session 一问一行）/ rounds / fail_rounds /
 * error_convs（窗口内有失败轮的会话数）/ avg_ms / p95_ms（Java 侧按有序 duration 取分位点，
 * 避开 PG 专有 percentile 函数）/ prompt_tokens / completion_tokens（分开记）。
 * dim_user 只写 NULL 全局行（按用户切片留待后续；'collector' 行归 MonitorTraceCollector 写失败计数，互不侵犯）。
 *
 * <p>数据源（监控中心自有表方案 §5，T7）：agentx_monitor_trace / agentx_monitor_session
 * （字段同名，SQL 与旧 agentx_trace 口径基本原样）。
 *
 * <p>幂等与追平：每次运行重算最近 {@link #RECENT_BUCKETS} 个桶（容忍延迟写入/晚到数据），
 * 并对 24h 内缺数据的桶补算（首次上线空表时自动追平一天，Dashboard 开箱有图）。
 * upsert 实现 = 事务内先 DELETE 该桶行再 INSERT（表无唯一约束，DELETE+INSERT 最稳；
 * 只清 dim_user IS NULL，collector 失败计数行不受影响）。
 */
@Slf4j
@Service
public class MetricAggregateJob {

    /** 桶宽 5 分钟 */
    private static final long BUCKET_MINUTES = 5;
    /** 每次滚动重算的最近桶数（15 分钟，容忍聚合任务停摆与晚写） */
    private static final int RECENT_BUCKETS = 3;
    /** 缺桶追平窗口（小时）：首次上线/任务长时间停摆后自动回填 */
    private static final int CATCHUP_HOURS = 24;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;
    /** yml 开关（联调环境可关掉聚合任务） */
    private final boolean enabled;

    public MetricAggregateJob(DataSource dataSource,
                              @Value("${data-agent.monitor.metric-job.enabled:true}") boolean enabled) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.enabled = enabled;
        DefaultTransactionDefinition txDef = new DefaultTransactionDefinition();
        txDef.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.txTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource), txDef);
        createTable();
    }

    public void createTable() {
        // T2.5 DDL（sql/trace-monitor/V03__monitor_metric_ddl.sql 同构）
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

    /** 每 5 分钟（每小时第 0/5/10... 分钟）滚动聚合 */
    @Scheduled(cron = "0 */5 * * * *")
    public void aggregate() {
        if (!enabled) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
            LocalDateTime currentBucket = truncateToBucket(now);
            // 已存在桶集合（追平判重用）
            Timestamp since = Timestamp.valueOf(currentBucket.minusHours(CATCHUP_HOURS));
            List<Timestamp> existing = jdbc.query(
                    "SELECT DISTINCT minute FROM agentx_monitor_metric WHERE minute >= ?",
                    (rs, i) -> rs.getTimestamp("minute"), since);

            int computed = 0, skipped = 0;
            // 从旧到新：先补缺桶（首上线追平），再重算最近 3 桶（晚写修正）
            for (int i = CATCHUP_HOURS * 60 / (int) BUCKET_MINUTES; i >= 0; i--) {
                LocalDateTime bucket = currentBucket.minusMinutes(i * BUCKET_MINUTES);
                Timestamp start = Timestamp.valueOf(bucket);
                boolean recent = i < RECENT_BUCKETS;
                boolean missing = !existing.contains(start);
                if (!recent && !missing) {
                    skipped++;
                    continue;
                }
                aggregateBucket(bucket);
                computed++;
            }
            if (computed > 0) {
                log.info("[monitor] 指标聚合完成: 计算 {} 桶（含最近重算 {}）, 跳过 {} 桶",
                        computed, Math.min(RECENT_BUCKETS, computed), skipped);
            }
        } catch (Exception e) {
            log.warn("[monitor] 指标聚合失败（下轮重试）: {}", e.getMessage(), e);
        }
    }

    /** 聚合单个 5 分钟桶 [bucket, bucket+5min)：先 DELETE 再 INSERT（事务内原子替换） */
    void aggregateBucket(LocalDateTime bucket) {
        Timestamp start = Timestamp.valueOf(bucket);
        Timestamp end = Timestamp.valueOf(bucket.plusMinutes(BUCKET_MINUTES));

        List<Object[]> rows = new ArrayList<>();
        Long rounds = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agentx_monitor_trace WHERE created_at >= ? AND created_at < ?", Long.class, start, end);
        rows.add(metricRow(bucket, "rounds", rounds == null ? 0 : rounds));
        if (rounds != null && rounds > 0) {
            Long fails = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agentx_monitor_trace WHERE success = 0 AND created_at >= ? AND created_at < ?",
                    Long.class, start, end);
            rows.add(metricRow(bucket, "fail_rounds", fails == null ? 0 : fails));
            Long errConvs = jdbc.queryForObject(
                    "SELECT COUNT(DISTINCT conversation_id) FROM agentx_monitor_trace " +
                            "WHERE success = 0 AND created_at >= ? AND created_at < ?", Long.class, start, end);
            rows.add(metricRow(bucket, "error_convs", errConvs == null ? 0 : errConvs));
            Double avgMs = jdbc.queryForObject(
                    "SELECT AVG(duration_ms) FROM agentx_monitor_trace WHERE created_at >= ? AND created_at < ?",
                    Double.class, start, end);
            if (avgMs != null) {
                rows.add(metricRow(bucket, "avg_ms", avgMs));
            }
            Double p95Ms = p95Ms(start, end);
            if (p95Ms != null) {
                rows.add(metricRow(bucket, "p95_ms", p95Ms));
            }
            Long prompt = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(COALESCE(prompt_tokens, 0)), 0) FROM agentx_monitor_trace " +
                            "WHERE created_at >= ? AND created_at < ?", Long.class, start, end);
            Long completion = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(COALESCE(completion_tokens, 0)), 0) FROM agentx_monitor_trace " +
                            "WHERE created_at >= ? AND created_at < ?", Long.class, start, end);
            rows.add(metricRow(bucket, "prompt_tokens", prompt == null ? 0 : prompt));
            rows.add(metricRow(bucket, "completion_tokens", completion == null ? 0 : completion));
        }
        Long convs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agentx_monitor_session WHERE created_at >= ? AND created_at < ?",
                Long.class, start, end);
        rows.add(metricRow(bucket, "convs", convs == null ? 0 : convs));

        txTemplate.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM agentx_monitor_metric WHERE minute = ? AND dim_user IS NULL", start);
            for (Object[] row : rows) {
                jdbc.update("INSERT INTO agentx_monitor_metric (id, minute, metric, dim_user, value) VALUES (?, ?, ?, NULL, ?)",
                        row);
            }
        });
    }

    /**
     * P95 耗时：桶内按 duration_ms 有序取 ceil(0.95*N) 位（Java 分位点，SQL 方言无关）。
     * 桶内轮次多时（数百）内存代价可忽略；无轮次返回 null（不写行，前端断线）。
     */
    private Double p95Ms(Timestamp start, Timestamp end) {
        List<Long> durations = jdbc.query(
                "SELECT COALESCE(duration_ms, 0) FROM agentx_monitor_trace WHERE created_at >= ? AND created_at < ? ORDER BY duration_ms ASC",
                (rs, i) -> rs.getLong(1), start, end);
        if (durations.isEmpty()) {
            return null;
        }
        int idx = (int) Math.ceil(durations.size() * 0.95) - 1;
        idx = Math.min(Math.max(idx, 0), durations.size() - 1);
        return durations.get(idx).doubleValue();
    }

    private static Object[] metricRow(LocalDateTime bucket, String metric, double value) {
        return new Object[]{SnowflakeIdGenerator.nextId(), Timestamp.valueOf(bucket), metric, value};
    }

    /** 整 5 分钟对齐（向下取整），minute = 桶起点 */
    private static LocalDateTime truncateToBucket(LocalDateTime time) {
        int minute = (time.getMinute() / (int) BUCKET_MINUTES) * (int) BUCKET_MINUTES;
        return time.truncatedTo(ChronoUnit.HOURS).plusMinutes(minute);
    }
}
