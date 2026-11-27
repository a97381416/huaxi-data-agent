package com.llmetor.dataagent.dataagent.hitl;

import com.llmetor.dataagent.auth.HuaxiAuthContext;
import com.llmetor.dataagent.dataagent.hitl.DecisionModels.*;
import com.llmetor.miniagentx.interrupt.HitlParkException;
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
 * 三期/业务改造 HITL：收集型内置工具（ask_user_question）。
 *
 * <p>双模式安全与交互规范：
 * <ul>
 *   <li>每次最多 4 题，支持 single（单选）、multiple（多选）、text（填空）及选项副标题 {@code optionDescriptions}；</li>
 *   <li>可选澄清模式（{@code required=false}，默认）：轻量短等待（60s），超时或跳过均不卡死会话，返回默认假设提示给 LLM 继续执行；</li>
 *   <li>业务必选模式（{@code required=true}）：禁止跳过，超 60s 转入慢路径挂起（{@link HitlParkException}，默认 30 分钟有效），绝不让 AI 替人盲猜；</li>
 *   <li>权限红线：无论何种模式，绝对永不授予任何工具权限（grantable=false，不写 agentx_decision_grant）。</li>
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
            @ToolParam(description = "选项副标题映射（键为候选项文案，值为单行字符串，如\"草稿批次 DOC-202611 · 已填 2/8 项\"；不要传数组，可选）", required = false) Map<String, Object> optionDescriptions,
            @ToolParam(description = "是否允许用户输入自定义“其他”选项，默认 true", required = false) Boolean allowCustom
    ) {
        /** 向下兼容 5 参构造（无需传递 optionDescriptions） */
        public QuestionItem(String id, String title, String type, List<String> options, Boolean allowCustom) {
            this(id, title, type, options, null, allowCustom);
        }
    }

    /** 向下兼容 4 参调用入口（默认 required=false 可选澄清） */
    public String askUserQuestion(List<QuestionItem> questions, String contextHint,
                                  String userId, String conversationId) {
        return askUserQuestion(questions, contextHint, false, userId, conversationId);
    }

    @Tool(name = TOOL_NAME,
            description = "向用户弹出 1~4 个结构化问题卡片（支持 single 单选、multiple 多选、text 填空及 optionDescriptions 选项副标题）。" +
                    "required=true 为业务必选（禁止跳过，超 60 秒转后台挂起待办，适用于选项目/草稿/阶段/节点/待审或被驳任务/审核结论与意见/文件类别绑定）；" +
                    "required=false（默认）为需求口径轻量澄清（60 秒超时或跳过则按默认假设继续）。")
    public String askUserQuestion(
            @ToolParam(description = "问题列表（1~4 题）", required = true) List<QuestionItem> questions,
            @ToolParam(description = "提问背景或引导标题（可选；必选模式下优先作为卡片标题展示）", required = false) String contextHint,
            @ToolParam(description = "是否必答：true=业务必选（禁止跳过，超60s转挂起待办）；false=可选澄清（默认，60s自动跳过）", required = false) Boolean required,
            @ToolParam(description = "用户身份标识，由框架自动注入真实值", required = false) String userId,
            @ToolParam(description = "会话标识，由框架自动注入真实值", required = false) String conversationId) {

        boolean isRequired = Boolean.TRUE.equals(required);
        Long uid = parseUserId(userId);
        if (uid == null) {
            return isRequired
                    ? "❌ 未获取到当前用户身份，无法发起业务必选确认。"
                    : "❌ 未获取到当前用户身份，请按默认假设继续或引导用户补充说明。";
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
            if (q.optionDescriptions() != null && !q.optionDescriptions().isEmpty()) {
                Map<String, String> descs = new LinkedHashMap<>();
                q.optionDescriptions().forEach((k, v) -> {
                    String textVal = normalizeDescValue(v);
                    if (StringUtils.hasText(k) && StringUtils.hasText(textVal)) {
                        descs.put(k.trim(), textVal.trim());
                    }
                });
                if (!descs.isEmpty()) {
                    item.put("optionDescriptions", descs);
                }
            }
            item.put("allowCustom", q.allowCustom() != null ? q.allowCustom() : !"text".equals(qType));
            normalized.add(item);
        }

        Map<String, Object> rawPayload = new LinkedHashMap<>();
        rawPayload.put("questions", normalized);
        rawPayload.put("required", isRequired);
        if (StringUtils.hasText(contextHint)) {
            rawPayload.put("contextHint", contextHint.trim());
        }

        String actionLabel = isRequired ? "需要您做出选择确认" : "需求细节确认";
        String summary = StringUtils.hasText(contextHint)
                ? contextHint.trim()
                : (isRequired
                        ? "请完成 " + normalized.size() + " 项业务确认"
                        : "需要您补充 " + normalized.size() + " 项需求细节");

        // 必选模式采用默认 30 分钟有效期（null），可选澄清保持 10 分钟
        Integer ttlMinutes = isRequired ? null : 10;

        String hash = decisionService.payloadHash(rawPayload);
        CreateResult created = decisionService.createOrAutoApprove(new CreateDecisionRequest(
                conversationId, conversationId, null, null, uid,
                TYPE_QUESTION, TOOL_NAME, null, null, false,
                actionLabel, "共 " + normalized.size() + " 个问题",
                List.of(HitlToolHelper.kp("问题数量", normalized.size() + " 题")),
                summary, rawPayload, hash, ttlMinutes));

        DecisionEntity entity = created.entity();
        String decId = entity.decisionId();

        // 慢路径恢复前置短路：若决策单已作答或已结束，直接消费返回，绝不二次弹窗或重新阻塞
        if (STATUS_ANSWERED.equals(entity.status())) {
            decisionService.emitResolved(entity);
            return formatAnsweredResult(entity.answerJson());
        }
        if (STATUS_SKIPPED.equals(entity.status()) || STATUS_TIMED_OUT_SOFT.equals(entity.status())) {
            decisionService.emitWithdrawn(entity);
            return "⏭️ 用户选择跳过需求补充。请先简要说明您的默认假设口径，然后继续完成任务。";
        }
        if (STATUS_EXPIRED.equals(entity.status()) || STATUS_SUPERSEDED.equals(entity.status())) {
            decisionService.emitWithdrawn(entity);
            return "⏹️ 该需求澄清提问已被新对话取代或已过期。";
        }

        // 双模式等待分流：required=true 落 PauseState 支持挂起恢复；required=false 轻量软等待 60s
        DecisionAwaitRegistry.AwaitResult res = decisionService.awaitDecision(decId, isRequired);
        return switch (res.state()) {
            case ANSWERED -> {
                DecisionEntity updated = decisionService.findById(decId);
                decisionService.emitResolved(updated != null ? updated : entity);
                yield formatAnsweredResult(res.answerJson());
            }
            case SKIPPED -> {
                DecisionEntity updated = decisionService.findById(decId);
                decisionService.emitWithdrawn(updated != null ? updated : entity);
                yield "⏭️ 用户选择跳过需求补充。请先简要说明您的默认假设口径，然后继续完成任务。";
            }
            case PARKED -> {
                if (isRequired) {
                    throw new HitlParkException(decId, "等待用户选择确认超时，已转入后台挂起");
                }
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

    private static String formatAnsweredResult(String answerJson) {
        return "✅ 用户已完成需求细节补充（答案 JSON）：\n"
                + (StringUtils.hasText(answerJson) ? answerJson : "{}")
                + "\n请严格依据上述用户补充的细节继续完成任务。";
    }

    private static String normalizeDescValue(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof List<?> list) {
            return list.stream()
                    .filter(it -> it != null && StringUtils.hasText(String.valueOf(it)))
                    .map(it -> String.valueOf(it).trim())
                    .reduce((a, b) -> a + " · " + b)
                    .orElse(null);
        }
        return String.valueOf(v);
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
