package com.llmetor.dataagent.dataagent.eval;

import com.llmetor.dataagent.dataagent.eval.dto.AgentDiagnoseOutput;
import com.llmetor.dataagent.dataagent.eval.dto.AgentDiagnoseRequest;
import com.llmetor.dataagent.dataagent.eval.dto.AgentDiagnoseResponse;
import com.llmetor.dataagent.dataagent.eval.prompt.AgentDiagnosePrompts;
import com.llmetor.dataagent.dataagent.eval.trace.TraceQueryTools;
import com.llmetor.miniagentx.agent.ReactAgent;
import com.llmetor.miniagentx.model.RunnableParams;
import com.llmetor.miniagentx.model.ThinkingMode;
import com.llmetor.miniagentx.utils.JsonRepairUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 问题诊断服务（M8，复刻 dodo-agentx AgentDiagnoseService）：
 * 判错之后起一个带 trace 工具的 ReactAgent 定位问题。
 *
 * <p>与裁判（{@link AgentEvalJudgeService}，裸大模型只比对结果）互补：诊断 Agent 关注执行过程，
 * 注入三个 trace 查询工具（全貌/单轮/首现），找到第一次出错的轮次并归因到
 * 模型能力/提示词/工具/框架四类。结构化输出用框架原生 RunnableParams.outputType。
 *
 * <p>诊断 Agent 构造时<b>不传 dataSource</b>（enableSession=false 语义）、enableTrace=false，
 * 不落库不写 trace，避免污染被测的评测记录；temperature=0 保证结论可复现；
 * 单例构建：工具与指令固定，call() 每次创建独立执行器，无共享状态。
 */
@Slf4j
@Service
public class AgentDiagnoseService {

    private static final int MAX_ROUNDS = 50;

    private final ObjectMapper objectMapper;
    private final TraceQueryTools traceQueryTools;
    private final ThinkingMode thinkingMode;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;
    @Value("${spring.ai.openai.base-url:https://api.minimax.chat}")
    private String baseUrl;
    @Value("${data-agent.agent-eval.diagnose-model:${spring.ai.openai.chat.options.model:MiniMax-M3.1-Flash}}")
    private String diagnoseModel;

    private ReactAgent diagnoseAgent;

    public AgentDiagnoseService(ObjectMapper objectMapper,
                                TraceQueryTools traceQueryTools,
                                @Value("${data-agent.agent.thinking-mode:REASONING_CONTENT}")
                                ThinkingMode thinkingMode) {
        this.objectMapper = objectMapper;
        this.traceQueryTools = traceQueryTools;
        this.thinkingMode = thinkingMode;
    }

    @PostConstruct
    void init() {
        String instructions = AgentDiagnosePrompts.DIAGNOSE_INSTRUCTIONS;
        // 专用模型：temperature=0 可复现，读超时 300s（多轮循环）；不自建 bean（见 EvalChatModels）
        var chatModel = EvalChatModels.build(apiKey, baseUrl, diagnoseModel, Duration.ofSeconds(300));
        // 不传 dataSource/sessionStore → 会话三表与 trace 全关（M8-D01）
        this.diagnoseAgent = ReactAgent.builder()
                .chatModel(chatModel)
                .instructions(instructions)
                .tools(List.of(ToolCallbacks.from(traceQueryTools)))
                .thinkingMode(thinkingMode)
                .maxRounds(MAX_ROUNDS)
                .enableTrace(false)
                .enableCompaction(true)  // 长会话诊断也防爆上下文；offload 走内存（无 DB）
                .build();
        log.info("[agent-eval] diagnose agent initialized | model={} maxRounds={} thinkingMode={}",
                diagnoseModel, MAX_ROUNDS, thinkingMode);
    }

