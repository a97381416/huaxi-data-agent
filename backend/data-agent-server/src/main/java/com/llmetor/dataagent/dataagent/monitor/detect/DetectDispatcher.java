package com.llmetor.dataagent.dataagent.monitor.detect;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 检测派发器（03 方案 §4，D3/D1）：closeSession 终态回填后投递异步检测——
 * 单线程池 + 有界队列（200），自吞异常不阻断主流程（与 Collector 同纪律）；
 * 队列满直接丢弃并 warn（SweepJob 每 5 分钟兜底扫「终态但未检测」，不丢检测机会）。
 */
@Slf4j
@Service
public class DetectDispatcher {

    private final AnomalyDetectService detectService;
    private final ThreadPoolExecutor executor;

    public DetectDispatcher(AnomalyDetectService detectService) {
        this.detectService = detectService;
        this.executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(200),
                r -> {
                    Thread t = new Thread(r, "monitor-detect");
                    t.setDaemon(true);
                    return t;
                },
                (r, pool) -> log.warn("[detect] 检测队列已满，丢弃本次投递（SweepJob 会兜底）"));
    }

    /**
     * closeSession 挂点（MonitorTraceCollector 终态回填后调用）：仅终态投递，running 不检测；
     * yaml 总开关关闭时整个链路跳过（进程级熔断）。
     */
    public void submitAfterClose(String sessionId, String status) {
        try {
            if (!detectService.globalEnabled() || !isTerminal(status)) {
                return;
            }
            submit(sessionId);
        } catch (Exception e) {
            log.warn("[detect] 投递异常（不影响主流程）: sessionId={}, err={}", sessionId, e.getMessage());
        }
    }

    /** 异步检测一次；执行期异常已在 service 内置 detect_status='failed'，此处不再外抛 */
    public void submit(String sessionId) {
        submit(sessionId, null);
    }

    /**
     * 异步检测一次 + 完成回调（V2 批量重诊断进度计数用）：ok=true 检测完成落库成功，
     * false=未完成（业务非法状态/落库失败——已置 failed 待 SweepJob 重试）。
     * 回调异常自吞，不影响检测主流程；队列满时回调不执行（等 SweepJob 兜底）。
     */
    public void submit(String sessionId, java.util.function.Consumer<Boolean> onFinish) {
        try {
            executor.execute(() -> {
                boolean ok = false;
                try {
                    detectService.detect(sessionId);
                    ok = true;
                } catch (Exception e) {
                    // service.detect 对业务非法状态（进行中/不存在/开关关）抛 IllegalStateException；
                    // 落库失败已置 failed，这里只记日志
                    log.debug("[detect] 异步检测未完成: sessionId={}, err={}", sessionId, e.getMessage());
                }
                if (onFinish != null) {
                    try {
                        onFinish.accept(ok);
                    } catch (Exception e) {
                        log.warn("[detect] 检测完成回调异常: sessionId={}, err={}", sessionId, e.getMessage());
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // 队列满已在 rejected handler 记 warn；等 SweepJob 兜底
        }
    }

    /** 终态 = completed / error / interrupted（running 不检测） */
    public static boolean isTerminal(String status) {
        return status != null && List.of("completed", "error", "interrupted").contains(status);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
