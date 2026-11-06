package com.llmetor.miniagentx.context;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.List;
import java.util.function.Function;

/**
 * 上下文取回工具（工具名 {@code context_reload}，docs/02 §7）——压缩闭环的取回侧。
 *
 * <p>LLM 在历史消息里看到 {@code uuid=xxx} offload 标记后，调用本工具传 uuid，
 * 从 {@link OffloadStore} 取回被压缩的原文（上限 32000 字符截断）。
 * 依赖压缩子系统：未启用压缩的 Agent 不注册本工具。
 */
public final class ContextReloadTool {

    /** 取回内容上限（对齐文档：32000 字符截断） */
    public static final int MAX_CHARS = 32_000;

    private static final String TOOL_DESCRIPTION = """
            取回一段被压缩归档（offload）的历史对话原文。
            上下文里的历史工具调用记录、大结果或轮次摘要旁带有 uuid=xxx 标记，表示原文已归档；
            当你需要其中的完整原文（如当时的完整查询结果、精确数字）时，调用本工具并传入该 uuid。
            返回归档的原始消息内容（超长时截断到 32000 字符）。传入未知的 uuid 会得到"未找到"提示。
            """;

    private ContextReloadTool() {
    }

    /** 工具入参：offload 标记里的 uuid */
    public record ReloadInput(
            @ToolParam(description = "offload 标记中的 uuid，例如 \"3f2a...\"。仅传 uuid 本身") String uuid) {
    }

    /** 取回函数：uuid → 原文（多消息拼接，32000 字符截断） */
    public static class ReloadFunction implements Function<ReloadInput, String> {

        private final OffloadStore offloadStore;
        private final String conversationId;

        public ReloadFunction(OffloadStore offloadStore, String conversationId) {
            this.offloadStore = offloadStore;
            this.conversationId = conversationId;
        }

        @Override
        public String apply(ReloadInput input) {
            String uuid = input == null ? null : input.uuid();
            if (uuid == null || uuid.isBlank()) {
                return "参数错误：uuid 不能为空。请从上下文中的 offload 标记（uuid=xxx）复制 uuid。";
            }
            List<org.springframework.ai.chat.messages.Message> messages =
                    offloadStore.load(conversationId, uuid.trim());
            if (messages == null || messages.isEmpty()) {
                return "未找到 uuid=" + uuid + " 对应的归档内容（可能已失效）。请勿编造原文，"
                        + "如仍需要请说明你需要的具体信息。";
            }
            StringBuilder sb = new StringBuilder();
            for (org.springframework.ai.chat.messages.Message m : messages) {
                sb.append(m.getText() == null ? "" : m.getText()).append('\n');
            }
            String text = sb.toString();
            if (text.length() > MAX_CHARS) {
                text = text.substring(0, MAX_CHARS) + "\n…[原文超长，已截断到 32000 字符]";
            }
            return text;
        }
    }

    /** 构建工具实例（每请求新建，绑定当前会话的 offload 存取） */
    public static ToolCallback build(OffloadStore offloadStore, String conversationId) {
        return FunctionToolCallback.builder("context_reload", new ReloadFunction(offloadStore, conversationId))
                .description(TOOL_DESCRIPTION)
                .inputType(ReloadInput.class)
                .build();
    }
}
