package com.llmetor.miniagentx.agent;

import com.llmetor.miniagentx.context.ContextCompactor;
import com.llmetor.miniagentx.context.ContextReloadTool;
import com.llmetor.miniagentx.context.InMemoryOffloadStore;
import com.llmetor.miniagentx.context.OffloadStore;
import com.llmetor.miniagentx.interrupt.InterruptContext;
import com.llmetor.miniagentx.interrupt.PauseStateStore;
import com.llmetor.miniagentx.memory.AgentxSessionStore;
import com.llmetor.miniagentx.memory.MessageJsonSerializer;
import com.llmetor.miniagentx.model.AgentResult;
import com.llmetor.miniagentx.model.AgentStreamEvent;
import com.llmetor.miniagentx.model.PauseState;
import com.llmetor.miniagentx.model.RunnableParams;
import com.llmetor.miniagentx.model.ThinkingMode;
import com.llmetor.miniagentx.model.think.ThinkTagParser;
import com.llmetor.miniagentx.tools.search.DeferredToolRegistry;
import com.llmetor.miniagentx.tools.search.ToolSearchTool;
import com.llmetor.miniagentx.trace.TraceManager;
import com.llmetor.miniagentx.utils.JsonRepairUtil;
import com.llmetor.miniagentx.utils.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ReAct 主循环执行器（一次 stream / resumeStream 调用一个实例）。
 *
 * <p>一轮 = 「LLM 流式调用 → chunk 聚合 → 分流（纯文本终止 / 工具调用续跑）」。
 * 四种停止条件：① 一轮内无 tool call（自然终止）② 达 maxRounds → forceFinal
 * 无工具强答 ③ interrupt → Paused ④ LLM 流错误且重试耗尽 → Error+Complete。
 *
 * <p>落库策略：循环期间零 DB 写入，终态一次落库（tryMarkPersisted CAS 幂等，
 * doFinally 兜底按 CANCEL 处理）。事件发射经共享锁串行化。
 *
 * <p>M3 恢复语义（resumeState 非空时从 PauseState 快照续跑，对应 docs/03 M3）：
 * <ul>
 *   <li>resumeQuery 为空：pendingToolCalls 本地真实重放（不重调 LLM），随后继续循环</li>
 *   <li>resumeQuery 非空：不重放工具（注入"已跳过"占位），追加澄清语包装的用户消息，
 *       由模型基于快照上下文自决策续跑</li>
 *   <li>恢复轮沿快照的 sessionId 落库（同一逻辑轮次），非 Paused 终态时删除快照</li>
 * </ul>
 */
