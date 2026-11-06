package com.llmetor.dataagent.dataagent.config;

import com.llmetor.miniagentx.context.ContextPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 上下文压缩策略装配（M7）：阈值全部可经 yml 覆盖（联调时调小阈值可真实触发压缩，
 * 生产保持默认——对齐 docs/02 §4.1：100 条 / 90K token / 保护区 50 条）。
 */
@Configuration
public class AgentxContextConfig {

    @Bean
    public ContextPolicy contextPolicy(
            @Value("${data-agent.agent.context-policy.last-keep:50}") int lastKeep,
            @Value("${data-agent.agent.context-policy.msg-threshold:100}") int msgThreshold,
            @Value("${data-agent.agent.context-policy.token-threshold:90000}") int tokenThreshold,
            @Value("${data-agent.agent.context-policy.large-payload-tokens:2000}") int largePayloadTokens,
            @Value("${data-agent.agent.context-policy.offload-preview-chars:200}") int offloadPreviewChars,
            @Value("${data-agent.agent.context-policy.max-llm-compression-count:3}") int maxLlmCompressionCount) {
        return ContextPolicy.builder()
                .lastKeep(lastKeep)
                .msgThreshold(msgThreshold)
                .tokenThreshold(tokenThreshold)
                .largePayloadTokens(largePayloadTokens)
                .offloadPreviewChars(offloadPreviewChars)
                .maxLlmCompressionCount(maxLlmCompressionCount)
                .build();
    }
}
