package com.llmetor.miniagentx.interrupt;

import com.llmetor.miniagentx.model.PauseState;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SafePoint 安全点状态机 + 打断上下文。
 *
 * <p>状态单向初始化后在 LLM_STREAMING ⇄ TOOL_EXECUTION 间切换：
 * 调 LLM 前切 LLM_STREAMING（副作用仅耗 token）；调工具前切 TOOL_EXECUTION
 * 并缓存待执行工具清单（快照可表达待执行状态）。
 *
 * <p>interrupt 标志置位后，工具完成回调据此不再续轮，由中断处理器接管收尾。
 */
public class InterruptContext {

    private volatile PauseState.SafePoint safePoint = PauseState.SafePoint.INIT;
    private volatile boolean interrupted = false;
    private volatile List<PauseState.PendingToolCall> pendingToolCalls = List.of();
    private final CopyOnWriteArrayList<Runnable> interruptCallbacks = new CopyOnWriteArrayList<>();

    public void enterLlmStreaming() {
        this.safePoint = PauseState.SafePoint.LLM_STREAMING;
        this.pendingToolCalls = List.of();
    }

    public void enterToolExecution(List<PauseState.PendingToolCall> calls) {
        this.safePoint = PauseState.SafePoint.TOOL_EXECUTION;
        this.pendingToolCalls = List.copyOf(calls);
    }

    public void markInterrupted() {
        this.interrupted = true;
    }

    public boolean isInterrupted() {
        return interrupted;
    }

    public PauseState.SafePoint getSafePoint() {
        return safePoint;
    }

    public List<PauseState.PendingToolCall> getPendingToolCalls() {
        return pendingToolCalls;
    }

    /** 注册中断回调（interrupt 时在打断线程上执行） */
    public void onInterrupt(Runnable callback) {
        interruptCallbacks.add(callback);
    }

    /** 触发中断回调（仅一次；由 AgentTaskManager 保证） */
    public void fireInterrupt() {
        markInterrupted();
        for (Runnable cb : interruptCallbacks) {
            try {
                cb.run();
            } catch (Exception ignored) {
                // 中断回调失败不阻断后续回调
            }
        }
    }
}
