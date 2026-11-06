package com.llmetor.miniagentx.context;

import com.llmetor.miniagentx.memory.MessageJsonSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 上下文压缩策略链（docs/02 §4，M7 先做 L1/L2/L4 三层；L3/L5/L6 预留）。
 *
 * <p>触发（每轮 LLM 调用前，由执行器内联调用——不做 Hook 体系）：
 * 非系统消息数 ≥ msgThreshold <b>或</b> 全链估算 token ≥ tokenThreshold。
 *
 * <p>策略依次 tryCompress，<b>先触发者赢</b>：
 * <ul>
 *   <li><b>L1 HistoricalToolList</b>：历史区连续工具消息 run ≥6 条且段 ≥5000 token →
 *       连同其前导 toolCalls assistant 整段 offload，替换为"工具调用记录模板 + uuid"</li>
 *   <li><b>L2 LargeMsgOffloadWithKeep</b>：历史区（保护 lastKeep）单条 >2000 token
 *       且非带 toolCalls 的 assistant → offload，替换为"前 200 字预览 + uuid 标记"</li>
 *   <li><b>L4 HistoricalRoundSummary</b>：历史区完整轮次段（user→无工具 assistant）
 *       ≥5000 token → LLM 摘要替换（user 原话保留），从最近往前最多 3 轮</li>
 * </ul>
 *
 * <p>协议安全：assistant(toolCalls) 与其 tool 响应必须成对——L1 整段移除时把前导
 * assistant 一并带走；L2 对 tool 消息只做原地内容替换；L4 移除的是轮内完整消息块。
 *
 * <p>offload 原文经 {@link OffloadStore} 按 uuid 存档，替换文本嵌 {@code uuid=xxx}
 * 标记，LLM 可调 context_reload 取回——"压缩不丢信息"闭环。
 */
