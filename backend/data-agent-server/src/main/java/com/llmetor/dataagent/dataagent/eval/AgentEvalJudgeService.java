package com.llmetor.dataagent.dataagent.eval;

import com.llmetor.dataagent.dataagent.eval.dto.AgentEvalJudgeOutput;
import com.llmetor.dataagent.dataagent.eval.dto.AgentEvalJudgeRequest;
import com.llmetor.dataagent.dataagent.eval.dto.AgentEvalJudgeResponse;
import com.llmetor.dataagent.dataagent.eval.prompt.AgentEvalPrompts;
import com.llmetor.miniagentx.utils.JsonRepairUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * LLM-as-Judge 判分服务（M8，复刻 dodo-agentx AgentEvalJudgeService）：
 * 裸大模型按 rubric 打四维度档位分，加权总分与通过判定由程序计算（正确性 ≥ 3 记通过）。
 *
 * <p>裁判<b>不是 Agent</b>：无工具、temperature=0、单次 ChatModel.call，只比对
 * "用户问题 / 固化标准答案 / Agent 报告"三段文本（报告超 8000 字截断）。
 * 结构化输出四维分缺失视为解析失败，自动重试 1 次。
 */
@Slf4j
@Service
public class AgentEvalJudgeService {

    private static final int PASS_THRESHOLD = 3;
    private static final int ANSWER_MAX_LENGTH = 8000;

    private final ObjectMapper objectMapper;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;
    @Value("${spring.ai.openai.base-url:https://api.minimax.chat}")
    private String baseUrl;
    /** 裁判模型可配（默认跟主模型同款） */
    @Value("${data-agent.agent-eval.judge-model:${spring.ai.openai.chat.options.model:MiniMax-M3.1-Flash}}")
    private String judgeModel;

    private ChatModel judgeChatModel;

    @Autowired
    public AgentEvalJudgeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 测试缝：直接注入打桩 ChatModel（跳过 @PostConstruct 自建） */
    AgentEvalJudgeService(ObjectMapper objectMapper, ChatModel judgeChatModel) {
        this.objectMapper = objectMapper;
        this.judgeChatModel = judgeChatModel;
    }

    @PostConstruct
    void init() {
        if (judgeChatModel != null) {
            return;  // 测试注入过则不重建
        }
        this.judgeChatModel = EvalChatModels.build(apiKey, baseUrl, judgeModel, Duration.ofSeconds(120));
        log.info("[agent-eval] judge initialized | model={}", judgeModel);
    }

    public AgentEvalJudgeResponse judge(AgentEvalJudgeRequest req) {
        long start = System.currentTimeMillis();
        if (req == null || isBlank(req.question()) || isBlank(req.goldAnswer()) || isBlank(req.agentAnswer())) {
            return fail("question/goldAnswer/agentAnswer 不能为空", start);
        }
        try {
            BeanOutputConverter<AgentEvalJudgeOutput> converter =
                    new BeanOutputConverter<>(AgentEvalJudgeOutput.class, objectMapper);
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(AgentEvalPrompts.JUDGE_INSTRUCTIONS
                            + "\n\n## Structured output\n\n" + converter.getFormat()),
                    new UserMessage(AgentEvalPrompts.JUDGE_USER_TEMPLATE.formatted(
                            req.question(), req.goldAnswer(), truncate(req.agentAnswer())))
            ));
            String content;
            AgentEvalJudgeOutput out = null;
            for (int attempt = 1; attempt <= 2 && out == null; attempt++) {
                content = judgeChatModel.call(prompt).getResult().getOutput().getText();
                AgentEvalJudgeOutput parsed = parse(content, converter);
                if (isComplete(parsed)) {
                    out = parsed;
                } else {
                    log.warn("[agent-eval] judge output incomplete (attempt {}/{}) | content={}",
                            attempt, 2, snippet(content, 300));
                }
            }
            if (out == null) {
                return fail("judge failed: 裁判维度分缺失（重试后仍不完整）", start);
            }

            int correctness = requireScore(out.getCorrectness());
            int completeness = requireScore(out.getCompleteness());
            int relevance = requireScore(out.getRelevance());
            int logic = requireScore(out.getLogic());
            double total = correctness * 0.4 + completeness * 0.2 + relevance * 0.2 + logic * 0.2;
            boolean pass = correctness >= PASS_THRESHOLD;
            log.info("[agent-eval] judge | question={}... pass={} scores=正确性{}/完整性{}/相关性{}/逻辑性{} "
                            + "totalScore={} reason={} durationMs={}",
                    snippet(req.question()), pass, correctness, completeness, relevance, logic,
                    round2(total), out.getReason(), System.currentTimeMillis() - start);
            return new AgentEvalJudgeResponse(true, pass, correctness, completeness,
                    relevance, logic, round2(total), text(out.getReason()), null,
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("[agent-eval] judge failed | question={}... err={}",
                    snippet(req.question()), e.getMessage());
            return fail("judge failed: " + e.getMessage(), start);
        }
    }

    /** 结构化解析：先 JsonRepair 修复再转目标类型（MiniMax 偶发尾缀文字/代码围栏） */
    private AgentEvalJudgeOutput parse(String content, BeanOutputConverter<AgentEvalJudgeOutput> converter) {
        String fixed = content;
        try {
            if (!JsonRepairUtil.isValidJson(fixed == null ? "" : fixed.trim())) {
                fixed = JsonRepairUtil.fixJson(fixed);
            }
            return converter.convert(fixed);
        } catch (Exception e) {
            try {
                return converter.convert(content);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private AgentEvalJudgeResponse fail(String error, long start) {
        return new AgentEvalJudgeResponse(false, false, 0, 0, 0, 0, 0.0, null,
                error, System.currentTimeMillis() - start);
    }

    /** 四个维度分齐全才算解析成功。 */
    private static boolean isComplete(AgentEvalJudgeOutput out) {
        return out != null && out.getCorrectness() != null && out.getCompleteness() != null
                && out.getRelevance() != null && out.getLogic() != null;
    }

    private static int requireScore(Integer score) {
        return Math.max(0, Math.min(5, score));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String truncate(String text) {
        return text.length() > ANSWER_MAX_LENGTH
                ? text.substring(0, ANSWER_MAX_LENGTH) + "\n...(报告过长已截断)"
                : text;
    }

    private static String snippet(String text) {
        return snippet(text, 30);
    }

    private static String snippet(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
