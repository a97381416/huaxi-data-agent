package com.llmetor.dataagent.dataagent.hitl;

import com.llmetor.dataagent.dataagent.hitl.DecisionModels.DecisionEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static com.llmetor.dataagent.dataagent.hitl.DecisionModels.*;

/**
 * 二期 HITL 快路径原地唤醒注册表：
 * <ul>
 *   <li>内存 {@link CompletableFuture} 毫秒级原地唤醒为主；</li>
 *   <li>2 秒轻量 DB 状态轮询兜底（天然兼容多节点集群部署，零额外中间件依赖）；</li>
 *   <li>实现 {@link SmartLifecycle} 优雅停机钩子：收到停机信号时立即唤醒所有等待线程返回 {@code PARKED}，
 *       确保体面落盘 {@code agentx_pause_state} 并推送 {@code PAUSED} 后再停机。</li>
 * </ul>
 */
@Slf4j
@Component
public class DecisionAwaitRegistry implements SmartLifecycle {

    private static final long DB_POLL_INTERVAL_MS = 2000L;

    public enum AwaitState {
        APPROVED,
        REJECTED,
        REJECT_ALL,
        PARKED,
        EXPIRED,
        SUPERSEDED
    }

    public record AwaitResult(AwaitState state, String option, String userWords, String grantId) {
        public static AwaitResult approved(String option, String grantId) {
            return new AwaitResult(AwaitState.APPROVED, option, null, grantId);
        }

        public static AwaitResult rejected(String option, String userWords) {
            AwaitState s = OPTION_REJECT_ALL.equals(option) ? AwaitState.REJECT_ALL : AwaitState.REJECTED;
            return new AwaitResult(s, option, userWords, null);
        }

        public static AwaitResult parked() {
            return new AwaitResult(AwaitState.PARKED, null, null, null);
        }

        public static AwaitResult expired() {
            return new AwaitResult(AwaitState.EXPIRED, null, null, null);
        }

        public static AwaitResult superseded() {
            return new AwaitResult(AwaitState.SUPERSEDED, null, null, null);
        }
    }

    private final ConcurrentHashMap<String, CompletableFuture<AwaitResult>> waiters = new ConcurrentHashMap<>();
    private volatile boolean running = false;
    private volatile boolean shuttingDown = false;

    /**
     * 工具线程原地阻塞等待人工决策（最多 timeoutSeconds 秒）。
     * 每 2 秒检查一次 DB 兜底（兼容多实例部署）。
     */
    public AwaitResult registerAndAwait(String decisionId, long timeoutSeconds,
                                        Function<String, DecisionEntity> dbLookup) {
        if (shuttingDown) {
            return AwaitResult.parked();
        }
        long waitSec = Math.max(1L, timeoutSeconds);
        CompletableFuture<AwaitResult> future = new CompletableFuture<>();
        waiters.put(decisionId, future);
        long deadlineMs = System.currentTimeMillis() + waitSec * 1000L;

        try {
            // 注册后先查一次 DB，防止极小时间窗内 decide() 已先于 register 发生
            AwaitResult early = checkDbState(decisionId, dbLookup);
            if (early != null) {
                return early;
            }

            while (!shuttingDown) {
                long remainingMs = deadlineMs - System.currentTimeMillis();
                if (remainingMs <= 0) {
                    return AwaitResult.parked();
                }
                long stepMs = Math.min(remainingMs, DB_POLL_INTERVAL_MS);
                try {
                    return future.get(stepMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    // 2 秒兜底轮询：检查是否由其他节点完成或已被取代/过期
                    AwaitResult dbRes = checkDbState(decisionId, dbLookup);
                    if (dbRes != null) {
                        return dbRes;
                    }
                }
            }
            return AwaitResult.parked();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return AwaitResult.parked();
        } catch (Exception e) {
            log.warn("[hitl-await] 等待异常: decisionId={}, err={}", decisionId, e.getMessage());
            return AwaitResult.parked();
        } finally {
            waiters.remove(decisionId, future);
        }
    }

    /**
     * 尝试原地唤醒等待中的工具线程。
     *
     * @return true=命中在线线程并唤醒（快路径 INLINE_RESUMED）；false=无在线等待线程（走慢路径 NEED_RESUME）
     */
    public boolean tryComplete(String decisionId, AwaitResult result) {
        if (decisionId == null || result == null) {
            return false;
        }
        CompletableFuture<AwaitResult> future = waiters.remove(decisionId);
        if (future == null) {
            return false;
        }
        boolean completed = future.complete(result);
        if (completed) {
            log.info("[hitl-await] 快路径原地唤醒成功: decisionId={}, state={}", decisionId, result.state());
        }
        return completed;
    }

    public boolean isWaiting(String decisionId) {
        return decisionId != null && waiters.containsKey(decisionId);
    }

    private AwaitResult checkDbState(String decisionId, Function<String, DecisionEntity> dbLookup) {
        if (dbLookup == null) {
            return null;
        }
        try {
            DecisionEntity e = dbLookup.apply(decisionId);
            if (e == null) {
                return null;
            }
            return switch (e.status()) {
                case STATUS_APPROVED, STATUS_AUTO_APPROVED_BY_GRANT ->
                        AwaitResult.approved(e.decisionOption(), e.grantId());
                case STATUS_REJECTED ->
                        AwaitResult.rejected(e.decisionOption(), e.userWords());
                case STATUS_EXPIRED -> AwaitResult.expired();
                case STATUS_SUPERSEDED -> AwaitResult.superseded();
                default -> {
                    if (e.expiresAt() != null && LocalDateTime.now().isAfter(e.expiresAt())) {
                        yield AwaitResult.expired();
                    }
                    yield null;
                }
            };
        } catch (Exception ex) {
            return null;
        }
    }

    // ==================== SmartLifecycle 优雅停机钩子 ====================

    @Override
    public void start() {
        this.running = true;
        this.shuttingDown = false;
    }

    @Override
    public void stop() {
        this.shuttingDown = true;
        this.running = false;
        if (!waiters.isEmpty()) {
            log.info("[hitl-await] 收到停机信号，主动唤醒 {} 个等待中的工具线程进入优雅挂起", waiters.size());
            for (Map.Entry<String, CompletableFuture<AwaitResult>> entry : waiters.entrySet()) {
                entry.getValue().complete(AwaitResult.parked());
            }
            waiters.clear();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // 高优先级：停机时早于数据源与线程池关闭
        return Integer.MAX_VALUE;
    }
}
