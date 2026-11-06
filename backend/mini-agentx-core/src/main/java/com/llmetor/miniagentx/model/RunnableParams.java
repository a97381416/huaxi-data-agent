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

        public RunnableParams build() {
            if (params.conversationId == null || params.conversationId.isBlank()) {
                throw new IllegalArgumentException("conversationId 不能为空");
            }
            return params;
        }
    }
}
