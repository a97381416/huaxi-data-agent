package com.llmetor.miniagentx.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 运行中任务登记与打断（对齐原框架 AgentTaskManager）。
 *
 * <p>registerTask putIfAbsent——同会话互斥（CONCURRENT_EXECUTION）；
 * 可跨 Agent 共享单例（stopStream 能跨请求找到运行中任务）。
 *
 * <p>两种停止：
 * <ul>
 *   <li>interrupt（可恢复暂停）：dispose 订阅 → 触发执行器中断回调（flush 快照 → Paused 事件）</li>
 *   <li>stopTask（强制停止丢状态）：dispose 订阅 → sink.complete()，不产生 Paused</li>
 * </ul>
 */
public class AgentTaskManager {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskManager.class);

    /** 执行器注册的中断回调：入参 interruptMessage */
    public interface InterruptHandler extends Consumer<String> {
    }

    private static final class TaskInfo {
        final Sinks.Many<?> sink;
        volatile Disposable disposable;
        final InterruptHandler interruptHandler;

        TaskInfo(Sinks.Many<?> sink, InterruptHandler interruptHandler) {
            this.sink = sink;
            this.interruptHandler = interruptHandler;
        }
    }

    private final Map<String, TaskInfo> tasks = new ConcurrentHashMap<>();

    /**
     * 登记任务；同会话已有运行任务 → 抛 IllegalStateException（上层转 CONCURRENT 执行冲突）。
     */
    public void registerTask(String conversationId, Sinks.Many<?> sink, InterruptHandler interruptHandler) {
        TaskInfo existing = tasks.putIfAbsent(conversationId, new TaskInfo(sink, interruptHandler));
        if (existing != null) {
            throw new IllegalStateException("会话已有进行中的执行: " + conversationId);
        }
    }

    public void bindDisposable(String conversationId, Disposable disposable) {
        TaskInfo info = tasks.get(conversationId);
        if (info != null) {
            info.disposable = disposable;
        }
    }

    /** 执行器结束时注销（complete/error/paused 之后） */
    public void unregisterTask(String conversationId) {
        tasks.remove(conversationId);
    }

    public boolean hasRunningTask(String conversationId) {
        return tasks.containsKey(conversationId);
    }

    public int getRunningTaskCount() {
        return tasks.size();
    }

    /**
     * 可恢复中断：dispose 订阅（停流）→ 执行器回调（flush 输出→快照→Paused）。
     *
     * @return true=找到任务并触发；false=无运行任务
     */
    public boolean interrupt(String conversationId, String message) {
        TaskInfo info = tasks.remove(conversationId);
        if (info == null) {
            return false;
        }
        if (info.disposable != null && !info.disposable.isDisposed()) {
            info.disposable.dispose();
        }
        log.info("任务中断: convId={}, message={}", conversationId, message);
        if (info.interruptHandler != null) {
            info.interruptHandler.accept(message);
        }
        return true;
    }

    /**
     * 强制停止（丢弃状态，不产生 Paused 事件）。
     */
    public boolean stopTask(String conversationId) {
        TaskInfo info = tasks.remove(conversationId);
        if (info == null) {
            return false;
        }
        if (info.disposable != null && !info.disposable.isDisposed()) {
            info.disposable.dispose();
        }
        info.sink.tryEmitComplete();
        return true;
    }
}