    public AgentDiagnoseResponse diagnose(AgentDiagnoseRequest req) {
        long start = System.currentTimeMillis();
        if (req == null || isBlank(req.conversationId())) {
            return fail(null, "conversationId 不能为空", start);
        }
        try {
            String query = AgentDiagnosePrompts.DIAGNOSE_USER_TEMPLATE.formatted(
                    req.conversationId(),
                    nz(req.question()), nz(req.goldAnswer()),
                    nz(req.agentAnswer()), nz(req.judgeReason()));

            // 结构化输出走框架原生 outputType：框架注入 schema 约束 + 内核 JsonRepair 修复。
            // MiniMax 偶发在字符串值里写裸换行/未转义引号导致仍不可解析：宽松解析三级兜底
            // （原文 → fixJson → 花括号截取），仍失败带格式提醒重试一次 Agent 调用
            RunnableParams params = buildParams(req.conversationId());
            String json = null;
            AgentDiagnoseOutput out = null;
            for (int attempt = 1; attempt <= 2 && out == null; attempt++) {
                json = diagnoseAgent.call(query, params).getAnswer();
                out = parseLenient(json);
                if (out == null) {
                    log.warn("[agent-eval] diagnose 输出 JSON 解析失败 (attempt {}/{}) | content={}",
                            attempt, 2, snippet(json));
                }
            }
            if (out == null) {
                return fail(req.conversationId(),
                        "诊断输出不完整（JSON 解析失败，重试后仍失败）：" + snippet(json), start);
            }
            if (isBlank(out.getAttribution())) {
                return fail(req.conversationId(),
                        "诊断输出不完整（attribution 为空）：" + snippet(json), start);
            }
            log.info("[agent-eval] diagnose | conversationId={} root={}/{} attribution={} durationMs={}",
                    req.conversationId(), out.getRootSessionId(), out.getRootRound(),
                    out.getAttribution(), System.currentTimeMillis() - start);
            return new AgentDiagnoseResponse(true, req.conversationId(),
                    text(out.getRootSessionId()), out.getRootRound(),
                    text(out.getAttribution()), text(out.getEvidence()),
                    text(out.getAnalysis()), text(out.getSuggestion()),
                    null, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("[agent-eval] diagnose failed | conversationId={} err={}",
                    req.conversationId(), e.getMessage(), e);
            return fail(req.conversationId(), "diagnose failed: " + e.getMessage(), start);
        }
    }

    private AgentDiagnoseResponse fail(String conversationId, String error, long start) {
        return new AgentDiagnoseResponse(false, conversationId, null, null,
                null, null, null, null, error, System.currentTimeMillis() - start);
    }

    /**
     * 诊断调用参数。conversationId 必传：内核 {@code RunnableParams.Builder.build()} 硬校验非空，
     * 缺失会在任何 LLM 调用前 2ms 抛异常（M8 回归：上线后真实诊断全部失败、联调只探过空参契约
     * 短路所以未暴露）。本 Agent 无 dataSource 不落库，该值只作压缩 offload 的内存键与日志标签。
     */
    static RunnableParams buildParams(String conversationId) {
        return RunnableParams.builder()
                .conversationId(conversationId)
                .outputType(AgentDiagnoseOutput.class)
                .build();
    }

    /**
     * 宽松解析三级兜底：原文直接读 → JsonRepair 修复 → 截取首尾花括号再修复
     * （模型偶发在 JSON 前后带说明文字/代码围栏，或字符串值里有裸换行/未转义引号——
     * 内核 JsonRepair 修不动的形态在这里再兜一层）。attribution 为空视为解析失败。
     */
    private AgentDiagnoseOutput parseLenient(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        String[] candidates = {json, JsonRepairUtil.fixJson(json), extractBraces(JsonRepairUtil.fixJson(json))};
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            try {
                AgentDiagnoseOutput out = objectMapper.readValue(candidate, AgentDiagnoseOutput.class);
                if (!isBlank(out.getAttribution())) {
                    return out;
                }
            } catch (Exception ignored) {
                // 尝试下一个候选
            }
        }
        return null;
    }

    private static String extractBraces(String s) {
        int begin = s.indexOf('{');
        int end = s.lastIndexOf('}');
        return (begin >= 0 && end > begin) ? s.substring(begin, end + 1) : null;
    }

    private static String nz(String value) {
        return value == null || value.isBlank() ? "（未提供）" : value;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
