package com.llmetor.dataagent.dataagent.hitl;

import com.llmetor.dataagent.auth.HuaxiAuthContext;
import com.llmetor.dataagent.dataagent.hitl.DecisionModels.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.llmetor.dataagent.dataagent.hitl.DecisionModels.*;

/**
 * 三期 HITL：收集型内置工具（ask_user_question）。
 *
 * <p>安全与交互规范：
 * <ul>
 *   <li>每次最多 4 题，支持 single（单选）、multiple（多选）、text（填空）；</li>
 *   <li>轻量短等待（60s），超时或用户跳过均不卡死会话，返回默认假设提示给 LLM 继续执行；</li>
 *   <li>权限红线：绝对永不授予任何工具权限（grantable=false，不写 agentx_decision_grant）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AskUserQuestionTool {

    public static final String TOOL_NAME = "ask_user_question";
    private static final int MAX_QUESTIONS = 4;

    private final DecisionService decisionService;

    public record QuestionItem(
            @ToolParam(description = "问题唯一标识，如 Q1、Q2", required = false) String id,
            @ToolParam(description = "问题正文", required = true) String title,
            @ToolParam(description = "题型：single（单选）、multiple（多选）、text（文本输入）", required = false) String type,
            @ToolParam(description = "候选项列表（single/multiple 时必填）", required = false) List<String> options,
            @ToolParam(description = "是否允许用户输入自定义“其他”选项，默认 true", required = false) Boolean allowCustom
    ) {
    }

    @Tool(name = TOOL_NAME,
            description = "当用户需求缺少关键口径、对象或范围时，向用户弹出 1~4 个结构化问题（支持单选、多选、自定义输入）进行需求澄清。" +
                    "用户提交后返回结构化 JSON 答案；若用户点跳过或 60 秒超时，请说明默认假设口径并继续完成任务。")
    public String askUserQuestion(
            @ToolParam(description = "问题列表（1~4 题）", required = true) List<QuestionItem> questions,
            @ToolParam(description = "提问背景简述（可选）", required = false) String contextHint,
            @ToolParam(description = "用户身份标识，由框架自动注入真实值", required = false) String userId,
            @ToolParam(description = "会话标识，由框架自动注入真实值", required = false) String conversationId) {

        Long uid = parseUserId(userId);
        if (uid == null) {
            return "❌ 未获取到当前用户身份，请按默认假设继续或引导用户补充说明。";
        }
        if (questions == null || questions.isEmpty()) {
            return "❌ 问题列表不能为空，请至少提供 1 个问题。";
        }
        if (questions.size() > MAX_QUESTIONS) {
            return "❌ 单次最多只能提出 " + MAX_QUESTIONS + " 个问题（当前 " + questions.size() + " 个），请精简后重新调用。";
        }

        List<Map<String, Object>> normalized = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            QuestionItem q = questions.get(i);
            if (q == null || !StringUtils.hasText(q.title())) {
                return "❌ 第 " + (i + 1) + " 题缺少问题正文 title。";
            }
            String qType = StringUtils.hasText(q.type()) ? q.type().trim().toLowerCase() : "single";
            if (!"single".equals(qType) && !"multiple".equals(qType) && !"text".equals(qType)) {
                qType = "single";
            }
            List<String> opts = q.options() == null ? List.of() : q.options().stream()
                    .filter(StringUtils::hasText).map(String::trim).toList();
            if (("single".equals(qType) || "multiple".equals(qType)) && opts.isEmpty()) {
                return "❌ 第 " + (i + 1) + " 题为" + ("single".equals(qType) ? "单选" : "多选") + "题，必须提供候选项 options。";
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", StringUtils.hasText(q.id()) ? q.id().trim() : "Q" + (i + 1));
            item.put("title", q.title().trim());
            item.put("type", qType);
            item.put("options", opts);
            item.put("allowCustom", q.allowCustom() != null ? q.allowCustom() : !"text".equals(qType));
            normalized.add(item);
        }

        Map<String, Object> rawPayload = new LinkedHashMap<>();
        rawPayload.put("questions", normalized);
        if (StringUtils.hasText(contextHint)) {
            rawPayload.put("contextHint", contextHint.trim());
        }

        String summary = StringUtils.hasText(contextHint)
                ? contextHint.trim()
                : "需要您补充 " + normalized.size() + " 项需求细节";

        String hash = decisionService.payloadHash(rawPayload);
        CreateResult created = decisionService.createOrAutoApprove(new CreateDecisionRequest(
                conversationId, conversationId, null, null, uid,
                TYPE_QUESTION, TOOL_NAME, null, null, false,
                "需求细节确认", "共 " + normalized.size() + " 个问题",
                List.of(HitlToolHelper.kp("问题数量", normalized.size() + " 题")),
                summary, rawPayload, hash, 10));

        DecisionEntity entity = created.entity();
        String decId = entity.decisionId();

        // 轻量短等待（60s，不落 PauseState，超时不卡死会话）
        DecisionAwaitRegistry.AwaitResult res = decisionService.awaitDecision(decId, false);
        return switch (res.state()) {
            case ANSWERED -> {
                DecisionEntity updated = decisionService.findById(decId);
                decisionService.emitResolved(updated != null ? updated : entity);
                yield "✅ 用户已完成需求细节补充（答案 JSON）：\n"
                        + (StringUtils.hasText(res.answerJson()) ? res.answerJson() : "{}")
                        + "\n请严格依据上述用户补充的细节继续完成任务。";
            }
            case SKIPPED -> {
                DecisionEntity updated = decisionService.findById(decId);
                decisionService.emitWithdrawn(updated != null ? updated : entity);
                yield "⏭️ 用户选择跳过需求补充。请先简要说明您的默认假设口径，然后继续完成任务。";
            }
            case PARKED -> {
                decisionService.markSoftTimedOut(decId);
                yield "⌛ 用户在 60 秒内未作答（已自动跳过，流程不中断）。请先简要说明您的默认假设口径，然后继续完成任务。";
            }
            case EXPIRED, SUPERSEDED -> {
                DecisionEntity updated = decisionService.findById(decId);
                decisionService.emitWithdrawn(updated != null ? updated : entity);
                yield "⏹️ 该需求澄清提问已被新对话取代或已过期。";
            }
            default -> "⏭️ 用户未补充细节，请说明默认假设并继续完成任务。";
        };
    }

    private static Long parseUserId(String userId) {
        if (StringUtils.hasText(userId)) {
            try {
                return Long.parseLong(userId.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return HuaxiAuthContext.getCurrentUserIdOrNull();
    }
}
