package com.llmetor.dataagent.dataagent.monitor.detect;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;

/**
 * 检测兜底扫描（03 方案 §4，D3）：每 5 分钟扫「终态但未检测」的提问行限量 50 条补检测——
 * 异步投递丢队列 / 检测失败（detect_status='failed' 下轮重试）/ 历史存量（detect_status 为空，
 * 显示「未检测」）三条路最终都汇到这里；单问逐条同步检测（<1s 量级），单条失败不影响其余。
 */
@Slf4j
@Service
public class DetectSweepJob {

    /** 每轮最多补检条数（方案口径限量 50） */
    private static final int BATCH = 50;

    private final AnomalyDetectService detectService;
    private final JdbcTemplate jdbc;

    public DetectSweepJob(AnomalyDetectService detectService, DataSource dataSource) {
        this.detectService = detectService;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 每 5 分钟（fixedDelay 从上轮结束计，避免堆积时重叠跑）；启动 90s 后首轮 */
    @Scheduled(fixedDelay = 300_000, initialDelay = 90_000)
    public void sweep() {
        try {
            if (!detectService.globalEnabled()) {
                return;
            }
            List<String> sessionIds = jdbc.queryForList("""
                    SELECT session_id FROM agentx_monitor_session
                    WHERE status IN ('completed', 'error', 'interrupted')
                      AND COALESCE(detect_status, '') <> 'done'
                    ORDER BY created_at DESC LIMIT %d
                    """.formatted(BATCH), String.class);
            if (sessionIds.isEmpty()) {
                return;
            }
            int ok = 0;
            for (String sessionId : sessionIds) {
                try {
                    detectService.detect(sessionId);
                    ok++;
                } catch (Exception e) {
                    log.debug("[detect-sweep] 单问检测未完成（下轮重试）: sessionId={}, err={}",
                            sessionId, e.getMessage());
                }
            }
            log.info("[detect-sweep] 兜底扫描完成: 本轮待检 {} 条，成功 {} 条", sessionIds.size(), ok);
        } catch (Exception e) {
            log.warn("[detect-sweep] 兜底扫描异常（下轮重试）: {}", e.getMessage());
        }
    }
}
