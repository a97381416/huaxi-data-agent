package com.llmetor.miniagentx.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmetor.miniagentx.interrupt.InterruptContext;
import com.llmetor.miniagentx.model.AgentStreamEvent;
import com.llmetor.miniagentx.model.PauseState;
import com.llmetor.miniagentx.model.RunnableParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 通用有状态子 Agent 工具桥接器（M-SA1 / M-SA2）。
 *
 * <p>核心职责（三大铁律落地）：
 * <ul>
 *   <li><b>状态物理隔离 + 串行锁</b>：派生子键 {@code subConvId = rootConvId + "::" + agentCode}，
 *       同一 {@code subConvId} 串行互斥，避免同轮双调冲突或互相覆盖 working_messages；</li>
 *   <li><b>跨线程凭证继承</b>：显式复制父 {@code RunnableParams} 的 userId 与 toolParams
 *       （含 token / tenantId），并将 toolParams.conversationId 强绑为外层 rootConvId；</li>
 *   <li><b>流透传 + 终态拦截</b>：子事件打上 {@code (agentCode, parentToolCallId)} 实时写入父 sink，
 *       严格拦截子 {@code COMPLETE / PAUSED} 不外泄，确保父流终态唯一；</li>
 *   <li><b>硬超时保护</b>：默认 180s 超时自动终止子任务并返回结构化超时 JSON，不挂死父循环；</li>
 *   <li><b>级联中断与智能恢复</b>：订阅父 {@code InterruptContext}，父停子必停；
 *       存在子快照且 {@code parentToolCallId} 匹配时续跑，不匹配则丢弃旧快照防诈尸。</li>
 * </ul>
 */