public class ContextCompactor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactor.class);

    /** L4 摘要输入里单条消息的字符上限（防个别大消息撑爆摘要调用） */
    private static final int SUMMARY_MSG_CAP_CHARS = 4000;
    /** L1 args 展示截断长度（对齐文档"args前100字"） */
    private static final int L1_ARGS_CHARS = 100;

    private final ContextPolicy policy;
    private final OffloadStore offloadStore;
    /** L4 摘要用（可为 null——此时 L4 跳过） */
    private final ChatModel chatModel;
    private final MessageJsonSerializer serializer = new MessageJsonSerializer();

    public ContextCompactor(ContextPolicy policy, OffloadStore offloadStore, ChatModel chatModel) {
        this.policy = policy;
        this.offloadStore = offloadStore;
        this.chatModel = chatModel;
    }

    /** 压缩结果：compacted=false 表示未触发任何策略（链保持原样） */
    public record CompactResult(boolean compacted, String strategy, String detail) {
        static CompactResult none() {
            return new CompactResult(false, null, null);
        }
    }

    /** 是否达到触发条件（消息数或估算 token 任一超阈） */
    public boolean shouldCompact(List<Message> workingChain) {
        long count = workingChain.stream().filter(m -> !(m instanceof SystemMessage)).count();
        if (count >= policy.getMsgThreshold()) {
            return true;
        }
        return TokenEstimator.estimateAll(workingChain) >= policy.getTokenThreshold();
    }

    /**
     * 对工作链尝试压缩：L1 → L2 → L4 依次尝试，先触发者赢。
     *
     * @param conversationId 会话 ID（offload 归档归属）
     * @param workingChain   工作链（含首部 SystemMessage；不被本方法修改）
     * @return 压缩结果与新链（未触发时 newChain == 原链）
     */
    public CompactOutcome tryCompact(String conversationId, List<Message> workingChain) {
        if (!shouldCompact(workingChain)) {
            return new CompactOutcome(CompactResult.none(), workingChain);
        }
        // 布局：[0]=SystemMessage；历史区=[1, protectedFrom)；保护区=[protectedFrom, size)
        int protectedFrom = Math.max(1, workingChain.size() - policy.getLastKeep());

        CompactOutcome outcome = tryL1(conversationId, workingChain, protectedFrom);
        if (outcome.result().compacted()) {
            return outcome;
        }
        outcome = tryL2(conversationId, workingChain, protectedFrom);
        if (outcome.result().compacted()) {
            return outcome;
        }
        return tryL4(conversationId, workingChain, protectedFrom);
    }

    public record CompactOutcome(CompactResult result, List<Message> newChain) {
    }

    // ==================== L1：历史工具列表 offload ====================

    private CompactOutcome tryL1(String conversationId, List<Message> chain, int protectedFrom) {
        // 找"工具密集区"：连续的 assistant(toolCalls) | ToolResponseMessage 区块。
        // 注意 ReAct 链里轮与轮之间隔着 assistant，所以按区内的工具响应数计数
        // （6 轮×1 响应 或 1 轮×6 响应都算达标）；跨进保护区的区块整段跳过
        // （只切一半会让保护区内的 tool 响应失去配对的 assistant，协议非法）。
        List<int[]> regions = new ArrayList<>(); // {start, end}
        int i = 1;
        while (i < chain.size()) {
            if (isToolRegionMember(chain.get(i))) {
                int start = i;
                while (i < chain.size() && isToolRegionMember(chain.get(i))) {
                    i++;
                }
                regions.add(new int[]{start, i});
            } else {
                i++;
            }
        }

        // 从右往左替换，前面的下标不受影响
        List<Message> replaced = new ArrayList<>(chain);
        int offloadedRegions = 0;
        long savedTokens = 0;
        for (int r = regions.size() - 1; r >= 0; r--) {
            int segStart = regions.get(r)[0];
            int segEnd = regions.get(r)[1];
            if (segEnd > protectedFrom) {
                continue; // 区块跨保护区边界，整段跳过
            }
            long responseCount = countResponses(replaced.subList(segStart, segEnd));
            if (responseCount < policy.getMinConsecutiveToolMessages()) {
                continue;
            }
            List<Message> segment = new ArrayList<>(replaced.subList(segStart, segEnd));
            long segTokens = TokenEstimator.estimateAll(segment);
            if (segTokens < policy.getMinCompressionTokens()) {
                continue;
            }
            String uuid = UUID.randomUUID().toString();
            offloadStore.save(conversationId, uuid, segment);
            replaced.subList(segStart, segEnd).clear();
            replaced.add(segStart, new UserMessage(l1Template(uuid, segment)));
            offloadedRegions++;
            savedTokens += segTokens;
        }
        if (offloadedRegions == 0) {
            return new CompactOutcome(CompactResult.none(), chain);
        }
        String detail = "offload %d 段历史工具记录（约 %.0f tokens）".formatted(offloadedRegions, (double) savedTokens);
        log.info("[compaction] L1 HistoricalToolList 生效: convId={}, {}", conversationId, detail);
        return new CompactOutcome(new CompactResult(true, "L1", detail), replaced);
    }

    /** 工具密集区成员：带 toolCalls 的 assistant 或工具响应消息 */
    private static boolean isToolRegionMember(Message m) {
        if (m instanceof ToolResponseMessage) {
            return true;
        }
        return m instanceof AssistantMessage am && am.getToolCalls() != null && !am.getToolCalls().isEmpty();
    }

    /** L1 替换模板：工具名 + args 前 100 字 + 结果预览 + uuid 取回指引 */
    private String l1Template(String uuid, List<Message> segment) {
        // 收集段内 toolCalls id→args，用于把响应与调用参数对上
        Map<String, String> argsById = new LinkedHashMap<>();
        for (Message m : segment) {
            if (m instanceof AssistantMessage am && am.getToolCalls() != null) {
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                    argsById.put(tc.id(), tc.arguments());
                }
            }
        }
        StringBuilder sb = new StringBuilder("""
                [历史工具调用记录（已压缩 offload，uuid=%s）]
                以下 %d 条历史工具调用及其结果已归档；需要原文时调用 context_reload 工具并传入上述 uuid。
                """.formatted(uuid, countResponses(segment)));
        for (Message m : segment) {
            if (!(m instanceof ToolResponseMessage trm)) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
                String args = argsById.getOrDefault(resp.id(), "");
                sb.append("- ").append(resp.name()).append("(")
                        .append(abbreviate(args, L1_ARGS_CHARS)).append(") → ")
                        .append(abbreviate(resp.responseData(), policy.getOffloadPreviewChars()))
                        .append('\n');
            }
        }
        return sb.toString();
    }

    private static long countResponses(List<Message> segment) {
        long n = 0;
        for (Message m : segment) {
            if (m instanceof ToolResponseMessage trm && trm.getResponses() != null) {
                n += trm.getResponses().size();
            }
        }
        return n;
    }

    // ==================== L2：历史大消息 offload ====================

    private CompactOutcome tryL2(String conversationId, List<Message> chain, int protectedFrom) {
        List<Message> replaced = new ArrayList<>(chain);
        int offloaded = 0;
        long savedTokens = 0;
        for (int i = 1; i < protectedFrom; i++) {
            Message m = replaced.get(i);
            long tokens = TokenEstimator.estimate(m);
            if (tokens <= policy.getLargePayloadTokens()) {
                continue;
            }
            if (m instanceof AssistantMessage am && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                // 带 toolCalls 的 assistant 不动（移除会破坏与 tool 响应的配对）
                continue;
            }
            String uuid = UUID.randomUUID().toString();
            offloadStore.save(conversationId, uuid, List.of(m));
            replaced.set(i, rebuildWithPreview(m, uuid));
            offloaded++;
            savedTokens += tokens;
        }
        if (offloaded == 0) {
            return new CompactOutcome(CompactResult.none(), chain);
        }
        String detail = "offload %d 条大消息（约 %.0f tokens）".formatted(offloaded, (double) savedTokens);
        log.info("[compaction] L2 LargeMsgOffloadWithKeep 生效: convId={}, {}", conversationId, detail);
        return new CompactOutcome(new CompactResult(true, "L2", detail), replaced);
    }

    /** 提取消息预览文本（tool 消息取各 response 拼接，其余取正文） */
    private static String previewOf(Message m) {
        if (m instanceof ToolResponseMessage trm && trm.getResponses() != null) {
            StringBuilder sb = new StringBuilder();
            for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(r.responseData() == null ? "" : r.responseData());
            }
            return sb.toString();
        }
        return m.getText() == null ? "" : m.getText();
    }

    /** 保持消息类型与协议配对不变，把内容替换为"前 N 字预览 + uuid 取回标记" */
    private Message rebuildWithPreview(Message m, String uuid) {
        String marker = "\n\n…[本条原文已压缩 offload，uuid=%s；调用 context_reload 可取回]".formatted(uuid);
        if (m instanceof ToolResponseMessage trm) {
            List<ToolResponseMessage.ToolResponse> responses = trm.getResponses() == null
                    ? List.<ToolResponseMessage.ToolResponse>of()
                    : trm.getResponses();
            List<ToolResponseMessage.ToolResponse> rebuilt = new ArrayList<>();
            for (ToolResponseMessage.ToolResponse r : responses) {
                String data = r.responseData() == null ? "" : r.responseData();
                String preview = abbreviate(data, policy.getOffloadPreviewChars());
                rebuilt.add(new ToolResponseMessage.ToolResponse(r.id(), r.name(),
                        responses.size() == 1 ? preview + marker : preview));
            }
            // 多 response 时标记附在末尾一条，避免每条重复占上下文
            if (responses.size() > 1 && !rebuilt.isEmpty()) {
                ToolResponseMessage.ToolResponse last = rebuilt.get(rebuilt.size() - 1);
                rebuilt.set(rebuilt.size() - 1,
                        new ToolResponseMessage.ToolResponse(last.id(), last.name(), last.responseData() + marker));
            }
            return ToolResponseMessage.builder().responses(rebuilt).build();
        }
        if (m instanceof AssistantMessage am) {
            return AssistantMessage.builder()
                    .content(abbreviate(am.getText(), policy.getOffloadPreviewChars()) + marker)
                    .properties(am.getMetadata())
                    .toolCalls(List.of())
                    .build();
        }
        return new UserMessage(abbreviate(previewOf(m), policy.getOffloadPreviewChars()) + marker);
    }

    // ==================== L4：历史轮次 LLM 摘要 ====================

    private CompactOutcome tryL4(String conversationId, List<Message> chain, int protectedFrom) {
        if (chatModel == null) {
            return new CompactOutcome(CompactResult.none(), chain);
        }
        // 切轮：round = [user 起，下一个 user 前）；只考虑完整收尾的轮——
        // 撞到保护区边界时，仅当边界处恰是新 user（下一轮已开始）才算完整，
        // 否则该轮延伸进保护区，摘要会让边界后的消息失去协议配对，跳过
        List<int[]> rounds = new ArrayList<>();
        int i = 1;
        while (i < protectedFrom) {
            if (chain.get(i) instanceof UserMessage) {
                int start = i;
                i++;
                while (i < protectedFrom && !(chain.get(i) instanceof UserMessage)) {
                    i++;
                }
                boolean completed = i < protectedFrom
                        || (protectedFrom < chain.size() && chain.get(protectedFrom) instanceof UserMessage);
                rounds.add(new int[]{start, completed ? i : -i});
            } else {
                i++;
            }
        }

        // 从最近往前挑 ≤maxLlmCompressionCount 个达标的轮做摘要
        int summarized = 0;
        long savedTokens = 0;
        List<Message> replaced = new ArrayList<>(chain);
        for (int r = rounds.size() - 1; r >= 0 && summarized < policy.getMaxLlmCompressionCount(); r--) {
            int start = rounds.get(r)[0];
            int end = rounds.get(r)[1];
            if (end < 0) {
                continue; // 截断轮跳过
            }
            List<Message> roundMsgs = new ArrayList<>(replaced.subList(start, end));
            long roundTokens = TokenEstimator.estimateAll(roundMsgs);
            if (roundTokens < policy.getMinCompressionTokens()) {
                continue;
            }
            String summary = summarizeRound(roundMsgs);
            if (summary == null || summary.isBlank()) {
                continue; // 摘要失败：跳过该轮，不阻断
            }
            String uuid = UUID.randomUUID().toString();
            offloadStore.save(conversationId, uuid, roundMsgs);
            // user 原话保留：只摘 [start+1, end)，摘要以 assistant 身份插在 user 之后
            replaced.subList(start + 1, end).clear();
            String summaryText = "[历史轮次摘要（原文已 offload，uuid=%s）]\n%s".formatted(uuid, summary.trim());
            replaced.add(start + 1, AssistantMessage.builder()
                    .content(summaryText)
                    .toolCalls(List.of())
                    .build());
            summarized++;
            savedTokens += roundTokens;
        }
        if (summarized == 0) {
            return new CompactOutcome(CompactResult.none(), chain);
        }
        String detail = "摘要 %d 个历史轮次（约 %.0f tokens）".formatted(summarized, (double) savedTokens);
        log.info("[compaction] L4 HistoricalRoundSummary 生效: convId={}, {}", conversationId, detail);
        return new CompactOutcome(new CompactResult(true, "L4", detail), replaced);
    }

    /** 调 LLM 生成单轮摘要；失败返回 null（策略跳过） */
    private String summarizeRound(List<Message> roundMsgs) {
        try {
            StringBuilder transcript = new StringBuilder();
            for (Message m : roundMsgs) {
                appendTranscriptLine(transcript, m);
            }
            String prompt = """
                    你是会话记录压缩器。请把下面的历史对话轮次压缩成一段摘要，供后续对话作为背景知识使用。
                    必须保留：
                    1) 用户的核心请求与约束（时间范围、口径、过滤条件等原样保留）；
                    2) 已完成的关键步骤与工具调用结论；
                    3) 查询得到的关键数据（数字、表名、字段名、代码等硬信息原样保留）；
                    4) 已得出的结论与未完成事项。
                    不要评论、不要客套，直接输出摘要正文（中文）。

                    --- 历史对话开始 ---
                    %s
                    --- 历史对话结束 ---
                    """.formatted(transcript.toString());
                return chatModel.call(new Prompt(prompt)).getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("[compaction] L4 轮次摘要调用失败（跳过该轮）: {}", e.getMessage());
            return null;
        }
    }

    private void appendTranscriptLine(StringBuilder sb, Message m) {
        String content;
        if (m instanceof ToolResponseMessage trm && trm.getResponses() != null) {
            StringBuilder r = new StringBuilder();
            for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
                if (r.length() > 0) {
                    r.append(" | ");
                }
                r.append(resp.name()).append(": ")
                        .append(abbreviate(resp.responseData(), SUMMARY_MSG_CAP_CHARS));
            }
            sb.append("[tool] ").append(r).append('\n');
            return;
        }
        content = m.getText() == null ? "" : m.getText();
        if (m instanceof AssistantMessage am && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
            StringBuilder calls = new StringBuilder();
            for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                if (calls.length() > 0) {
                    calls.append(", ");
                }
                calls.append(tc.name()).append("(").append(abbreviate(tc.arguments(), 200)).append(")");
            }
            sb.append("[assistant 调用工具] ").append(calls);
            if (!content.isBlank()) {
                sb.append(" | 正文: ").append(abbreviate(content, SUMMARY_MSG_CAP_CHARS));
            }
            sb.append('\n');
            return;
        }
        String role = m instanceof UserMessage ? "user" : (m instanceof SystemMessage ? "system" : "assistant");
        sb.append('[').append(role).append("] ").append(abbreviate(content, SUMMARY_MSG_CAP_CHARS)).append('\n');
    }

    // ==================== 小工具 ====================

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
