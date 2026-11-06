package com.llmetor.miniagentx.agent;

import com.llmetor.miniagentx.context.ContextCompactor;
import com.llmetor.miniagentx.context.ContextPolicy;
import com.llmetor.miniagentx.context.InMemoryOffloadStore;
import com.llmetor.miniagentx.context.OffloadStore;
import com.llmetor.miniagentx.interrupt.InMemoryPauseStateStore;
import com.llmetor.miniagentx.interrupt.PauseStateStore;
import com.llmetor.miniagentx.memory.AgentxSessionStore;
import com.llmetor.miniagentx.model.AgentResult;
import com.llmetor.miniagentx.model.AgentStreamEvent;
import com.llmetor.miniagentx.model.PauseState;
import com.llmetor.miniagentx.model.RunnableParams;
import com.llmetor.miniagentx.model.ThinkingMode;
import com.llmetor.miniagentx.tools.search.DeferredToolRegistry;
import com.llmetor.miniagentx.trace.TraceManager;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * ReAct Agent 门面（对齐原框架 ReactAgent 的 M1 子集 + M7 上下文工程）。
 *
 * <p>用法：
 * <pre>
 * ReactAgent agent = ReactAgent.builder()
 *         .chatModel(chatModel)
 *         .instructions("...")
 *         .tools(toolCallbacks)              // 常驻层
 *         .deferredTools(registry)           // 延迟层（可选，M7 tool_search）
 *         .thinkingMode(ThinkingMode.THINK_TAG)
 *         .dataSource(dataSource)            // 传入即启用会话三表 + trace
 *         .maxRounds(100)
 *         .build();
 * Flux&lt;AgentStreamEvent&gt; events = agent.stream(query, RunnableParams.of(convId));
 * </pre>
 *
 * <p>对外 API：stream / call / interrupt / stopStream / hasInterruptedState /
 * getInterruptedState / discardInterruptedState / hasRunningTask。
 */
public class ReactAgent {

    private final ChatModel chatModel;
    private final String instructions;
    private final List<ToolCallback> tools;
    private final int maxRounds;
    private final int maxRetries;
    private final long retryIntervalMs;
    private final ThinkingMode thinkingMode;
    private final AgentTaskManager taskManager;
    private final AgentxSessionStore sessionStore;
    private final TraceManager traceManager;
    private final PauseStateStore stateStore;
    /** M7：延迟工具注册表（null = 不启用 tool_search） */
    private final DeferredToolRegistry deferredTools;
    /** M7：压缩器（null = 不压缩） */
    private final ContextCompactor compactor;
    /** M7：offload 存取（压缩启用时非空） */
    private final OffloadStore offloadStore;

