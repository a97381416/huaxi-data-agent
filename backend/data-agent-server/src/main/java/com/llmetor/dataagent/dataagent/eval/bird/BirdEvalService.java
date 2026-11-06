package com.llmetor.dataagent.dataagent.eval.bird;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmetor.dataagent.dataagent.eval.EvalChatModels;
import com.llmetor.dataagent.dataagent.eval.bird.dto.BirdAgentOutput;
import com.llmetor.dataagent.dataagent.eval.bird.dto.BirdEvalRequest;
import com.llmetor.dataagent.dataagent.eval.bird.dto.BirdEvalResponse;
import com.llmetor.dataagent.dataagent.eval.bird.prompt.BirdEvalPrompts;
import com.llmetor.dataagent.dataagent.eval.bird.BirdEvalToolFactory.BirdToolBundle;
import com.llmetor.miniagentx.agent.ReactAgent;
import com.llmetor.miniagentx.model.RunnableParams;
import com.llmetor.miniagentx.model.ThinkingMode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * BIRD single-question evaluation service.
 *
 * <p>This service builds a separate ReactAgent for the BIRD text-to-SQL task.
 * It must not share the production data-agent's SQL validation or permission
 * pipeline because BIRD compares the submitted SQL directly through its own
 * execution-result metric.</p>
 *
 * <p>M9 自原版 dodo-agentx 移植，三处适配（其余逻辑零改动）：
 * ① 模型装配 DeepSeekV4ChatModel → 复用 M8 {@link EvalChatModels}（OpenAI 兼容端点，
 * temperature=0、读超时 300s）；② ReactAgent 换自研内核，装配对齐
 * {@code AgentDiagnoseService}——不传 dataSource（会话三表与 trace 全关，不落库）+
 * enableTrace=false；③ 内核 {@link RunnableParams} 硬校验 conversationId 非空，
 * 每题合成 {@code bird-{questionId}}（仅作日志标签，不落库）。</p>
 */
@Slf4j
@Service
public class BirdEvalService {

    private static final int DEFAULT_MAX_ROUNDS = 50;

    private final BirdEvalToolFactory toolFactory;
    private final ObjectMapper objectMapper;
    private final ThinkingMode thinkingMode;

    @Value("${spring.ai.openai.base-url:https://api.minimax.chat}")
    private String baseUrl;
    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;
    @Value("${data-agent.bird-eval.model:${spring.ai.openai.chat.options.model:MiniMax-M3.1-Flash}}")
    private String model;
    @Value("${data-agent.bird-eval.max-rounds:" + DEFAULT_MAX_ROUNDS + "}")
    private int defaultMaxRounds;
    private ChatModel chatModel;

    public BirdEvalService(BirdEvalToolFactory toolFactory,
                           ObjectMapper objectMapper,
                           @Value("${data-agent.agent.thinking-mode:REASONING_CONTENT}")
                           ThinkingMode thinkingMode) {
        this.toolFactory = toolFactory;
        this.objectMapper = objectMapper;
        this.thinkingMode = thinkingMode;
    }

    @PostConstruct
    void init() {
        this.chatModel = EvalChatModels.build(apiKey, baseUrl, model, Duration.ofSeconds(300));
        log.info("[bird-eval] initialized | model={} defaultMaxRounds={} thinkingMode={}",
                model, defaultMaxRounds, thinkingMode);
    }

    public BirdEvalResponse evalQuestion(BirdEvalRequest req) {
        long start = System.currentTimeMillis();
        BirdEvalResponse invalid = validateRequest(req, start);
        if (invalid != null) {
            return invalid;
        }
        String questionId = text(req.questionId());
        String dbId = text(req.dbId());

        try {
            BirdAgentOutput output = callAgent(req, buildQuery(req));
            String sql = output.getSql() == null ? "" : output.getSql().trim();
            if (sql.isBlank()) {
                return fail(questionId, dbId, "agent returned blank SQL", start);
            }
            return new BirdEvalResponse(true, sql, null, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("[bird-eval] agent execution failed | questionId={} err={}",
                    questionId, e.getMessage(), e);
            return fail(questionId, dbId, "agent execution failed: " + e.getMessage(), start);
        }
    }

    public BirdEvalResponse evalBaseline(BirdEvalRequest req) {
        long start = System.currentTimeMillis();
        BirdEvalResponse invalid = validateRequest(req, start);
        if (invalid != null) {
            return invalid;
        }
        String questionId = text(req.questionId());
        String dbId = text(req.dbId());

        try {
            BeanOutputConverter<BirdAgentOutput> outputConverter =
                    new BeanOutputConverter<>(BirdAgentOutput.class, objectMapper);
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(buildBaselineInstructions(req, outputConverter.getFormat())),
                    new UserMessage(buildQuery(req))
            ));
            String content = chatModel.call(prompt).getResult().getOutput().getText();
            BirdAgentOutput output = outputConverter.convert(content);
            String sql = output.getSql() == null ? "" : output.getSql().trim();
            if (sql.isBlank()) {
                return fail(questionId, dbId, "baseline returned blank SQL", start);
            }
            return new BirdEvalResponse(true, sql, null, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("[bird-eval] baseline execution failed | questionId={} err={}",
                    questionId, e.getMessage(), e);
            return fail(questionId, dbId, "baseline execution failed: " + e.getMessage(), start);
        }
    }

