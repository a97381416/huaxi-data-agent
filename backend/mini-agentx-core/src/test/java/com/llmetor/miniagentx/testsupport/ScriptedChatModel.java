package com.llmetor.miniagentx.testsupport;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * 测试用脚本化 ChatModel：每次 stream 弹出预置的 Flux 脚本，并捕获全部请求 Prompt。
 */
public class ScriptedChatModel implements ChatModel {

    private final Queue<Flux<ChatResponse>> script = new LinkedList<>();
    public final List<Prompt> capturedPrompts = new ArrayList<>();

    public void enqueue(Flux<ChatResponse> round) {
        script.add(round);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        throw new UnsupportedOperationException("测试只走 stream");
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        capturedPrompts.add(prompt);
        Flux<ChatResponse> next = script.poll();
        if (next == null) {
            return Flux.error(new IllegalStateException("脚本已耗尽（未预期的额外调用）"));
        }
        return next;
    }

    // ==================== chunk 构造辅助 ====================

    public static ChatResponse textChunk(String content) {
        AssistantMessage msg = new AssistantMessage(content);
        return new ChatResponse(List.of(new Generation(msg)));
    }

    public static ChatResponse toolCallChunk(String id, String name, String argsFragment) {
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall(
                id == null ? "" : id, "function", name == null ? "" : name, argsFragment);
        AssistantMessage msg = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(tc))
                .build();
        return new ChatResponse(List.of(new Generation(msg)));
    }

    public static ChatResponse usageChunk(long promptTokens, long completionTokens) {
        AssistantMessage msg = new AssistantMessage("");
        Usage usage = new DefaultUsage((int) promptTokens, (int) completionTokens);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().usage(usage).build();
        return new ChatResponse(List.of(new Generation(msg)), metadata);
    }

    /**
     * 模拟 OpenAI 兼容模型的 reasoning_content 回流：思考增量放在 AssistantMessage 的
     * properties（Spring AI 把 OpenAI 的 reasoning_content 放进 metadata 的 reasoningContent 键）。
     */
    public static ChatResponse reasoningChunk(String reasoning, String content) {
        AssistantMessage msg = AssistantMessage.builder()
                .content(content == null ? "" : content)
                .properties(java.util.Map.of("reasoningContent", reasoning == null ? "" : reasoning))
                .build();
        return new ChatResponse(List.of(new Generation(msg)));
    }
}
