package com.llmetor.dataagent.dataagent.monitor.detect;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 批量重诊断（V2 规格 §6，V2-D10）：按版本/时间/会话圈定历史提问，重跑 v2 规则——
 * <b>严格只读回放</b>（只读 agentx_monitor_trace + agentx_trace_payload，绝不重放工具）。
 * 执行模型：圈定 session 列表 → 校验终态 → 置 detect_status='pending' → 按 50/批切片
 * 投递 DetectDispatcher 现有单线程队列（与实时检测天然串行，不挤压实时检测；
 * 队列满丢弃的部分由 SweepJob 兜底接续——sweep 只补 detect_status<>'done'，pending 同样覆盖，闭环）。
 * 单问检测复用 AnomalyDetectService.detect 的幂等 replace（DELETE→INSERT + 回填 detector_version=v2）。
 *
 * <p>进度：首版不建进度表——列表页按 detector_version 筛选观察；内存计数器
 * （受理/完成/失败）经 GET /monitor/detect/rerun/status 暴露（进程重启清零，可选任务口径）。
 */
@Slf4j
@Service
public class DetectRerunService {

    /** 单次受理上限（默认 200、最大 1000，05 文档 §6.1） */
    static final int DEFAULT_LIMIT = 200;
    static final int MAX_LIMIT = 1000;
    /** 投递切片大小（与 SweepJob BATCH 同口径） */
    static final int SLICE = 50;

    private final AnomalyDetectService detectService;
    private final DetectDispatcher dispatcher;
    private final JdbcTemplate jdbc;

    /** 内存计数器（受理 / 完成 / 失败；pending = accepted - completed - failed） */
    private final AtomicLong acceptedCount = new AtomicLong();
    private final AtomicLong completedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();

    public DetectRerunService(AnomalyDetectService detectService, DetectDispatcher dispatcher,
                              DataSource dataSource) {
        this.detectService = detectService;
        this.dispatcher = dispatcher;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 重诊断请求（05 文档 §6.1：三选一或组合） */
    public record RerunRequest(List<String> conversationIds, String detectorVersionLt,
                               String from, String to, Integer limit) {
    }

    /** 受理结果：accepted=已投递条数，skipped=非终态/不存在 */
    public record RerunResult(int accepted, int skipped) {
    }

    /** 进度计数（内存态，进程重启清零） */
    public record RerunStatus(long accepted, long completed, long failed, long pending) {
    }

    /**
     * 圈定 + 受理：查询候选 → 校验终态 → 置 pending → 切片投递。
     * 全程只读 trace/payload；不校验会话内容，检测失败由 markFailed + SweepJob 重试兜底。
     */
    public synchronized RerunResult rerun(RerunRequest req) {
        if (!detectService.globalEnabled()) {
            throw new IllegalStateException("检测总开关已关闭（data-agent.monitor.detect.enabled=false）");
        }
        int limit = req.limit() == null || req.limit() <= 0 ? DEFAULT_LIMIT : Math.min(req.limit(), MAX_LIMIT);
        Timestamp from = parseTs(req.from(), "from");
        Timestamp to = parseTs(req.to(), "to");

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (req.conversationIds() != null && !req.conversationIds().isEmpty()) {
            List<String> ids = req.conversationIds().stream()
                    .filter(id -> id != null && !id.isBlank()).map(String::trim).distinct().toList();
            if (!ids.isEmpty()) {
                where.append(" AND conversation_id IN (")
                        .append(String.join(",", ids.stream().map(v -> "?").toList()))
                        .append(")");
                args.addAll(ids);
            }
        }
        // 圈定旧版本检测过的（detectorVersionLt，如 v2 → detector_version < 'v2'）
        if (req.detectorVersionLt() != null && !req.detectorVersionLt().isBlank()) {
            where.append(" AND detector_version IS NOT NULL AND detector_version < ?");
            args.add(req.detectorVersionLt().trim());
        }
        if (from != null) {
            where.append(" AND created_at >= ?");
            args.add(from);
        }
        if (to != null) {
            where.append(" AND created_at < ?");
            args.add(to);
        }
        where.append(" ORDER BY created_at DESC LIMIT ?");
        args.add(limit);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT session_id, status FROM agentx_monitor_session" + where, args.toArray());
        if (rows.isEmpty()) {
            return new RerunResult(0, 0);
        }
        List<String> accepted = new ArrayList<>();
        int skipped = 0;
        for (Map<String, Object> row : rows) {
            String sessionId = String.valueOf(row.get("session_id"));
            String status = String.valueOf(row.get("status"));
            if (!DetectDispatcher.isTerminal(status)) {
                skipped++; // 非终态不受理（running 由 closeSession 正常链路检测）
                continue;
            }
            // 先置 pending：队列满丢弃部分由 SweepJob 兜底（sweep 条件 detect_status<>'done' 覆盖 pending）
            try {
                jdbc.update("UPDATE agentx_monitor_session SET detect_status = 'pending' WHERE session_id = ?",
                        sessionId);
            } catch (Exception e) {
                log.warn("[detect-rerun] 置 pending 失败（跳过该行）: sessionId={}, err={}", sessionId, e.getMessage());
                skipped++;
                continue;
            }
            accepted.add(sessionId);
            acceptedCount.incrementAndGet();
        }
        // 按 50/批切片投递到现有单线程队列（与实时检测互斥串行）
        for (int i = 0; i < accepted.size(); i += SLICE) {
            List<String> slice = accepted.subList(i, Math.min(i + SLICE, accepted.size()));
            for (String sessionId : slice) {
                dispatcher.submit(sessionId, ok -> {
                    if (ok) {
                        completedCount.incrementAndGet();
                    } else {
                        failedCount.incrementAndGet();
                    }
                });
            }
        }
        log.info("[detect-rerun] 批量重诊断受理: accepted={}, skipped={}, filter(convs={}, versionLt={}, from={}, to={})",
                accepted.size(), skipped,
                req.conversationIds() == null ? 0 : req.conversationIds().size(),
                req.detectorVersionLt(), req.from(), req.to());
        return new RerunResult(accepted.size(), skipped);
    }

    /** 进度计数（可选任务口径：受理/完成/失败/pending） */
    public RerunStatus status() {
        long a = acceptedCount.get();
        long c = completedCount.get();
        long f = failedCount.get();
        return new RerunStatus(a, c, f, Math.max(0, a - c - f));
    }

    /** ISO（yyyy-MM-ddTHH:mm:ss）/ 空格分隔 / 纯日期 三种格式解析；非法抛业务参数异常 */
    private static Timestamp parseTs(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            String t = raw.trim().replace(' ', 'T');
            if (t.length() == 10) {
                t = t + "T00:00:00";
            }
            return Timestamp.valueOf(LocalDateTime.parse(t));
        } catch (Exception e) {
            throw new IllegalArgumentException("时间格式非法（" + field + "=" + raw
                    + "，应为 yyyy-MM-dd HH:mm:ss 或 yyyy-MM-ddTHH:mm:ss）");
        }
    }
}