    /**
     * 每题新建 ReactAgent：三工具指向该题 SQLite 文件（无共享状态）。评测链不落库不写 trace，
     * enableCompaction=false——BIRD 单题任务轮次少，压缩反而有损（对齐原版无压缩行为）。
     */
    private ReactAgent buildAgent(BirdEvalRequest req) {
        BirdToolBundle bundle = toolFactory.create(req.sqlitePath(), buildReviewContext(req));
        // ToolCallbacks.from 返回数组，压平成单一 List<ToolCallback>
        List<ToolCallback> tools = Stream.of(
                        bundle.listTablesTool(), bundle.describeTablesTool(), bundle.verifySqlTool())
                .map(ToolCallbacks::from)
                .flatMap(Arrays::stream)
                .toList();
        return ReactAgent.builder()
                .chatModel(chatModel)
                .instructions(buildInstructions(req))
                .tools(tools)
                .thinkingMode(thinkingMode)
                .maxRounds(resolveMaxRounds(req))
                .enableTrace(false)
                .enableCompaction(false)
                .build();
    }

    private BirdAgentOutput callAgent(BirdEvalRequest req, String query) throws Exception {
        String questionId = text(req.questionId());
        // conversationId 内核硬校验非空（M8 教训），合成 id 仅作日志标签——本 Agent 不落库
        RunnableParams params = RunnableParams.builder()
                .conversationId("bird-" + (questionId.isBlank() ? "unknown" : questionId))
                .outputType(BirdAgentOutput.class)
                .build();
        String json = buildAgent(req).call(query, params).getAnswer();
        return objectMapper.readValue(json, BirdAgentOutput.class);
    }

    private int resolveMaxRounds(BirdEvalRequest req) {
        return req.maxRounds() == null || req.maxRounds() <= 0
                ? defaultMaxRounds
                : req.maxRounds();
    }

    private BirdEvalResponse validateRequest(BirdEvalRequest req, long start) {
        if (req == null || req.question() == null || req.question().isBlank()) {
            return fail(text(req == null ? null : req.questionId()),
                    text(req == null ? null : req.dbId()), "question is blank", start);
        }
        if (req.sqlitePath() == null || req.sqlitePath().isBlank()) {
            return fail(text(req.questionId()), text(req.dbId()), "sqlitePath is blank", start);
        }
        return null;
    }

    private BirdEvalResponse fail(String questionId, String dbId, String error, long start) {
        log.warn("[bird-eval] questionId={} dbId={} failed: {}", questionId, dbId, error);
        return new BirdEvalResponse(false, "", error, System.currentTimeMillis() - start);
    }

    private String buildInstructions(BirdEvalRequest req) {
        return BirdEvalPrompts.COMMON_INSTRUCTIONS
                + "\n\n" + BirdEvalPrompts.AGENT_INSTRUCTIONS
                + "\n\n## Current database\n\nDatabase ID: "
                + text(req.dbId());
    }

    private String buildBaselineInstructions(BirdEvalRequest req, String structuredOutputFormat) {
        return BirdEvalPrompts.COMMON_INSTRUCTIONS
                + "\n\n" + BirdEvalPrompts.BASELINE_INSTRUCTIONS
                + "\n\n## Current database\n\nDatabase ID: "
                + text(req.dbId())
                + "\n\n## Complete schema\n\n"
                + toolFactory.describeAllTables(req.sqlitePath())
                + "\n\n## Structured output\n\n"
                + structuredOutputFormat;
    }

    private String buildReviewContext(BirdEvalRequest req) {
        return "Question: " + req.question() + "\nEvidence: "
                + (req.evidence() == null || req.evidence().isBlank() ? "(none)" : req.evidence());
    }

    private String buildQuery(BirdEvalRequest req) {
        String evidence = req.evidence() == null || req.evidence().isBlank()
                ? "(none)"
                : req.evidence();
        return """
                Generate one SQLite SELECT/WITH query for the following BIRD question.

                Question:
                %s

                Evidence:
                %s
                """.formatted(req.question(), evidence);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
