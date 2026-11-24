package com.llmetor.dataagent.dataagent.monitor.dto;

import java.util.List;

/**
 * 提问事件流时间轴（提问详情页左轴「事件流」+ 右侧「事件快照」数据源）。
 *
 * <p>事件模型（2026-11-20 排查视角改造）：一轮 LLM 调用拆成 🤖 llm 事件（思考+决策）
 * + N 个 🔧 tool 事件（并发调用，结果按 tool_call_id 从下一轮 INPUT 配对回填），
 * 加上 ❓ user 起点与 💬 answer 终答。决策与结果闭环在同一父节点下。
 *
 * <p>增量语义：llm 事件的 deltaMessages = 本轮 INPUT 相比上一轮新传入的消息
 * （上一轮的 assistant 原话 + 工具结果们），即「这次调用时增量传入的数据」。
 * 工具结果全文只在 deltaMessages 存一份，tool 事件带 resultRefSeq 指向下轮
 * llm 事件，前端按 callId 取全文（避免响应体翻倍）。
 */
public record SessionTimelineVO(
        String sessionId,
        String conversationId,
        String question,
        String status,
        int roundCount,
        List<Event> events) {

    /**
     * 事件行。type = user | llm | tool | answer。
     * user/llm/answer 共有 deltaMessages；tool 专有 callId/arguments/result* 组。
     */
    public record Event(
            int seq,
            String type,
            int round,
            String traceId,
            Boolean success,
            String errorMessage,
            Long durationMs,
            Long promptTokens,
            Long completionTokens,
            Long thinkDurationMs,
            String think,
            Integer inputCount,
            Integer inputChars,
            /** llm/answer：模型文本输出（下一轮 INPUT 的 assistant 原话回填；answer=output 原文） */
            String assistantText,
            /** llm：本轮决策的并发工具调用（arguments 全文，量小直出） */
            List<ToolCallItem> toolCalls,
            /** 本事件新增传入上下文的消息（tool 事件为 null） */
            List<DeltaMessage> deltaMessages,
            /** tool：父 llm 事件 seq */
            Integer parentSeq,
            /** tool：tool_call_id */
            String callId,
            /** tool：工具名 */
            String toolName,
            /** tool：调用参数原文 */
            String arguments,
            /** tool：执行结果单行预览（160 字） */
            String resultPreview,
            /** tool：结果字符数 */
            Integer resultChars,
            /** tool：结果判定（❌/{\"error\" 开头=false；内容空=null；其余 true） */
            Boolean resultOk,
            /** tool：执行成功但未命中数据（未找到/无结果类前缀）→ 前端 ⓘ */
            Boolean resultEmpty,
            /** tool：结果全文所在 llm 事件 seq（下轮 deltaMessages 内按 callId 查找） */
            Integer resultRefSeq) {
    }

    /** 工具调用项（决策与参数；arguments 为模型输出的 JSON 字符串原文） */
    public record ToolCallItem(String callId, String name, String arguments) {
    }

    /**
     * 增量消息（OpenAI 风格行）。content 为原文（tool 消息的 content 可能是
     * JSON 字符串编码文本，由前端 normalizeJsonText 统一解包渲染）。
     */
    public record DeltaMessage(
            String role,
            String content,
            /** tool：工具名 */
            String name,
            /** tool：tool_call_id */
            String toolCallId,
            /** assistant：本条消息发起的工具调用 */
            List<ToolCallItem> toolCalls,
            /** content 超单条上限被截断（全文走全量输入懒加载） */
            boolean truncated) {
    }
}
