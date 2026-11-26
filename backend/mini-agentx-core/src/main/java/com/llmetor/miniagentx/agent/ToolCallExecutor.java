package com.llmetor.miniagentx.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmetor.miniagentx.interrupt.HitlParkException;
import com.llmetor.miniagentx.interrupt.InterruptContext;
import com.llmetor.miniagentx.model.AgentStreamEvent;
import com.llmetor.miniagentx.model.RunnableParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;

/**
 * 工具执行器：每个 toolCall 并行执行（boundedElastic），结果按原始顺序回灌；
 * 全部完成后按顺序统一发 ToolEnd（失败也发、success=false，错误 JSON 照回灌，循环不终止）。
 *
 * <p>单工具流程：args 空→"{}" → toolMap 查找（找不到返回错误 JSON 不抛异常）→
 * toolParams 白名单注入（按工具 inputSchema properties，强制覆盖：漏填补、乱填盖）→
 * 构建 ToolContext → 发 ToolStart → call() 同步阻塞 → 成功记录结果。
 */
public class ToolCallExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolCallExecutor.class);

    /** 已按 id 合并完分片的完整工具调用 */
    public record MergedToolCall(String id, String name, String arguments) {
    }

    /** 执行结果（success=false 时 result 为错误 JSON） */
    public record ToolExecutionOutcome(String id, String name, String arguments, String result, boolean success) {
    }

    public interface AllDoneCallback {
        void accept(List<ToolExecutionOutcome> outcomesInOrder);
    }

    /** 二期 HITL：进入 60s await 前的预落盘钩子 */
    public interface PreParkHook {
        void onPrePark(String decisionId, int currentIndex, List<ToolExecutionOutcome> completedOutcomes);
    }

    /** 二期 HITL：60s 超时或停机挂起回调（截断后续工具，立即优雅落盘 PAUSED） */
    public interface ParkCallback {
        void onPark(String decisionId, MergedToolCall parkedCall,
                    List<MergedToolCall> remainingCalls, List<ToolExecutionOutcome> completedOutcomes);
    }

    /** 默认非并发安全（HITL 高危）工具集合：一轮命中多个时自动切入串行队列逐个审批 */
    public static final Set<String> DEFAULT_SERIAL_TOOLS = Set.of(
            "approveTask",
            "rejectTask",
            "partialApproveLedger",
            "submitLedgerForReview",
            "resubmitLedger"
    );

    private final ObjectMapper mapper = new ObjectMapper();
    private volatile Set<String> serialTools = DEFAULT_SERIAL_TOOLS;

    public void setSerialTools(Set<String> serialTools) {
        this.serialTools = serialTools == null ? Set.of() : Set.copyOf(serialTools);
    }

    public boolean isSerialTool(String toolName) {
        return toolName != null && serialTools.contains(toolName);
    }

    /**
     * 并发执行全部工具调用，完成（全部结束）后回调按原始顺序的结果列表。
     *
     * @param sink      事件出口（ToolStart 各自发；ToolEnd 全部完成后按序统一发，只发成功的）
     * @param emitLock  事件发射锁（与主循环共享，保证 Sinks 串行化）
     * @param executor  执行线程池（各调用并行跑）
     */
    public void executeAsync(List<MergedToolCall> calls,
                             Map<String, ToolCallback> toolMap,
                             RunnableParams params,
                             String conversationId,
                             Sinks.Many<AgentStreamEvent> sink,
                             Object emitLock,
                             ExecutorService executor,
                             AllDoneCallback allDone) {
        executeAsync(calls, toolMap, params, conversationId, null, null, sink, emitLock, executor, allDone);
    }

    /** M-SA1 扩展版：向 ToolContext 注入 parentSessionId / toolCallId / stepEmitter / interruptContext */
    public void executeAsync(List<MergedToolCall> calls,
                             Map<String, ToolCallback> toolMap,
                             RunnableParams params,
                             String conversationId,
                             String parentSessionId,
                             InterruptContext interruptCtx,
                             Sinks.Many<AgentStreamEvent> sink,
                             Object emitLock,
                             ExecutorService executor,
                             AllDoneCallback allDone) {
        executeAsync(calls, toolMap, params, conversationId, parentSessionId, interruptCtx,
                sink, emitLock, executor, null, null, allDone);
    }

    /** 二期 HITL 扩展版：支持串行队列 + 60s 挂起预落盘 + Park 截断回调 */
    public void executeAsync(List<MergedToolCall> calls,
                             Map<String, ToolCallback> toolMap,
                             RunnableParams params,
                             String conversationId,
                             String parentSessionId,
                             InterruptContext interruptCtx,
                             Sinks.Many<AgentStreamEvent> sink,
                             Object emitLock,
                             ExecutorService executor,
                             PreParkHook preParkHook,
                             ParkCallback onPark,
                             AllDoneCallback allDone) {
        boolean hasSerial = calls.stream().anyMatch(c -> isSerialTool(c.name()));
        if (hasSerial) {
            executeSerialAsync(calls, toolMap, params, conversationId, parentSessionId, interruptCtx,
                    sink, emitLock, executor, preParkHook, onPark, allDone);
            return;
        }
        int n = calls.size();
        AtomicReferenceArray<ToolExecutionOutcome> outcomes = new AtomicReferenceArray<>(n);
        AtomicInteger remaining = new AtomicInteger(n);
        Consumer<AgentStreamEvent> stepEmitter = ev -> emit(sink, emitLock, ev);

        for (int i = 0; i < n; i++) {
            final int index = i;
            final MergedToolCall call = calls.get(i);
            executor.execute(() -> {
                ToolExecutionOutcome outcome;
                try {
                    outcome = executeOne(call, toolMap, params, conversationId,
                            parentSessionId, interruptCtx, stepEmitter, sink, emitLock,
                            null, null, null, null);
                } catch (HitlParkException parkEx) {
                    // 子 Agent 内部可能触发 HITL 挂起（SubAgentTool 透传 HitlParkException）
                    if (onPark != null) {
                        List<MergedToolCall> rem = index + 1 < n ? calls.subList(index + 1, n) : List.of();
                        onPark.onPark(parkEx.getDecisionId(), call, rem, List.of());
                    }
                    return;
                }
                outcomes.set(index, outcome);
                if (remaining.decrementAndGet() == 0) {
                    // 全部完成：按原始顺序统一发 ToolEnd（失败也发，success=false，前端徽章才能落地不再空转）
                    for (int k = 0; k < n; k++) {
                        ToolExecutionOutcome o = outcomes.get(k);
                        if (o != null) {
                            emit(sink, emitLock, AgentStreamEvent.toolEnd(o.name(), o.id(), o.result(), o.success()));
                        }
                    }
                    List<ToolExecutionOutcome> ordered = new ArrayList<>(n);
                    for (int k = 0; k < n; k++) {
                        ordered.add(outcomes.get(k));
                    }
                    allDone.accept(ordered);
                }
            });
        }
    }

    /**
     * 二期 HITL 串行队列执行：按 ordinal 逐个执行，单个完成立即发射 TOOL_END；
     * 任一工具触发 HitlParkException 或 REJECT_ALL 时，立即截断后续工具绝不偷跑。
     */
    private void executeSerialAsync(List<MergedToolCall> calls,
                                    Map<String, ToolCallback> toolMap,
                                    RunnableParams params,
                                    String conversationId,
                                    String parentSessionId,
                                    InterruptContext interruptCtx,
                                    Sinks.Many<AgentStreamEvent> sink,
                                    Object emitLock,
                                    ExecutorService executor,
                                    PreParkHook preParkHook,
                                    ParkCallback onPark,
                                    AllDoneCallback allDone) {
        Consumer<AgentStreamEvent> stepEmitter = ev -> emit(sink, emitLock, ev);
        executor.execute(() -> {
            int n = calls.size();
            List<Integer> serialIndices = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (isSerialTool(calls.get(i).name())) {
                    serialIndices.add(i);
                }
            }
            int totalSerial = serialIndices.size();
            List<ToolExecutionOutcome> outcomes = new ArrayList<>(n);
            boolean rejectAllTriggered = false;

            for (int i = 0; i < n; i++) {
                MergedToolCall call = calls.get(i);
                if (interruptCtx != null && interruptCtx.isInterrupted()) {
                    return;
                }
                if (rejectAllTriggered) {
                    outcomes.add(markToolNotStarted(call, toolMap, params, sink, emitLock));
                    continue;
                }

                ToolExecutionContext.HitlQueueInfo queueInfo = buildQueueInfo(calls, serialIndices, i, totalSerial);
                final int idx = i;
                Consumer<String> preParkSaver = preParkHook == null ? null
                        : decId -> preParkHook.onPrePark(decId, idx, List.copyOf(outcomes));
                AtomicBoolean rejectAllFlag = new AtomicBoolean(false);

                ToolExecutionOutcome outcome;
                try {
                    outcome = executeOne(call, toolMap, params, conversationId,
                            parentSessionId, interruptCtx, stepEmitter, sink, emitLock,
                            queueInfo, null, preParkSaver, rejectAllFlag);
                } catch (HitlParkException parkEx) {
                    log.info("[ToolCallExecutor] HITL 工具超时挂起，截断后续 {} 个工具: tool={}, decisionId={}",
                            Math.max(0, n - 1 - i), call.name(), parkEx.getDecisionId());
                    if (onPark != null) {
                        List<MergedToolCall> rem = i + 1 < n ? calls.subList(i + 1, n) : List.of();
                        onPark.onPark(parkEx.getDecisionId(), call, rem, List.copyOf(outcomes));
                    }
                    return;
                }

                emit(sink, emitLock, AgentStreamEvent.toolEnd(outcome.name(), outcome.id(),
                        outcome.result(), outcome.success()));
                outcomes.add(outcome);
                if (rejectAllFlag.get()) {
                    rejectAllTriggered = true;
                }
            }
            allDone.accept(outcomes);
        });
    }

    /** 构造当前工具在串行审批队列中的位置与后续预览 */
    public static ToolExecutionContext.HitlQueueInfo buildQueueInfo(List<MergedToolCall> calls,
                                                                     List<Integer> serialIndices,
                                                                     int currentIdx,
                                                                     int totalSerial) {
        int pos = serialIndices.indexOf(currentIdx);
        if (pos < 0 || totalSerial <= 0) {
            return null;
        }
        List<ToolExecutionContext.HitlQueueItem> upcoming = new ArrayList<>();
        for (int k = pos + 1; k < serialIndices.size(); k++) {
            MergedToolCall next = calls.get(serialIndices.get(k));
            upcoming.add(new ToolExecutionContext.HitlQueueItem(next.id(), next.name(), next.arguments()));
        }
        return new ToolExecutionContext.HitlQueueInfo(pos + 1, totalSerial, upcoming);
    }

    /** 标记被【全部拒绝】截断的未执行工具为 TOOL_NOT_STARTED */
    public ToolExecutionOutcome markToolNotStarted(MergedToolCall call,
                                                   Map<String, ToolCallback> toolMap,
                                                   RunnableParams params,
                                                   Sinks.Many<AgentStreamEvent> sink,
                                                   Object emitLock) {
        ToolCallback callback = toolMap.get(call.name());
        String effectiveArgs = normalizeArgs(call.arguments());
        if (callback != null) {
            effectiveArgs = injectToolParams(effectiveArgs, callback, params);
        }
        emit(sink, emitLock, AgentStreamEvent.toolStart(call.name(), call.id(), maskSensitiveArgs(effectiveArgs)));
        String skippedJson = "{\"status\":\"TOOL_NOT_STARTED\",\"message\":\"前序审批已全部拒绝，后续操作已取消\"}";
        emit(sink, emitLock, AgentStreamEvent.toolEnd(call.name(), call.id(), skippedJson, false));
        String modelResult = "TOOL_NOT_STARTED: 前序操作已被用户【全部拒绝】，本工具未执行。";
        return new ToolExecutionOutcome(call.id(), call.name(), effectiveArgs, modelResult, false);
    }

    /**
     * 恢复重放通道（M3）：同步执行单个工具调用，含 ToolStart 发射与 toolParams 注入。
     * ToolEnd 不在此发（成功与否由调用方 {@code AgentLoopExecutor} 决定），
     * 失败时返回错误 JSON 的 outcome、不抛异常——与批量通道同一自愈语义。
     */
    public ToolExecutionOutcome executeSync(MergedToolCall call,
                                            Map<String, ToolCallback> toolMap,
                                            RunnableParams params,
                                            String conversationId,
                                            Sinks.Many<AgentStreamEvent> sink,
                                            Object emitLock) {
        return executeSync(call, toolMap, params, conversationId, null, null, sink, emitLock);
    }

    /** M-SA1 扩展版恢复重放：同样注入 parentSessionId / stepEmitter / interruptContext */
    public ToolExecutionOutcome executeSync(MergedToolCall call,
                                            Map<String, ToolCallback> toolMap,
                                            RunnableParams params,
                                            String conversationId,
                                            String parentSessionId,
                                            InterruptContext interruptCtx,
                                            Sinks.Many<AgentStreamEvent> sink,
                                            Object emitLock) {
        return executeSync(call, toolMap, params, conversationId, parentSessionId, interruptCtx,
                sink, emitLock, null, null, null, null);
    }

    /** 二期 HITL 恢复重放通道：支持携带 queueInfo / resumeDecisionId / preParkSaver / rejectAllFlag */
    public ToolExecutionOutcome executeSync(MergedToolCall call,
                                            Map<String, ToolCallback> toolMap,
                                            RunnableParams params,
                                            String conversationId,
                                            String parentSessionId,
                                            InterruptContext interruptCtx,
                                            Sinks.Many<AgentStreamEvent> sink,
                                            Object emitLock,
                                            ToolExecutionContext.HitlQueueInfo queueInfo,
                                            String resumeDecisionId,
                                            Consumer<String> preParkSaver,
                                            AtomicBoolean rejectAllFlag) {
        Consumer<AgentStreamEvent> stepEmitter = ev -> emit(sink, emitLock, ev);
        return executeOne(call, toolMap, params, conversationId, parentSessionId, interruptCtx,
                stepEmitter, sink, emitLock, queueInfo, resumeDecisionId, preParkSaver, rejectAllFlag);
    }

    /** 单个工具调用（同步执行，含 ToolStart 发射与 toolParams 注入） */
    private ToolExecutionOutcome executeOne(MergedToolCall call,
                                            Map<String, ToolCallback> toolMap,
                                            RunnableParams params,
                                            String conversationId,
                                            String parentSessionId,
                                            InterruptContext interruptCtx,
                                            Consumer<AgentStreamEvent> stepEmitter,
                                            Sinks.Many<AgentStreamEvent> sink,
                                            Object emitLock,
                                            ToolExecutionContext.HitlQueueInfo queueInfo,
                                            String resumeDecisionId,
                                            Consumer<String> preParkSaver,
                                            AtomicBoolean rejectAllFlag) {
        ToolCallback callback = toolMap.get(call.name());
        String effectiveArgs = normalizeArgs(call.arguments());

        if (callback != null) {
            effectiveArgs = injectToolParams(effectiveArgs, callback, params);
        }

        emit(sink, emitLock, AgentStreamEvent.toolStart(call.name(), call.id(), maskSensitiveArgs(effectiveArgs)));

        if (callback == null) {
            String err = "{\"error\":\"不存在名为 '" + call.name() + "' 的工具\",\"tool\":\"" + call.name() + "\"}";
            log.warn("工具不存在: {}", call.name());
            return new ToolExecutionOutcome(call.id(), call.name(), effectiveArgs, err, false);
        }

        String result;
        boolean success;
        try {
            Map<String, Object> ctxMap = new LinkedHashMap<>();
            ctxMap.put("userId", params.getUserId() == null ? "" : params.getUserId());
            ctxMap.put("conversationId", conversationId);
            ctxMap.put("runnableParams", params);
            ctxMap.put("toolCallId", call.id());
            if (parentSessionId != null) {
                ctxMap.put("parentSessionId", parentSessionId);
            }
            if (stepEmitter != null) {
                ctxMap.put("stepEmitter", stepEmitter);
            }
            if (interruptCtx != null) {
                ctxMap.put("interruptContext", interruptCtx);
            }
            ToolContext toolContext = new ToolContext(ctxMap);
            ToolExecutionContext.set(params, conversationId, call.id(), stepEmitter,
                    queueInfo, resumeDecisionId, preParkSaver, rejectAllFlag);
            try {
                result = callback.call(effectiveArgs, toolContext);
            } finally {
                ToolExecutionContext.clear();
            }
            if (result == null) {
                result = "{\"error\":\"工具返回空结果\",\"tool\":\"" + call.name() + "\"}";
                success = false;
            } else {
                success = true;
            }
        } catch (HitlParkException parkEx) {
            throw parkEx;
        } catch (Exception e) {
            HitlParkException parkEx = unwrapHitlParkException(e);
            if (parkEx != null) {
                throw parkEx;
            }
            log.warn("工具执行失败: {} - {}", call.name(), e.getMessage());
            result = errorJson(call.name(), "工具调用失败：" + safeMessage(e));
            success = false;
        }
        return new ToolExecutionOutcome(call.id(), call.name(), effectiveArgs, result, success);
    }

    /**
     * toolParams 白名单注入：解析工具 inputSchema 的 properties 键，
     * toolParams 中命中键名的强制覆盖进 args（LLM 漏填→补、乱填→覆盖），不暴露给 LLM。
     * 任一环节解析失败保持原 args 不变（fail-safe 不 fail-stop）。
     */
    String injectToolParams(String arguments, ToolCallback callback, RunnableParams params) {
        if (params == null || params.getToolParams().isEmpty()) {
            return arguments;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> argsMap = mapper.readValue(arguments, Map.class);
            JsonNode schema = mapper.readTree(callback.getToolDefinition().inputSchema());
            JsonNode props = schema.path("properties");
            Iterator<String> keys = props.fieldNames();
            boolean changed = false;
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = params.getToolParams().get(key);
                if (value != null) {
                    argsMap.put(key, value);
                    changed = true;
                }
            }
            return changed ? mapper.writeValueAsString(argsMap) : arguments;
        } catch (Exception e) {
            log.debug("toolParams 注入失败，保持原参数: {}", e.getMessage());
            return arguments;
        }
    }

    /** 对外发射 TOOL_START 时脱敏敏感凭证字段（token/password/secret），防止明文暴露到前端 UI */
    private String maskSensitiveArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank() || !argsJson.contains("\"")) {
            return argsJson;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = mapper.readValue(argsJson, Map.class);
            boolean masked = false;
            for (String key : List.of("token", "accessToken", "password", "secret", "authorization")) {
                if (map.containsKey(key) && map.get(key) != null && !String.valueOf(map.get(key)).isBlank()) {
                    map.put(key, "***");
                    masked = true;
                }
            }
            return masked ? mapper.writeValueAsString(map) : argsJson;
        } catch (Exception ignored) {
            return argsJson;
        }
    }

    private static String normalizeArgs(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "{}";
        }
        return arguments.trim();
    }

    private static String errorJson(String toolName, String reason) {
        String safe = reason == null ? "未知原因" : reason.replace("\"", "'");
        return "{\"error\":\"" + toolName + " " + safe + "\",\"tool\":\"" + toolName + "\"}";
    }

    private static String safeMessage(Exception e) {
        String msg = e.getMessage();
        return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg;
    }

    /** 穿透 Spring AI MethodToolCallback 等包装异常（如 ToolExecutionException），还原 HitlParkException */
    private static HitlParkException unwrapHitlParkException(Throwable t) {
        Throwable cur = t;
        for (int i = 0; i < 12 && cur != null; i++) {
            if (cur instanceof HitlParkException hpe) {
                return hpe;
            }
            cur = cur.getCause();
        }
        return null;
    }

    private static void emit(Sinks.Many<AgentStreamEvent> sink, Object lock, AgentStreamEvent event) {
        synchronized (lock) {
            sink.tryEmitNext(event);
        }
    }
}
