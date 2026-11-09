package com.llmetor.miniagentx.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmetor.miniagentx.model.AgentStreamEvent;
import com.llmetor.miniagentx.model.RunnableParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private final ObjectMapper mapper = new ObjectMapper();

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
        int n = calls.size();
        AtomicReferenceArray<ToolExecutionOutcome> outcomes = new AtomicReferenceArray<>(n);
        AtomicInteger remaining = new AtomicInteger(n);

        for (int i = 0; i < n; i++) {
            final int index = i;
            final MergedToolCall call = calls.get(i);
            executor.execute(() -> {
                ToolExecutionOutcome outcome = executeOne(call, toolMap, params, conversationId,
                        sink, emitLock);
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
        return executeOne(call, toolMap, params, conversationId, sink, emitLock);
    }

    /** 单个工具调用（同步执行，含 ToolStart 发射与 toolParams 注入） */
    private ToolExecutionOutcome executeOne(MergedToolCall call,
                                            Map<String, ToolCallback> toolMap,
                                            RunnableParams params,
                                            String conversationId,
                                            Sinks.Many<AgentStreamEvent> sink,
                                            Object emitLock) {
        ToolCallback callback = toolMap.get(call.name());
        String effectiveArgs = normalizeArgs(call.arguments());

        if (callback != null) {
            effectiveArgs = injectToolParams(effectiveArgs, callback, params);
        }

        emit(sink, emitLock, AgentStreamEvent.toolStart(call.name(), call.id(), effectiveArgs));

        if (callback == null) {
            String err = "{\"error\":\"不存在名为 '" + call.name() + "' 的工具\",\"tool\":\"" + call.name() + "\"}";
            log.warn("工具不存在: {}", call.name());
            return new ToolExecutionOutcome(call.id(), call.name(), effectiveArgs, err, false);
        }

        String result;
        boolean success;
        try {
            ToolContext toolContext = new ToolContext(Map.of(
                    "userId", params.getUserId() == null ? "" : params.getUserId(),
                    "conversationId", conversationId,
                    "runnableParams", params));
            result = callback.call(effectiveArgs, toolContext);
            if (result == null) {
                result = "{\"error\":\"工具返回空结果\",\"tool\":\"" + call.name() + "\"}";
                success = false;
            } else {
                success = true;
            }
        } catch (Exception e) {
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

    private static void emit(Sinks.Many<AgentStreamEvent> sink, Object lock, AgentStreamEvent event) {
        synchronized (lock) {
            sink.tryEmitNext(event);
        }
    }
}