    private ReactAgent(Builder b) {
        this.chatModel = b.chatModel;
        this.instructions = b.instructions;
        this.tools = List.copyOf(b.tools);
        this.maxRounds = b.maxRounds;
        this.maxRetries = b.maxRetries;
        this.retryIntervalMs = b.retryIntervalMs;
        this.thinkingMode = b.thinkingMode;
        this.taskManager = b.taskManager != null ? b.taskManager : new AgentTaskManager();
        if (b.dataSource != null) {
            // 允许外部注入共享实例（每请求重建 Agent 时避免重复建表/重复包装 JdbcTemplate）
            this.sessionStore = b.sessionStore != null ? b.sessionStore : new AgentxSessionStore(b.dataSource);
            this.traceManager = b.enableTrace
                    ? (b.traceManager != null ? b.traceManager : new TraceManager(b.dataSource))
                    : null;
        } else {
            this.sessionStore = null;
            this.traceManager = null;
        }
        this.stateStore = b.stateStore != null ? b.stateStore : new InMemoryPauseStateStore();
        this.deferredTools = b.deferredTools;
        // 压缩启用时：offload 优先落会话表（跨请求可取回），无 DB 退化为内存
        this.offloadStore = b.enableCompaction
                ? (sessionStore != null ? sessionStore : new InMemoryOffloadStore())
                : null;
        this.compactor = b.enableCompaction
                ? new ContextCompactor(b.contextPolicy, offloadStore, chatModel)
                : null;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 流式执行（事件流必以 Paused 或 Complete 收尾） */
    public Flux<AgentStreamEvent> stream(String query, RunnableParams params) {
        AgentLoopExecutor executor = newExecutor(query, params);
        return executor.run();
    }

    /**
     * 从中断快照恢复流式执行（M3）。
     *
     * <p>恢复语义（resumeQuery 三分支，对应 docs/03 M3）：
     * <ul>
     *   <li>resumeQuery 为空：pendingToolCalls 本地真实重放（不重调 LLM），随后续跑</li>
     *   <li>resumeQuery = "继续"类指令：不重放工具（跳过占位），澄清语包装后由模型自决策</li>
     *   <li>resumeQuery = 新的澄清/补充：同上，模型结合新信息修正后续输出</li>
     * </ul>
     *
     * @return 事件流；无快照时返回 Flux.error
     */
    public Flux<AgentStreamEvent> resumeStream(String conversationId, String resumeQuery) {
        PauseState state = stateStore.get(conversationId);
        if (state == null) {
            return Flux.error(new IllegalStateException("没有可恢复的中断快照: " + conversationId));
        }
        AgentLoopExecutor executor = newResumeExecutor(state, resumeQuery);
        return executor.run();
    }

    /** 阻塞执行，返回三态结果 */
    public AgentResult call(String query, RunnableParams params) {
        AgentLoopExecutor executor = newExecutor(query, params);
        executor.run().blockLast();
        return executor.getResult();
    }

    private AgentLoopExecutor newExecutor(String query, RunnableParams params) {
        return new AgentLoopExecutor(chatModel, instructions, tools,
                maxRounds, maxRetries, retryIntervalMs, thinkingMode,
                taskManager, sessionStore, traceManager, stateStore,
                query, params, null, null,
                deferredTools, compactor, offloadStore);
    }

    /** 恢复执行通道：复用快照里的 query/params，沿快照 sessionId/轮次续跑 */
    private AgentLoopExecutor newResumeExecutor(PauseState state, String resumeQuery) {
        RunnableParams params = state.getParams() != null
                ? state.getParams()
                : RunnableParams.of(state.getConversationId());
        return new AgentLoopExecutor(chatModel, instructions, tools,
                maxRounds, maxRetries, retryIntervalMs, thinkingMode,
                taskManager, sessionStore, traceManager, stateStore,
                state.getQuery() == null ? "" : state.getQuery(), params,
                state, resumeQuery, deferredTools, compactor, offloadStore);
    }

    // ==================== 打断 / 任务查询 ====================

    /** 可恢复中断（→ Paused + 快照）；@return 是否找到运行任务 */
    public boolean interrupt(String conversationId, String message) {
        return taskManager.interrupt(conversationId, message);
    }

    /** 强制停止（丢状态，不产生 Paused） */
    public boolean stopStream(String conversationId) {
        return taskManager.stopTask(conversationId);
    }

    public boolean hasRunningTask(String conversationId) {
        return taskManager.hasRunningTask(conversationId);
    }

    public boolean hasInterruptedState(String conversationId) {
        return stateStore.has(conversationId);
    }

    public PauseState getInterruptedState(String conversationId) {
        return stateStore.get(conversationId);
    }

    public boolean discardInterruptedState(String conversationId) {
        return stateStore.delete(conversationId);
    }

    public AgentTaskManager getTaskManager() {
        return taskManager;
    }

    // ==================== Builder ====================

    public static class Builder {
        private ChatModel chatModel;
        private String instructions = "";
        private final List<ToolCallback> tools = new ArrayList<>();
        private int maxRounds = 100;
        private int maxRetries = 3;
        private long retryIntervalMs = 10_000;
        private ThinkingMode thinkingMode = ThinkingMode.DISABLED;
        private AgentTaskManager taskManager;
        private javax.sql.DataSource dataSource;
        private boolean enableTrace = true;
        private PauseStateStore stateStore;
        private AgentxSessionStore sessionStore;
        private TraceManager traceManager;
        private DeferredToolRegistry deferredTools;
        private boolean enableCompaction = true;
        private ContextPolicy contextPolicy = ContextPolicy.defaults();

        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        public Builder instructions(String instructions) {
            this.instructions = instructions;
            return this;
        }

        public Builder tools(List<ToolCallback> tools) {
            this.tools.addAll(tools);
            return this;
        }

        public Builder maxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder retryIntervalMs(long retryIntervalMs) {
            this.retryIntervalMs = retryIntervalMs;
            return this;
        }

        public Builder thinkingMode(ThinkingMode thinkingMode) {
            this.thinkingMode = thinkingMode;
            return this;
        }

        /** 共享任务管理器（跨 Agent 打断定位） */
        public Builder taskManager(AgentTaskManager taskManager) {
            this.taskManager = taskManager;
            return this;
        }

        /** 传入 DataSource 即启用会话三表（+ 默认启用 trace） */
        public Builder dataSource(javax.sql.DataSource dataSource) {
            this.dataSource = dataSource;
            return this;
        }

        /** 注入共享会话存储（dataSource 仍必传；用于每请求重建 Agent 的场景） */
        public Builder sessionStore(AgentxSessionStore sessionStore) {
            this.sessionStore = sessionStore;
            return this;
        }

        /** 注入共享 trace 管理（需 enableTrace=true；用于每请求重建 Agent 的场景） */
        public Builder traceManager(TraceManager traceManager) {
            this.traceManager = traceManager;
            return this;
        }

        public Builder enableTrace(boolean enableTrace) {
            this.enableTrace = enableTrace;
            return this;
        }

        public Builder stateStore(PauseStateStore stateStore) {
            this.stateStore = stateStore;
            return this;
        }

        /** M7：延迟工具注册表（传入即启用 tool_search 延迟工具发现） */
        public Builder deferredTools(DeferredToolRegistry deferredTools) {
            this.deferredTools = deferredTools;
            return this;
        }

        /** M7：上下文压缩开关（默认开；无 chatModel 的场景需关闭） */
        public Builder enableCompaction(boolean enableCompaction) {
            this.enableCompaction = enableCompaction;
            return this;
        }

        /** M7：压缩策略参数（默认文档默认值） */
        public Builder contextPolicy(ContextPolicy contextPolicy) {
            if (contextPolicy != null) {
                this.contextPolicy = contextPolicy;
            }
            return this;
        }

        public ReactAgent build() {
            if (chatModel == null) {
                throw new IllegalArgumentException("chatModel 必填");
            }
            return new ReactAgent(this);
        }
    }
}
