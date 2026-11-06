package com.llmetor.dataagent.dataagent.eval;

import com.llmetor.dataagent.dataagent.eval.dto.AgentDiagnoseOutput;
import com.llmetor.miniagentx.model.RunnableParams;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M8 诊断参数单测：防回归 2026-11-04 真实事故——E2E 首跑诊断 2ms 即败
 * "conversationId 不能为空"。根因：诊断服务构造 RunnableParams 只传了 outputType，
 * 而内核 build() 硬校验 conversationId 非空；联调只探过空参契约短路（不触发 LLM），
 * 真实诊断链路从未跑通。修复即 buildParams 收口 + 本测试锁死两件事：
 * ① 参数必须携带 conversationId 且能通过内核校验；② 内核对空值确拒绝（契约本体）。
 */
class AgentDiagnoseServiceTest {

    @Test
    void 诊断参数必须携带conversationId并通过内核校验() {
        RunnableParams params = AgentDiagnoseService.buildParams("eval_862f0f0396a1");
        assertThat(params.getConversationId()).isEqualTo("eval_862f0f0396a1");
        assertThat(params.getOutputType()).isEqualTo(AgentDiagnoseOutput.class);
    }

    @Test
    void 空conversationId内核直接拒绝不进LLM() {
        assertThatThrownBy(() -> AgentDiagnoseService.buildParams("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conversationId");
    }
}