public class AgentLoopExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopExecutor.class);

    private final ChatModel chatModel;
    private final String instructions;
    private final List<ToolCallback> tools;
    private final Map<String, ToolCallback> toolMap;
    private final int maxRounds;
    private final int maxRetries;
    private final long retryIntervalMs;
    private final ThinkingMode thinkingMode;
    private final AgentTaskManager taskManager;
    private final AgentxSessionStore sessionStore;   // null=不落库
    private final TraceManager traceManager;         // null=不审计
    private final PauseStateStore stateStore;        // null=中断不存快照
    private final MessageJsonSerializer serializer = new MessageJsonSerializer();
    private final ToolCallExecutor toolExecutor = new ToolCallExecutor();
    private final ExecutorService toolPool = Executors.newCachedThreadPool();

    private final String query;
    private final RunnableParams params;
    private final BeanOutputConverter<?> outputConverter;
    /** 非空 = 从快照恢复执行（resumeStream 通道） */
    private final PauseState resumeState;
    /** 恢复时用户补充消息（空 = 纯恢复） */
    private final String resumeQuery;

    // ==================== M7 上下文工程 ====================
    /** 延迟工具注册表（null = 不启用 tool_search，全部工具常驻） */
    private final DeferredToolRegistry deferredRegistry;
    /** 每请求发现会话：发现的工具请求内跨轮累积、请求间隔离 */
    private final DeferredToolRegistry.Session deferredSession;
    /** tool_search 工具（延迟目录为空时为 null） */
    private final ToolCallback toolSearchTool;
    /** 上下文压缩器（null = 不压缩） */
    private final ContextCompactor compactor;
    /** offload 存取（压缩启用时非空；context_reload 与取回共用） */
    private final OffloadStore offloadStore;
    /** context_reload 工具（压缩启用时非空） */
    private final ToolCallback contextReloadTool;
    /** 原始消息链（只增不减，永不被压缩污染）——original 视图落库来源 */
    private final List<Message> originalChain = new ArrayList<>();

    private final Sinks.Many<AgentStreamEvent> sink =
            Sinks.many().unicast().onBackpressureBuffer();
    private final Object emitLock = new Object();
    private final List<Message> messages = new ArrayList<>();
    private final Set<String> respondedToolCallIds = new LinkedHashSet<>();
    private final InterruptContext interruptCtx = new InterruptContext();
    private final AtomicBoolean persisted = new AtomicBoolean(false);
    private final AtomicBoolean terminalEmitted = new AtomicBoolean(false);
    private final AtomicReference<AgentResult> resultRef = new AtomicReference<>();

    private int historySize = 0;
    private int round = 0;
    private int retries = 0;
    private long totalPromptTokens = 0;
    private long totalCompletionTokens = 0;
    /** 首轮开始时间戳（毫秒）；0 = 尚未开跑。COMPLETE 事件据此带总耗时 */
    private long startAtMs = 0L;
    private String sessionId = SnowflakeIdGenerator.nextIdStr();
    private volatile RoundState current;

    public AgentLoopExecutor(ChatModel chatModel, String instructions, List<ToolCallback> tools,
                             int maxRounds, int maxRetries, long retryIntervalMs,
                             ThinkingMode thinkingMode, AgentTaskManager taskManager,
                             AgentxSessionStore sessionStore, TraceManager traceManager,
                             PauseStateStore stateStore,
                             String query, RunnableParams params) {
        this(chatModel, instructions, tools, maxRounds, maxRetries, retryIntervalMs, thinkingMode,
                taskManager, sessionStore, traceManager, stateStore, query, params, null, null,
                null, null, null);
    }

    /** 恢复执行通道：resumeState 非空时从快照续跑（ReactAgent.resumeStream 专用） */
    public AgentLoopExecutor(ChatModel chatModel, String instructions, List<ToolCallback> tools,
                             int maxRounds, int maxRetries, long retryIntervalMs,
                             ThinkingMode thinkingMode, AgentTaskManager taskManager,
                             AgentxSessionStore sessionStore, TraceManager traceManager,
                             PauseStateStore stateStore,
                             String query, RunnableParams params,
                             PauseState resumeState, String resumeQuery) {
        this(chatModel, instructions, tools, maxRounds, maxRetries, retryIntervalMs, thinkingMode,
                taskManager, sessionStore, traceManager, stateStore, query, params,
                resumeState, resumeQuery, null, null, null);
    }

    /**
     * M7 全参构造：deferredRegistry 非空启用 tool_search 延迟发现；
     * compactor 非空启用上下文压缩（+ context_reload 取回工具）。
     */
    public AgentLoopExecutor(ChatModel chatModel, String instructions, List<ToolCallback> tools,
                             int maxRounds, int maxRetries, long retryIntervalMs,
                             ThinkingMode thinkingMode, AgentTaskManager taskManager,
                             AgentxSessionStore sessionStore, TraceManager traceManager,
                             PauseStateStore stateStore,
                             String query, RunnableParams params,
                             PauseState resumeState, String resumeQuery,
                             DeferredToolRegistry deferredRegistry,
                             ContextCompactor compactor,
                             OffloadStore offloadStore) {
        this.chatModel = chatModel;
        this.instructions = instructions == null ? "" : instructions;
        this.tools = List.copyOf(tools);
        Map<String, ToolCallback> map = new LinkedHashMap<>();
        for (ToolCallback t : this.tools) {
            map.putIfAbsent(t.getToolDefinition().name(), t);
        }
        this.toolMap = Collections.unmodifiableMap(map);
        this.maxRounds = maxRounds;
        this.maxRetries = maxRetries;
        this.retryIntervalMs = retryIntervalMs;
        this.thinkingMode = thinkingMode;
        this.taskManager = taskManager;
        this.sessionStore = sessionStore;
        this.traceManager = traceManager;
        this.stateStore = stateStore;
        this.query = query;
        this.params = params;
        this.resumeState = resumeState;
        this.resumeQuery = resumeQuery;
        this.deferredRegistry = deferredRegistry;
        this.deferredSession = deferredRegistry != null ? deferredRegistry.newSession() : null;
        this.toolSearchTool = ToolSearchTool.buildOrNull(deferredRegistry, this.deferredSession);
        this.compactor = compactor;
        this.offloadStore = offloadStore != null ? offloadStore
                : (compactor != null ? new InMemoryOffloadStore() : null);
        this.contextReloadTool = this.offloadStore != null
                ? ContextReloadTool.build(this.offloadStore, conversationId())
                : null;
        this.outputConverter = params.getOutputType() != null
                ? new BeanOutputConverter<>(params.getOutputType())
                : null;
    }

    // ==================== 入口 ====================

    /**
     * 启动执行，返回事件流。每个流必以 Paused 或 Complete 收尾。
     */
    public Flux<AgentStreamEvent> run() {
        // 1、把任务保存起来,onInterrupt(stop中断的时候、会触发这个方法)
        taskManager.registerTask(conversationId(), sink, this::onInterrupt);
        try {
            if (resumeState != null) {
                initMessagesFromResume();
            } else {
                initMessages();
            }
        } catch (Exception e) {
            taskManager.unregisterTask(conversationId());
            throw e;
        }
        // 执行轮次round
        scheduleRound(false);

        return sink.asFlux()
                .doFinally(sig -> fallbackPersist())
                .name("mini-agentx-stream");
    }

    public AgentResult getResult() {
        AgentResult r = resultRef.get();
        return r == null ? AgentResult.failed("NO_RESULT", "流未终止") : r;
    }

    private String conversationId() {
        return params.getConversationId();
    }

    // ==================== 初始化 ====================

    private void initMessages() {
        List<Message> history = sessionStore != null ? sessionStore.loadHistory(conversationId()) : List.of();
        historySize = history.size();
        messages.add(new SystemMessage(buildSystemPrompt()));
        messages.addAll(history);
        String userContent = query;
        if (outputConverter != null) {
            userContent = query + "\n\n" + outputConverter.getFormat();
        }
        UserMessage userMessage = new UserMessage(userContent);
        messages.add(userMessage);
        // 原始链 = 历史 + 本次新增（working 链会被压缩替换，original 只增不减）
        originalChain.addAll(history);
        originalChain.add(userMessage);
        if (sessionStore != null) {
            // 存对话表agentx_conversation
            sessionStore.initConversation(SnowflakeIdGenerator.nextId(),conversationId(), sessionId, params.getUserId(), query);
        }
    }

    private String buildSystemPrompt() {
        if (params.getCustomParams().isEmpty()) {
            return instructions;
        }
        StringBuilder sb = new StringBuilder(instructions).append("\n\n## 系统参数\n");
        params.getCustomParams().forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
        return sb.toString();
    }

    // ==================== 恢复初始化（M3） ====================

    /** 中断恢复的澄清语包装模板（对齐原框架 PromptConstants.RESUME_INTERRUPT_USER_PROMPT） */
    static final String RESUME_INTERRUPT_USER_PROMPT = """
            [用户中断后返回]

            你之前的执行已被用户中断，以下是用户返回后发送的消息。
            请根据原始任务和用户消息之间的关系，判断用户意图并继续执行：
            - 如果用户是对原任务的细化或方向调整，请据此修正后续输出
            - 如果用户是提出新问题，请将其作为新任务处理
            - 如果用户只是让你继续，请无缝恢复执行

            原始任务：
            %s

            用户消息：
            %s
            """;

    /** 带新消息恢复时，被跳过工具的占位响应（不重放，让模型按新指示自决策） */
    static final String TOOL_SKIPPED_PLACEHOLDER = """
            工具执行被用户中断，用户返回后给出了新的指示。
            请根据用户补充的消息判断是否需要继续执行该工具，或调整方案。
            原工具调用：%s(%s)
            """;

    /** 占位工具响应标记（与 placeholderResponses 文案一致，恢复时据此识别可替换的占位行） */
    private static final String PLACEHOLDER_MARKER = "[占位]";

    /**
     * 从 PauseState 快照重建消息链并续跑：
     * 反序列化快照链 → 剥离链尾中断占位响应 → 按 resume 语义回灌工具结果
     * （空 query=本地重放 / 带 query=跳过占位）→ 追加澄清语 → 继续循环。
     */
    private void initMessagesFromResume() {
        for (String row : resumeState.getMessagesJson()) {
            messages.addAll(serializer.fromJsonArray(row));
        }
        // 同一逻辑轮次：沿用快照的轮次号与 sessionId（落库/trace 归属一致）
        round = Math.max(resumeState.getCurrentRound(), 0);
        if (resumeState.getSessionId() != null && !resumeState.getSessionId().isBlank()) {
            this.sessionId = resumeState.getSessionId();
        }
        if (resumeState.getTotalPromptTokens() > 0) {
            totalPromptTokens = resumeState.getTotalPromptTokens();
        }
        if (resumeState.getTotalCompletionTokens() > 0) {
            totalCompletionTokens = resumeState.getTotalCompletionTokens();
        }

        stripTrailingPlaceholderResponses();
        appendResumeToolResponses(resumeState.getPendingToolCalls());

        if (resumeQuery != null && !resumeQuery.isBlank()) {
            String original = resumeState.getQuery() == null ? "无" : resumeState.getQuery();
            messages.add(new UserMessage(RESUME_INTERRUPT_USER_PROMPT.formatted(original, resumeQuery)));
        }
        // 已被中断轮落库的部分（user/assistant 到剥离点为止）不再重复追加
        historySize = (int) messages.stream().filter(m -> !(m instanceof SystemMessage)).count();
        // 原始链以快照链（去 system）为基线（快照即当前已知最完整的链）
        for (Message m : messages) {
            if (!(m instanceof SystemMessage)) {
                originalChain.add(m);
            }
        }
        log.info("恢复执行初始化: convId={}, round={}, pending={}, resumeQuery={}",
                conversationId(), round, resumeState.getPendingToolCalls().size(),
                resumeQuery == null ? "" : resumeQuery);
    }

    /**
     * 剥离链尾的中断占位工具响应（恢复时替换为真实重放/跳过结果，
     * 避免 OpenAI 协议下同一 tool_call_id 被回答两次）。
     */
    private void stripTrailingPlaceholderResponses() {
        Set<String> pendingIds = new LinkedHashSet<>();
        for (PauseState.PendingToolCall ptc : resumeState.getPendingToolCalls()) {
            pendingIds.add(ptc.id());
        }
        if (pendingIds.isEmpty()) {
            return;
        }
        while (!messages.isEmpty()) {
            Message last = messages.get(messages.size() - 1);
            if (last instanceof ToolResponseMessage trm && isPlaceholderBatch(trm, pendingIds)) {
                messages.remove(messages.size() - 1);
                for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                    respondedToolCallIds.remove(r.id());
                }
            } else {
                break;
            }
        }
    }

    /** 仅当整批响应都属于 pending 的占位行时才可剥离（真实结果永不匹配） */
    private static boolean isPlaceholderBatch(ToolResponseMessage trm, Set<String> pendingIds) {
        List<ToolResponseMessage.ToolResponse> responses = trm.getResponses();
        if (responses == null || responses.isEmpty()) {
            return false;
        }
        for (ToolResponseMessage.ToolResponse r : responses) {
            if (!pendingIds.contains(r.id())) {
                return false;
            }
            String data = r.responseData();
            if (data == null || !data.contains(PLACEHOLDER_MARKER)) {
                return false;
            }
        }
        return true;
    }

    /** 按恢复语义回灌 pending 工具调用：空 query=本地真实重放；带 query=跳过占位 */
    private void appendResumeToolResponses(List<PauseState.PendingToolCall> pending) {
        if (pending == null || pending.isEmpty()) {
            return;
        }
        boolean replay = resumeQuery == null || resumeQuery.isBlank();
        // 重放用全量可用映射：pending 可能是上个请求里发现的延迟工具
        Map<String, ToolCallback> replayMap = fullAvailableToolMap();
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (PauseState.PendingToolCall ptc : pending) {
            if (replay) {
                ToolCallExecutor.MergedToolCall call = new ToolCallExecutor.MergedToolCall(
                        ptc.id(), ptc.name(), ptc.arguments());
                ToolCallExecutor.ToolExecutionOutcome o = toolExecutor.executeSync(
                        call, replayMap, params, conversationId(), sink, emitLock);
                // 失败也发 ToolEnd（success=false），与批量通道契约一致
                emit(AgentStreamEvent.toolEnd(o.name(), o.id(), o.result(), o.success()));
                responses.add(new ToolResponseMessage.ToolResponse(ptc.id(), ptc.name(), o.result()));
                log.info("恢复重放工具: convId={}, tool={}, success={}", conversationId(), ptc.name(), o.success());
            } else {
                responses.add(new ToolResponseMessage.ToolResponse(ptc.id(), ptc.name(),
                        TOOL_SKIPPED_PLACEHOLDER.formatted(ptc.name(),
                                ptc.arguments() == null ? "" : ptc.arguments())));
            }
        }
        appendToolResponses(responses);
    }

    // ==================== 轮次调度 ====================

    private void scheduleRound(boolean noTools) {
        if (interruptCtx.isInterrupted() || terminalEmitted.get()) {
            return;
        }
        round++;
        if (startAtMs == 0L) {
            startAtMs = System.currentTimeMillis();
        }
        // M7：每轮 LLM 调用前尝试上下文压缩（超阈值才进策略链；内联实现，不做 Hook）
        compactIfNeeded();
        // M7：每轮重建工具清单 = 常驻 + 已发现（延迟）+ tool_search + context_reload
        List<ToolCallback> roundTools = buildRoundTools(noTools);
        interruptCtx.enterLlmStreaming();
        RoundState rs = new RoundState(new ArrayList<>(messages), toolMapOf(roundTools));
        current = rs;

        // 注册工具
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(noTools ? List.of() : roundTools)
                .internalToolExecutionEnabled(false)
                .build();

        // 调用llm
        Disposable d = chatModelStream(new Prompt(rs.inputSnapshot, options))
                .subscribeOn(Schedulers.boundedElastic())

                // 处理chunk
                .doOnNext(this::handleChunk)

                // 单轮round结束
                .doOnComplete(() -> finishRound(noTools))

                // llm异常处理
                .doOnError(this::handleLlmError)

                .subscribe();

        // 2、任务管理器绑定disposable
        taskManager.bindDisposable(conversationId(), d);
    }

    /** 可覆写：测试注入 scripted 模型响应 */
    protected Flux<org.springframework.ai.chat.model.ChatResponse> chatModelStream(Prompt prompt) {
        return Flux.from(chatModel.stream(prompt));
    }

    // ==================== M7：压缩与每轮工具重建 ====================

    /** 每轮 LLM 调用前尝试上下文压缩（超阈值才进策略链；失败仅告警不阻断） */
    private void compactIfNeeded() {
        if (compactor == null) {
            return;
        }
        synchronized (this) {
            if (interruptCtx.isInterrupted() || terminalEmitted.get()) {
                return;
            }
            try {
                ContextCompactor.CompactOutcome outcome = compactor.tryCompact(conversationId(), messages);
                if (outcome.result().compacted()) {
                    messages.clear();
                    messages.addAll(outcome.newChain());
                    log.info("[data-agent] 上下文已压缩: convId={}, round={}, 策略={}, {}",
                            conversationId(), round, outcome.result().strategy(), outcome.result().detail());
                }
            } catch (Exception e) {
                log.warn("上下文压缩失败（跳过，不影响主流程）: convId={}", conversationId(), e);
            }
        }
    }

    /**
     * 每轮工具清单：常驻 + 已发现（延迟）+ tool_search + context_reload。
     * 延迟注册表未启用时即原 tools + context_reload（若有）。
     */
    private List<ToolCallback> buildRoundTools(boolean noTools) {
        if (noTools) {
            return List.of();
        }
        List<ToolCallback> round = new ArrayList<>(tools);
        if (deferredSession != null) {
            round.addAll(deferredSession.discoveredTools());
        }
        if (toolSearchTool != null) {
            round.add(toolSearchTool);
        }
        if (contextReloadTool != null) {
            round.add(contextReloadTool);
        }
        return List.copyOf(round);
    }

    /** 本轮可执行工具映射：roundTools（含发现工具）保序构建 */
    private static Map<String, ToolCallback> toolMapOf(List<ToolCallback> roundTools) {
        Map<String, ToolCallback> map = new LinkedHashMap<>();
        for (ToolCallback t : roundTools) {
            map.putIfAbsent(t.getToolDefinition().name(), t);
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * 全量可用工具映射：常驻 + 延迟目录全部 + tool_search + context_reload。
     * 用于中断恢复重放（pending 工具可能属于上一个请求发现过的延迟工具）。
     */
    private Map<String, ToolCallback> fullAvailableToolMap() {
        List<ToolCallback> all = new ArrayList<>(tools);
        if (deferredRegistry != null) {
            deferredRegistry.names().forEach(n -> {
                ToolCallback cb = deferredRegistry.get(n);
                if (cb != null) {
                    all.add(cb);
                }
            });
        }
        if (toolSearchTool != null) {
            all.add(toolSearchTool);
        }
        if (contextReloadTool != null) {
            all.add(contextReloadTool);
        }
        return toolMapOf(all);
    }

    // ==================== chunk 处理 ====================

    private void handleChunk(org.springframework.ai.chat.model.ChatResponse resp) {
        RoundState rs = current;
        if (rs == null || interruptCtx.isInterrupted()) {
            return;
        }
        if (resp.getMetadata() != null) {
            Usage usage = resp.getMetadata().getUsage();
            if (usage != null) {
                rs.roundPromptTokens += nz(usage.getPromptTokens());
                rs.roundCompletionTokens += nz(usage.getCompletionTokens());
            }
        }
        List<Generation> generations = resp.getResults();
        if (generations == null) {
            return;
        }
        for (Generation gen : generations) {
            AssistantMessage msg = gen.getOutput();
            if (msg == null) {
                continue;
            }
            if (thinkingMode == ThinkingMode.REASONING_CONTENT) {
                String reasoning = reasoningOf(msg);
                if (!reasoning.isEmpty()) {
                    rs.reasoningBuffer.append(reasoning);
                    emit(AgentStreamEvent.thinking(reasoning));
                }
            }
            List<ToolCall> calls = msg.getToolCalls();
            if (calls != null && !calls.isEmpty()) {
                rs.sawToolCall = true;
                for (ToolCall tc : calls) {
                    mergeToolCall(rs, tc);
                }
            }
            processContent(rs, msg.getText());
        }
    }

    private void processContent(RoundState rs, String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        switch (thinkingMode) {
            case THINK_TAG -> {
                ThinkTagParser.Output out = rs.parser.feed(content);
                if (out.thinkingDelta() != null) {
                    rs.reasoningBuffer.append(out.thinkingDelta());
                    emit(AgentStreamEvent.thinking(out.thinkingDelta()));
                }
                if (out.textDelta() != null) {
                    rs.textBuffer.append(out.textDelta());
                    emit(AgentStreamEvent.text(out.textDelta()));
                }
            }
            case DISABLED -> {
                ThinkTagParser.Output out = rs.parser.feed(content);
                if (out.textDelta() != null) {
                    rs.textBuffer.append(out.textDelta());
                    emit(AgentStreamEvent.text(out.textDelta()));
                }
                // 思考内容丢弃
            }
            case REASONING_CONTENT -> {
                // 思考走独立 reasoning_content 字段（见 handleChunk），content 全部是正文，原样透传
                rs.textBuffer.append(content);
                emit(AgentStreamEvent.text(content));
            }
        }
    }

    /**
     * 取本块模型的思考增量（REASONING_CONTENT 模式）。
     *
     * <p>Spring AI 的 OpenAI 兼容模型把 OpenAI 响应里的 {@code reasoning_content} 字段放进
     * AssistantMessage.metadata 的 {@code reasoningContent} 键（流式下逐块给增量）。
     */
    private static String reasoningOf(AssistantMessage msg) {
        Map<String, Object> meta = msg.getMetadata();
        if (meta == null) {
            return "";
        }
        Object value = meta.get("reasoningContent");
        return value == null ? "" : String.valueOf(value);
    }

    private void mergeToolCall(RoundState rs, ToolCall tc) {
        String id = (tc.id() == null || tc.id().isBlank())
                ? "call_" + rs.toolCallBuilders.size()
                : tc.id();
        MergedBuilder builder = rs.toolCallBuilders.computeIfAbsent(id, k -> {
            MergedBuilder b = new MergedBuilder();
            b.id = k;
            return b;
        });
        if (tc.name() != null && !tc.name().isBlank()) {
            builder.name = tc.name();
        }
        if (tc.arguments() != null && !tc.arguments().isEmpty()) {
            builder.arguments.append(tc.arguments());
        }
    }

    // ==================== 轮次收尾分流 ====================

    private void finishRound(boolean noTools) {
        RoundState rs = current;
        if (rs == null || interruptCtx.isInterrupted()) {
            return;
        }
        synchronized (this) {
            if (current == null) {
                return;
            }
            current = null;

            // thinking 和 text 分流收集
            flushParser(rs);

            // 累加消耗token数
            accumulateTokens(rs);
        }

        // 收集、合并 调用工具数量
        List<ToolCall> merged = buildToolCalls(rs);

        // ---> 出口1：没有工具返回，直接返回答案
        if (merged.isEmpty()) {
            String rawAnswer = rs.textBuffer.toString();

            // 记录trace
            traceRound(rs, rawAnswer, rs.reasoningBuffer.toString(), true, null);

            synchronized (this) {
                AssistantMessage finalMsg = AssistantMessage.builder()
                        .content(rawAnswer)
                        .properties(reasoningProps(rs.reasoningBuffer.toString()))
                        .toolCalls(List.of())
                        .build();
                messages.add(finalMsg);
                originalChain.add(finalMsg);  // original 视图同步追加（真相链只增不减）
            }
            String answer = outputConverter != null ? JsonRepairUtil.fixJson(rawAnswer) : rawAnswer;

            completeTerminal(answer, rs.reasoningBuffer.toString());
            return;
        }

        // B. 工具调用轮
        synchronized (this) {
            AssistantMessage toolRoundMsg = AssistantMessage.builder()
                    .content(rs.textBuffer.toString())
                    .properties(reasoningProps(rs.reasoningBuffer.toString()))
                    .toolCalls(merged)
                    .build();
            messages.add(toolRoundMsg);
            originalChain.add(toolRoundMsg);  // original 视图同步追加（真相链只增不减）
        }

        // 记录trace
        traceRound(rs, toolCallsJson(merged), rs.reasoningBuffer.toString(), true, null);

        if (noTools) {
            // forceFinal 轮模型仍幻觉 tool call：按文本终止
            String answer = rs.textBuffer.isEmpty()
                    ? "已达最大轮次限制，未能产出最终答案。"
                    : rs.textBuffer.toString();

            completeTerminal(answer, rs.reasoningBuffer.toString());
            return;
        }

        if (round >= maxRounds) {
            // forceFinal：占位响应补配对 → 不带工具再调一次逼出最终答案
            synchronized (this) {
                // 追加消息上下文
                appendToolResponses(placeholderResponses(merged));
            }
            log.info("达到 maxRounds={}，进入 forceFinal 终轮: convId={}", maxRounds, conversationId());
            scheduleRound(true);
            return;
        }

        // 正常：并发执行工具 → 全部完成后按序回灌 → 下一轮
        List<PauseState.PendingToolCall> pending = merged.stream()
                .map(tc -> new PauseState.PendingToolCall(tc.id(), tc.name(), tc.arguments()))
                .toList();
        interruptCtx.enterToolExecution(pending);
        List<ToolCallExecutor.MergedToolCall> calls = merged.stream()
                .map(tc -> new ToolCallExecutor.MergedToolCall(tc.id(), tc.name(), tc.arguments()))
                .toList();

        // 批量调用该轮的工具集合，填充对应的工具调用结果（用本轮工具映射——含已发现的延迟工具）
        toolExecutor.executeAsync(calls, rs.toolMap, params, conversationId(), sink, emitLock,
                toolPool, outcomes -> {
                    synchronized (AgentLoopExecutor.this) {
                        if (interruptCtx.isInterrupted() || terminalEmitted.get()) {
                            return; // 中断处理器已收尾
                        }
                        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                        for (ToolCallExecutor.ToolExecutionOutcome o : outcomes) {
                            responses.add(new ToolResponseMessage.ToolResponse(o.id(), o.name(), o.result()));
                        }
                        appendToolResponses(responses);
                    }
                    scheduleRound(false);
                });
    }

    // ==================== LLM 错误与重试 ====================

    private void handleLlmError(Throwable err) {
        RoundState rs = current;
        if (interruptCtx.isInterrupted()) {
            return; // dispose 引发的取消不算错误
        }
        synchronized (this) {
            if (current == null && rs == null) {
                // 已被其他路径收尾
            }
            current = null;
        }
        if (rs != null) {
            accumulateTokens(rs);
            traceRound(rs, null, rs.reasoningBuffer.toString(), false, err.toString());
        }
        retries++;
        log.warn("LLM 调用失败(第 {} 次): {}", retries, err.getMessage());
        if (retries <= maxRetries) {
            emit(AgentStreamEvent.error("LLM_ERROR",
                    "LLM 调用失败，正在重试 " + retries + "/" + maxRetries, err.getMessage()));
            Disposable d = Mono.delay(Duration.ofMillis(retryIntervalMs))
                    .subscribe(v -> scheduleRound(false));
            taskManager.bindDisposable(conversationId(), d);
        } else {
            emit(AgentStreamEvent.error("LLM_ERROR", "LLM 调用失败且重试已耗尽", err.getMessage()));
            synchronized (this) {
                if (rs != null) {
                    flushParser(rs);
                }
            }
            errorTerminal(err);
        }
    }

    // ==================== 中断（interrupt → Paused） ====================

    private void onInterrupt(String interruptMessage) {
        interruptCtx.markInterrupted();
        PauseState snapshot;
        synchronized (this) {
            RoundState rs = current;
            current = null;
            if (rs != null) {
                // LLM 流被打断：半截输出刷成 AssistantMessage 入链
                flushParser(rs);
                List<ToolCall> merged = buildToolCalls(rs);
                AssistantMessage flushedMsg = AssistantMessage.builder()
                        .content(rs.textBuffer.toString())
                        .properties(reasoningProps(rs.reasoningBuffer.toString()))
                        .toolCalls(merged)
                        .build();
                messages.add(flushedMsg);
                originalChain.add(flushedMsg);  // original 视图同步追加（真相链只增不减）
                if (!merged.isEmpty()) {
                    // 已解析出 toolCall 但尚未执行：登记为 pending（恢复时按语义重放/跳过）
                    // 并补占位响应（协议配对；恢复时按占位标记剥离替换）
                    interruptCtx.enterToolExecution(merged.stream()
                            .map(tc -> new PauseState.PendingToolCall(tc.id(), tc.name(), tc.arguments()))
                            .toList());
                    appendToolResponses(placeholderResponses(merged));
                }
                accumulateTokens(rs);
            } else {
                // 工具执行期被打断：给未回灌的 toolCall 补占位响应（OpenAI 协议配对）
                closeDanglingToolCalls();
            }
            snapshot = buildSnapshot(interruptMessage);
        }
        if (stateStore != null) {
            stateStore.save(snapshot);
        }
        if (!terminalEmitted.compareAndSet(false, true)) {
            return;
        }
        persistTerminal("interrupted");
        emit(AgentStreamEvent.paused("USER_INTERRUPT", sessionId));
        resultRef.set(AgentResult.paused(snapshot));
        finishSink();
        log.info("执行已暂停: convId={}, round={}, safePoint={}",
                conversationId(), snapshot.getCurrentRound(), snapshot.getSafePoint());
    }

    private void closeDanglingToolCalls() {
        // 找链尾最近的带 toolCalls 的 assistant，其后无对应响应的 → 补占位
        List<ToolCall> dangling = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m instanceof ToolResponseMessage) {
                continue;
            }
            if (m instanceof AssistantMessage am && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                for (ToolCall tc : am.getToolCalls()) {
                    if (!respondedToolCallIds.contains(tc.id())) {
                        dangling.add(tc);
                    }
                }
                break;
            }
            break;
        }
        if (!dangling.isEmpty()) {
            appendToolResponses(placeholderResponses(dangling));
        }
    }

    private PauseState buildSnapshot(String interruptMessage) {
        List<String> rows = new ArrayList<>();
        for (Message m : messages) {
            rows.add(serializer.toJsonArray(List.of(m)));
        }
        List<PauseState.PendingToolCall> pending = interruptCtx.getPendingToolCalls();
        return new PauseState(conversationId(), sessionId, query, params,
                rows, round, pending, totalPromptTokens, totalCompletionTokens,
                PauseState.Reason.USER_INTERRUPT, interruptCtx.getSafePoint(), interruptMessage);
    }

    // ==================== 终态 ====================

    private void completeTerminal(String answer, String think) {
        if (!terminalEmitted.compareAndSet(false, true)) {
            return;
        }
        // 持久化磁盘，三种形态：completed、error、interrupted
        persistTerminal("completed");
        // 非暂停终态：中断快照已消费/失效，删除防僵尸恢复
        discardSnapshot();

        emit(AgentStreamEvent.complete(totalPromptTokens, totalCompletionTokens,
                elapsedMs(), round, conversationId(), sessionId));

        resultRef.set(AgentResult.completed(answer, think));

        finishSink();
    }

    private void errorTerminal(Throwable err) {
        if (!terminalEmitted.compareAndSet(false, true)) {
            return;
        }
        persistTerminal("error");
        discardSnapshot();
        emit(AgentStreamEvent.complete(totalPromptTokens, totalCompletionTokens,
                elapsedMs(), round, conversationId(), sessionId));
        resultRef.set(AgentResult.failed("LLM_ERROR", err.getMessage()));
        finishSink();
    }

    /** 本次执行累计耗时（毫秒）；尚未开跑返回 null（序列化时省略） */
    private Long elapsedMs() {
        return startAtMs == 0L ? null : System.currentTimeMillis() - startAtMs;
    }

    /** 删除该会话的中断快照（非 Paused 终态时调用；写失败仅告警不影响主流程） */
    private void discardSnapshot() {
        if (stateStore == null) {
            return;
        }
        try {
            stateStore.delete(conversationId());
        } catch (Exception e) {
            log.warn("中断快照清理失败: convId={}", conversationId(), e);
        }
    }

    private void finishSink() {
        sink.tryEmitComplete();
        taskManager.unregisterTask(conversationId());
        toolPool.shutdown();
    }

    // ==================== 落库（终态一次 + 兜底） ====================
    // 持久化磁盘，三种形态：completed、error、interrupted
    private void persistTerminal(String status) {
        if (!persisted.compareAndSet(false, true)) {
            return;
        }
        if (sessionStore == null) {
            return;
        }
        try {
            // original 视图从历史条数(即本轮 user 消息)起追加（原始链不被压缩污染）；working 全量替换
            sessionStore.persistTerminal(conversationId(), sessionId, status, originalChain, messages, historySize);
        } catch (Exception e) {
            log.error("终态落库失败: convId={}, status={}", conversationId(), status, e);
        }
    }

    /** doFinally 兜底：不信任 Reactor signal，未落库一律按 interrupted 处理 */
    private void fallbackPersist() {
        if (persisted.compareAndSet(false, true) && sessionStore != null) {
            try {
                sessionStore.persistTerminal(conversationId(), sessionId, "interrupted", originalChain, messages, historySize);
            } catch (Exception e) {
                log.warn("兜底落库失败: convId={}", conversationId(), e);
            }
        }
        taskManager.unregisterTask(conversationId());
    }

    // ==================== 小工具 ====================

    private void appendToolResponses(List<ToolResponseMessage.ToolResponse> responses) {
        ToolResponseMessage trm = ToolResponseMessage.builder().responses(responses).build();
        messages.add(trm);
        originalChain.add(trm);  // original 视图同步追加（真相链只增不减）
        for (ToolResponseMessage.ToolResponse r : responses) {
            respondedToolCallIds.add(r.id());
        }
    }

    // 任务未正常完成时、针对未匹配对的工具、填充响应response结果。保证【工具调用 --- 工具结果返回】成对出现
    private List<ToolResponseMessage.ToolResponse> placeholderResponses(List<ToolCall> calls) {
        List<ToolResponseMessage.ToolResponse> list = new ArrayList<>();
        for (ToolCall tc : calls) {
            list.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(),
                    "[占位] 本轮工具调用未产生真实结果（轮次上限/中断），请基于已有信息作答。"));
        }
        return list;
    }

    private List<ToolCall> buildToolCalls(RoundState rs) {
        List<ToolCall> list = new ArrayList<>();
        for (MergedBuilder b : rs.toolCallBuilders.values()) {
            String args = sanitizeArgs(b.arguments.toString());
            list.add(new ToolCall(b.id, "function", b.name == null ? "" : b.name, args));
        }
        return list;
    }

    private static String sanitizeArgs(String args) {
        if (args == null || args.isBlank()) {
            return "{}";
        }
        String trimmed = args.trim();
        if (!JsonRepairUtil.isValidJson(trimmed)) {
            return "{}";
        }
        return trimmed;
    }

    private String toolCallsJson(List<ToolCall> calls) {
        StringBuilder sb = new StringBuilder("{\"tool_calls\":[");
        for (int i = 0; i < calls.size(); i++) {
            ToolCall tc = calls.get(i);
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"id\":").append(quote(tc.id()))
                    .append(",\"name\":").append(quote(tc.name()))
                    .append(",\"arguments\":").append(quote(tc.arguments())).append("}");
        }
        return sb.append("]}").toString();
    }

    private static String quote(String s) {
        return "\"" + (s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r")) + "\"";
    }

    private Map<String, Object> reasoningProps(String reasoning) {
        if (reasoning == null || reasoning.isEmpty()) {
            return Map.of();
        }
        return Map.of(MessageJsonSerializer.KEY_REASONING, reasoning);
    }

    private void accumulateTokens(RoundState rs) {
        totalPromptTokens += rs.roundPromptTokens;
        totalCompletionTokens += rs.roundCompletionTokens;
    }

    private void flushParser(RoundState rs) {
        ThinkTagParser.Output out = rs.parser.flush();
        if (out.thinkingDelta() != null) {
            rs.reasoningBuffer.append(out.thinkingDelta());
            if (thinkingMode == ThinkingMode.THINK_TAG) {
                emit(AgentStreamEvent.thinking(out.thinkingDelta()));
            }
        }
        if (out.textDelta() != null) {
            rs.textBuffer.append(out.textDelta());
            emit(AgentStreamEvent.text(out.textDelta()));
        }
    }

    private void traceRound(RoundState rs, String output, String think, boolean success, String error) {
        if (traceManager == null) {
            return;
        }
        traceManager.trace(sessionId, conversationId(), round,
                rs.inputSnapshot, output, think,
                rs.roundPromptTokens, rs.roundCompletionTokens,
                System.currentTimeMillis() - rs.startedAt, success, error);
    }

    private void emit(AgentStreamEvent event) {
        synchronized (emitLock) {
            sink.tryEmitNext(event);
        }
    }

    private static long nz(Integer v) {
        return v == null ? 0L : v;
    }

    // ==================== 轮次状态 ====================

    private static final class RoundState {
        final List<Message> inputSnapshot;
        /** 本轮可用工具映射（常驻 + 已发现延迟 + tool_search + context_reload） */
        final Map<String, ToolCallback> toolMap;
        final StringBuilder textBuffer = new StringBuilder();
        final StringBuilder reasoningBuffer = new StringBuilder();
        final Map<String, MergedBuilder> toolCallBuilders = new LinkedHashMap<>();
        final ThinkTagParser parser = new ThinkTagParser();
        final long startedAt = System.currentTimeMillis();
        long roundPromptTokens;
        long roundCompletionTokens;
        volatile boolean sawToolCall;

        RoundState(List<Message> inputSnapshot, Map<String, ToolCallback> toolMap) {
            this.inputSnapshot = inputSnapshot;
            this.toolMap = toolMap;
        }
    }

    private static final class MergedBuilder {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}
