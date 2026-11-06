package com.llmetor.dataagent.dataagent.file;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * EmbeddingModel 手动装配（M5，DashScope 兼容 OpenAI 协议，text-embedding-v3 / 1024 维）。
 *
 * <p>刻意不走 spring.ai.openai 自动配置——主模型是 MiniMax，Embedding 是另一家端点+密钥，
 * 必须独立 builder。HTTP 用 JDK HttpClient（不引入 reactor-netty，与多模态同一思路）。
 * 只在配置了 api-key 时创建；EmbeddingService 对 Bean 缺失/初始化失败都做了降级。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "data-agent.embeddings.api-key")
public class EmbeddingModelConfig {

    @Value("${data-agent.embeddings.base-url}")
    private String baseUrl;

    @Value("${data-agent.embeddings.api-key}")
    private String apiKey;

    @Value("${data-agent.embeddings.model:text-embedding-v3}")
    private String model;

    @Bean
    public EmbeddingModel embeddingModel() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(300)); // 长文本批量向量化留足时间

        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder()
                        .requestFactory(factory))
                .build();

        log.info("[file] EmbeddingModel 构建完成: {} @ {}", model, baseUrl);
        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder().model(model).build());
    }
}
