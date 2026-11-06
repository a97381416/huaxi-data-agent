package com.llmetor.dataagent.dataagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmetor.dataagent.dataagent.eval.dto.AgentEvalJudgeRequest;
import com.llmetor.dataagent.dataagent.eval.dto.AgentEvalJudgeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M8 裁判单测：打桩 ChatModel，验证程序算分（权重/round2/pass=correctness≥3）、
 * clamp 0-5、报告 8000 字截断、四维缺失自动重试 1 次、参数校验。
 */
class AgentEvalJudgeServiceTest {

    /** 每次调用弹出预置文本的打桩 ChatModel，并捕获全部请求 Prompt */
    static class StubChatModel implements ChatModel {
        final Deque<String> script = new ArrayDeque<>();
        final List<Prompt> captured = new ArrayList<>();

        StubChatModel(String... responses) {
            for (String r : responses) {
                script.add(r);
            }
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            captured.add(prompt);
            String next = script.poll();
            if (next == null) {
                throw new IllegalStateException("脚本已耗尽");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(next))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            throw new UnsupportedOperationException("裁判只走 call");
        }
    }

    private StubChatModel stub;
    private AgentEvalJudgeService service;

    @BeforeEach
    void setUp() {
        stub = new StubChatModel();
        service = new AgentEvalJudgeService(new ObjectMapper(), stub);
    }

    private static String judgeJson(Integer c, Integer comp, Integer rel, Integer logic, String reason) {
        return "{\"correctness\":%s,\"completeness\":%s,\"relevance\":%s,\"logic\":%s,\"reason\":\"%s\"}"
                .formatted(c, comp, rel, logic, reason == null ? "" : reason);
    }

    // ── 程序算分 ──

    @Test
    void 满分通过() {
        stub.script.add(judgeJson(5, 5, 5, 5, "完全一致"));
        AgentEvalJudgeResponse resp = service.judge(new AgentEvalJudgeRequest("q", "g", "a"));
        assertThat(resp.success()).isTrue();
        assertThat(resp.pass()).isTrue();
        assertThat(resp.totalScore()).isEqualTo(5.0);
        assertThat(resp.correctness()).isEqualTo(5);
        assertThat(resp.reason()).isEqualTo("完全一致");
    }

    @Test
    void 加权总分与通过判定由程序计算() {
        stub.script.add(judgeJson(4, 3, 3, 2, null));
        AgentEvalJudgeResponse resp = service.judge(new AgentEvalJudgeRequest("q", "g", "a"));
        // 4*0.4 + 3*0.2 + 3*0.2 + 2*0.2 = 3.2
        assertThat(resp.totalScore()).isEqualTo(3.2);
        assertThat(resp.pass()).isTrue();  // correctness≥3
    }

    @Test
    void correctness低于3不通过() {
        stub.script.add(judgeJson(2, 5, 5, 5, "数值编造"));
        AgentEvalJudgeResponse resp = service.judge(new AgentEvalJudgeRequest("q", "g", "a"));
        // 2*0.4 + 5*0.2 + 5*0.2 + 5*0.2 = 3.8 —— 总分再高，正确性不达标也不通过
        assertThat(resp.totalScore()).isEqualTo(3.8);
        assertThat(resp.pass()).isFalse();
    }

    @Test
    void 维度分clamp到0到5() {
        stub.script.add(judgeJson(7, -1, 5, 5, null));
        AgentEvalJudgeResponse resp = service.judge(new AgentEvalJudgeRequest("q", "g", "a"));
        assertThat(resp.correctness()).isEqualTo(5);
        assertThat(resp.completeness()).isEqualTo(0);
        // 5*0.4 + 0 + 5*0.2 + 5*0.2 = 4.0
        assertThat(resp.totalScore()).isEqualTo(4.0);
    }

    // ── 截断 ──

    @Test
    void 超长报告截断送判() {
        stub.script.add(judgeJson(5, 5, 5, 5, null));
        service.judge(new AgentEvalJudgeRequest("q", "g", "长".repeat(9000)));
        assertThat(stub.captured).hasSize(1);
        String userText = stub.captured.get(0).getInstructions().stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> ((UserMessage) m).getText())
                .findFirst().orElseThrow();
        assertThat(userText).contains("...(报告过长已截断)");
        assertThat(userText.length()).isLessThan(9000);
    }

    // ── 重试 ──

    @Test
    void 四维缺失自动重试一次() {
        // 第一次缺 relevance，第二次齐全
        stub.script.add(judgeJson(5, 5, null, 5, null));
        stub.script.add(judgeJson(5, 5, 4, 5, "补齐"));
        AgentEvalJudgeResponse resp = service.judge(new AgentEvalJudgeRequest("q", "g", "a"));
        assertThat(resp.success()).isTrue();
        assertThat(resp.relevance()).isEqualTo(4);
        assertThat(stub.captured).hasSize(2);
    }

    @Test
    void 重试后仍缺失返回失败() {
        stub.script.add(judgeJson(5, 5, null, 5, null));
        stub.script.add("不是JSON");
        AgentEvalJudgeResponse resp = service.judge(new AgentEvalJudgeRequest("q", "g", "a"));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error()).contains("裁判维度分缺失");
        assertThat(stub.captured).hasSize(2);
    }

    // ── 参数校验与模型异常 ──

    @Test
    void 参数缺失直接失败不调模型() {
        AgentEvalJudgeResponse resp = service.judge(new AgentEvalJudgeRequest(null, "g", "a"));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error()).contains("不能为空");
        assertThat(stub.captured).isEmpty();
    }

    @Test
    void 模型调用异常包装成失败响应() {
        StubChatModel broken = new StubChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new RuntimeException("连接超时");
            }
        };
        AgentEvalJudgeService brokenService = new AgentEvalJudgeService(new ObjectMapper(), broken);
        AgentEvalJudgeResponse resp = brokenService.judge(new AgentEvalJudgeRequest("q", "g", "a"));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error()).contains("连接超时");
    }

    // ── 提示词契约 ──

    @Test
    void 提示词含rubric与结构化格式约束() {
        stub.script.add(judgeJson(5, 5, 5, 5, null));
        service.judge(new AgentEvalJudgeRequest("q", "g", "a"));
        String systemText = stub.captured.get(0).getInstructions().stream()
                .filter(m -> m instanceof org.springframework.ai.chat.messages.SystemMessage)
                .map(m -> ((org.springframework.ai.chat.messages.SystemMessage) m).getText())
                .findFirst().orElseThrow();
        assertThat(systemText).contains("Agent 报告裁判");
        assertThat(systemText).contains("±1% 容差");
        assertThat(systemText).contains("任一轮编造数据，正确性记 0 分");
        assertThat(systemText).contains("## Structured output");
    }
}
