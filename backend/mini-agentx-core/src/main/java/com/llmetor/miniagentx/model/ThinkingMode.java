package com.llmetor.miniagentx.model;

/**
 * 思考模式。
 *
 * <p>两种回流形态：
 * <ul>
 *   <li>{@link #REASONING_CONTENT}：模型把思考放在独立字段 {@code reasoning_content} 里回流
 *       （MiniMax M3.1-Flash / DeepSeek / Qwen 路线）。Spring AI 的 OpenAI 兼容模型把该字段
 *       放进 AssistantMessage.metadata 的 {@code reasoningContent} 键，内核据此逐块发射思考事件。</li>
 *   <li>{@link #THINK_TAG}：思考以 {@code <think>} 标签嵌在 content 里（MiniMax M3 路线），
 *       由 ThinkTagParser 流式解析。</li>
 * </ul>
 *
 * <p>{@link #DISABLED} 剥标签丢弃思考内容。
 */
public enum ThinkingMode {

    /**
     * 思考以独立 reasoning_content 字段回流（MiniMax M3.1-Flash / DeepSeek / Qwen 路线）
     */
    REASONING_CONTENT,

    /**
     * 思考以 &lt;think&gt; 标签嵌在 content 里（MiniMax M3 路线）
     */
    THINK_TAG,

    /**
     * 不输出思考：标签剥离丢弃
     */
    DISABLED
}