public class SubAgentTool implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(SubAgentTool.class);

    public static final long DEFAULT_TIMEOUT_MS = 180_000L;

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "task": {
                  "type": "string",
                  "description": "委派给子助手的具体任务说明（请自包含，原样带齐项目名、阶段、单号、筛选条件及用户上传文件或确认令牌等信息）"
                }
              },
              "required": ["task"]
            }
            """;

    /** 同窗口同子 Agent 串行互斥锁（key = subConvId） */
    private static final ConcurrentHashMap<String, Object> SUB_LOCKS = new ConcurrentHashMap<>();

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolDefinition definition;
    private final String agentCode;
    private final Function<RunnableParams, ReactAgent> agentFactory;
    private final long timeoutMs;

    public SubAgentTool(String toolName, String agentCode, String description,
                        Supplier<ReactAgent> agentSupplier) {
        this(toolName, agentCode, description, p -> agentSupplier.get(), DEFAULT_TIMEOUT_MS);
    }

    public SubAgentTool(String toolName, String agentCode, String description,
                        Supplier<ReactAgent> agentSupplier, long timeoutMs) {
        this(toolName, agentCode, description, p -> agentSupplier.get(), timeoutMs);
    }

    public SubAgentTool(String toolName, String agentCode, String description,
                        Function<RunnableParams, ReactAgent> agentFactory, long timeoutMs) {
        this.agentCode = agentCode;
        this.agentFactory = agentFactory;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        this.definition = ToolDefinition.builder()
                .name(toolName)
                .description(description)
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    public String getAgentCode() {
        return agentCode;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput, ToolContext toolContext) {
        String task = extractTask(toolInput);
        Map<String, Object> ctx = toolContext != null ? toolContext.getContext() : Map.of();

        RunnableParams parentParams = (RunnableParams) ctx.get("runnableParams");
        String rootConvId = parentParams != null
                ? parentParams.effectiveRootConversationId()
                : String.valueOf(ctx.getOrDefault("conversationId", "sub_orphan"));
        String userId = parentParams != null
                ? parentParams.getUserId()
                : String.valueOf(ctx.getOrDefault("userId", ""));
        String parentSessionId = (String) ctx.get("parentSessionId");
        String parentToolCallId = (String) ctx.get("toolCallId");
        Consumer<AgentStreamEvent> stepEmitter = (Consumer<AgentStreamEvent>) ctx.get("stepEmitter");
        InterruptContext parentInterruptCtx = (InterruptContext) ctx.get("interruptContext");

        String subConvId = rootConvId + "::" + agentCode;

        // 跨线程凭证强制继承：复制父 toolParams，并把 conversationId 强绑为外层 rootConvId
        Map<String, Object> inheritedToolParams = new LinkedHashMap<>();
        if (parentParams != null && parentParams.getToolParams() != null) {
            inheritedToolParams.putAll(parentParams.getToolParams());
        }
        inheritedToolParams.put("conversationId", rootConvId);
        if (userId != null && !userId.isBlank() && !inheritedToolParams.containsKey("userId")) {
            inheritedToolParams.put("userId", userId);
        }

        RunnableParams subParams = RunnableParams.builder()
                .conversationId(subConvId)
                .userId(userId)
                .customParams(parentParams != null ? parentParams.getCustomParams() : Map.of())
                .toolParams(inheritedToolParams)
                .subAgent(true)
                .agentCode(agentCode)
                .rootConversationId(rootConvId)
                .parentSessionId(parentSessionId)
                .parentToolCallId(parentToolCallId)
                .build();

        Object lock = SUB_LOCKS.computeIfAbsent(subConvId, k -> new Object());
        synchronized (lock) {
            return executeSerial(task, subConvId, subParams, parentToolCallId, stepEmitter, parentInterruptCtx);
        }
    }

    private String executeSerial(String task,
                                 String subConvId,
                                 RunnableParams subParams,
                                 String parentToolCallId,
                                 Consumer<AgentStreamEvent> stepEmitter,
                                 InterruptContext parentInterruptCtx) {
        if (parentInterruptCtx != null && parentInterruptCtx.isInterrupted()) {
            return "{\"status\":\"PAUSED\",\"agentCode\":\"" + agentCode + "\",\"message\":\"父任务已中断\"}";
        }

        ReactAgent subAgent = agentFactory.apply(subParams);

        // 级联中断挂钩：父停子必停
        AtomicBoolean cascadedInterrupt = new AtomicBoolean(false);
        if (parentInterruptCtx != null) {
            parentInterruptCtx.onInterrupt(() -> {
                cascadedInterrupt.set(true);
                try {
                    subAgent.interrupt(subConvId, "父任务中断级联停止子助手");
                } catch (Exception e) {
                    log.warn("[SubAgentTool] 级联中断子任务失败: subConvId={}", subConvId, e);
                }
            });
        }

        // 智能恢复分流：仅当子快照存在且 parentToolCallId 匹配当前调用时续跑；否则清理旧子快照防诈尸
        Flux<AgentStreamEvent> subFlux;
        PauseState existingSnapshot = subAgent.getInterruptedState(subConvId);
        if (existingSnapshot != null) {
            String snapParentCallId = existingSnapshot.getParams() != null
                    ? existingSnapshot.getParams().getParentToolCallId() : null;
            if (parentToolCallId != null && parentToolCallId.equals(snapParentCallId)) {
                log.info("[SubAgentTool] 命中匹配子快照，从断点续跑: subConvId={}, parentToolCallId={}",
                        subConvId, parentToolCallId);
                subFlux = subAgent.resumeStream(subConvId, null);
            } else {
                log.info("[SubAgentTool] 旧子快照 parentToolCallId 不匹配（old={}, cur={}），丢弃旧快照重新执行: subConvId={}",
                        snapParentCallId, parentToolCallId, subConvId);
                subAgent.discardInterruptedState(subConvId);
                subFlux = subAgent.stream(task, subParams);
            }
        } else {
            subFlux = subAgent.stream(task, subParams);
        }

        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder roundText = new StringBuilder();
        StringBuilder allText = new StringBuilder();
        AtomicReference<AgentStreamEvent.Type> terminalType = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        Disposable disposable = subFlux.subscribe(
                ev -> {
                    AgentStreamEvent.Type type = ev.getType();
                    if (type == AgentStreamEvent.Type.COMPLETE || type == AgentStreamEvent.Type.PAUSED) {
                        // 铁律②：严格拦截子终态，绝不推入父 sink
                        terminalType.set(type);
                        latch.countDown();
                        return;
                    }
                    // 透传子过程事件并打上归属标记
                    if (stepEmitter != null) {
                        stepEmitter.accept(ev.withSource(agentCode, parentToolCallId));
                    }
                    if (type == AgentStreamEvent.Type.TEXT && ev.getContent() != null) {
                        roundText.append(ev.getContent());
                        allText.append(ev.getContent());
                    } else if (type == AgentStreamEvent.Type.TOOL_END) {
                        // 进入工具回灌后的下一轮前清空临时段，优先保留子 Agent 最后一轮的总结文本
                        roundText.setLength(0);
                    }
                },
                err -> {
                    errorRef.set(err);
                    latch.countDown();
                },
                latch::countDown
        );

        try {
            boolean finished = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                log.warn("[SubAgentTool] 子助手执行超时({}ms)，强制停止: subConvId={}", timeoutMs, subConvId);
                disposable.dispose();
                try {
                    subAgent.stopStream(subConvId);
                } catch (Exception e) {
                    log.warn("[SubAgentTool] 超时停止子任务异常: subConvId={}", subConvId, e);
                }
                return "{\"error\":\"子助手执行超时（超过 " + timeoutMs + "ms），已自动终止子任务\",\"agentCode\":\""
                        + agentCode + "\",\"timeout\":true}";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            disposable.dispose();
            subAgent.stopStream(subConvId);
            return "{\"error\":\"子助手等待被中断\",\"agentCode\":\"" + agentCode + "\"}";
        } finally {
            if (!disposable.isDisposed()) {
                disposable.dispose();
            }
        }

        if (cascadedInterrupt.get() || terminalType.get() == AgentStreamEvent.Type.PAUSED) {
            return "{\"status\":\"PAUSED\",\"agentCode\":\"" + agentCode + "\",\"message\":\"子助手已随主任务暂停\"}";
        }
        if (errorRef.get() != null) {
            String msg = errorRef.get().getMessage();
            return "{\"error\":\"子助手执行异常：" + (msg == null ? "未知错误" : msg.replace("\"", "'"))
                    + "\",\"agentCode\":\"" + agentCode + "\"}";
        }

        String finalAnswer = roundText.toString().trim();
        if (finalAnswer.isEmpty()) {
            finalAnswer = allText.toString().trim();
        }
        return finalAnswer.isEmpty() ? "子助手已完成任务（无附加文本输出）。" : finalAnswer;
    }

    private String extractTask(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return "";
        }
        try {
            JsonNode node = mapper.readTree(toolInput);
            if (node.isObject() && node.has("task")) {
                return node.path("task").asText("");
            }
            if (node.isTextual()) {
                return node.asText("");
            }
        } catch (Exception ignored) {
            // 非 JSON 时直接作为任务文本
        }
        return toolInput.trim();
    }
}
