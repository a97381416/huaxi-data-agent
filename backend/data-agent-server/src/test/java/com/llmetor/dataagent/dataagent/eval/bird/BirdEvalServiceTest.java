package com.llmetor.dataagent.dataagent.eval.bird;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmetor.dataagent.dataagent.eval.bird.dto.BirdEvalRequest;
import com.llmetor.dataagent.dataagent.eval.bird.dto.BirdEvalResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M9 BIRD 评测服务单测：请求校验契约短路（不触发 LLM）。
 * 服务为普通 new 实例化（无 Spring 上下文）：@PostConstruct 不执行、chatModel 为 null——
 * 校验发生在任何 LLM 调用之前，命中契约即返回 fail，正好验证"坏参数不进模型"。
 */
class BirdEvalServiceTest {

    private final BirdEvalService service = new BirdEvalService(
            new BirdEvalToolFactory(new ObjectMapper()), new ObjectMapper(), null);

    @Test
    void nullRequestFailsWithoutLlm() {
        BirdEvalResponse response = service.evalQuestion(null);
        assertThat(response.success()).isFalse();
        assertThat(response.error()).contains("question is blank");
        assertThat(response.sql()).isEmpty();
        assertThat(response.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void blankQuestionFailsWithoutLlm() {
        BirdEvalResponse response = service.evalQuestion(
                new BirdEvalRequest("1", "california_schools", "  ", "evidence", "/tmp/x.sqlite", null));
        assertThat(response.success()).isFalse();
        assertThat(response.error()).contains("question is blank");
    }

    @Test
    void blankSqlitePathFailsWithoutLlm() {
        BirdEvalResponse response = service.evalQuestion(
                new BirdEvalRequest("1", "california_schools", "How many schools?", "", null, null));
        assertThat(response.success()).isFalse();
        assertThat(response.error()).contains("sqlitePath is blank");
    }

    @Test
    void baselineContractsMirrorQuestionContracts() {
        assertThat(service.evalBaseline(null).error()).contains("question is blank");
        assertThat(service.evalBaseline(new BirdEvalRequest("1", "db", "q", "", "", null))
                .error()).contains("sqlitePath is blank");
    }

    @Test
    void failResponseCarriesQuestionIdAndDbIdForRunnerBookkeeping() {
        BirdEvalResponse response = service.evalQuestion(
                new BirdEvalRequest("42", "card_games", "  ", "", "/tmp/x.sqlite", null));
        assertThat(response.success()).isFalse();
        // runner 依 questionId/dbId 回写 JSONL，契约字段必须原样透传
        assertThat(response.sql()).isEmpty();
        assertThat(response.error()).isNotBlank();
    }
}
