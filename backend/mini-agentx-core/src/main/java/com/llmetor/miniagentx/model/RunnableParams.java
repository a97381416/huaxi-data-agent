package com.llmetor.miniagentx.model;

import lombok.Getter;
import lombok.ToString;

import java.util.Collections;
import java.util.Map;

/**
 * Agent 调用参数（对齐原框架 RunnableParams，M1 子集）。
 *
 * <ul>
 *   <li>conversationId：会话窗口——历史加载/落库、并发互斥、interrupt 定位</li>
 *   <li>userId：注入 ToolContext（数据权限等场景使用，M4 消费）</li>
 *   <li>customParams：拼进 system prompt（"## 系统参数"小节，LLM 可见）</li>
 *   <li>toolParams：<b>不进 prompt</b>，工具执行前按工具 inputSchema 白名单强制覆盖进工具 args</li>
 *   <li>outputType：结构化输出——BeanOutputConverter.getFormat() 拼 user 消息尾部，
 *       最终答案过 JsonRepairUtil 修复后返回</li>
 * </ul>
 */
@Getter
@ToString
public class RunnableParams {

    private String conversationId;
    private String userId;
    private Map<String, String> customParams = Collections.emptyMap();
    private Map<String, Object> toolParams = Collections.emptyMap();
    private Class<?> outputType;
    /** M-SA1：是否为子 Agent 执行（true 时跳过 agentx_conversation 写入，保持一问一行） */
    private boolean subAgent = false;
    /** M-SA1：Agent 标识（主路由默认 "router"，子 Agent 如 "data-analysis"） */
    private String agentCode;
    /** M-SA1：根会话窗口 ID（子 Agent 指向外层 rootConvId，用于 Trace 聚合与附件共享） */
    private String rootConversationId;
    /** M-SA1：父级执行 sessionId（用于构建父子 Trace 调用树） */
    private String parentSessionId;
    /** M-SA1：父级调用当前子 Agent 时的 toolCallId（用于 SSE 流事件溯源挂载） */
    private String parentToolCallId;

    /** 返回有效的根窗口 ID（主 Agent 即自身 conversationId，子 Agent 为 rootConversationId） */
    public String effectiveRootConversationId() {
        return (rootConversationId != null && !rootConversationId.isBlank())
                ? rootConversationId : conversationId;
    }

    public static RunnableParams of(String conversationId) {
        RunnableParams p = new RunnableParams();
        p.conversationId = conversationId;
        return p;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final RunnableParams params = new RunnableParams();

        public Builder conversationId(String conversationId) {
            params.conversationId = conversationId;
            return this;
        }

        public Builder userId(String userId) {
            params.userId = userId;
            return this;
        }

        public Builder customParams(Map<String, String> customParams) {
            if (customParams != null) {
                params.customParams = Map.copyOf(customParams);
            }
            return this;
        }

        public Builder toolParams(Map<String, Object> toolParams) {
            if (toolParams != null) {
                params.toolParams = Map.copyOf(toolParams);
            }
            return this;
        }

        public Builder outputType(Class<?> outputType) {
            params.outputType = outputType;
            return this;
        }

        public Builder subAgent(boolean subAgent) {
            params.subAgent = subAgent;
            return this;
        }

        public Builder agentCode(String agentCode) {
            params.agentCode = agentCode;
            return this;
        }

        public Builder rootConversationId(String rootConversationId) {
            params.rootConversationId = rootConversationId;
            return this;
        }

        public Builder parentSessionId(String parentSessionId) {
            params.parentSessionId = parentSessionId;
            return this;
        }

        public Builder parentToolCallId(String parentToolCallId) {
            params.parentToolCallId = parentToolCallId;
            return this;
        }

        public RunnableParams build() {
            if (params.conversationId == null || params.conversationId.isBlank()) {
                throw new IllegalArgumentException("conversationId 不能为空");
            }
            return params;
        }
    }
}
