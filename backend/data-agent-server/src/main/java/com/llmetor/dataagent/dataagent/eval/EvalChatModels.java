package com.llmetor.dataagent.dataagent.eval;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 评测体系专用 ChatModel 构建器（M8）。
 *
 * <p>裁判与诊断 Agent 共用主模型端点（MiniMax OpenAI 兼容），但 temperature=0
 * （判分与诊断结论要求可复现），与主 Agent（0.7）隔离。
 *
 * <p>刻意<b>不注册为 Spring bean</b>：spring-ai-starter-model-openai 自动配置已产出唯一的
 * ChatModel bean（DodoAgent 按类型注入），评测模型再多会破坏 by-type 装配——
 * 因此对齐原版，由各服务在 @PostConstruct 里自建实例。
 * api-key 未配时照常构建（调用期才失败，judge 返回 success=false 的错误响应，不影响启动）。
 *
 * <p>public 可见性：M9 BIRD 评测服务在 {@code eval.bird} 子包，同用本构建器（temperature=0 口径一致）。
 */
public final class EvalChatModels {

    private EvalChatModels() {
    }

    /**
     * @param model       模型名（可用 yml 覆盖，默认跟主模型同款）
     * @param readTimeout HTTP 读超时：裁判单次调用 120s；诊断与 BIRD 是多轮 ReAct 循环放宽到 300s
     */
    public static ChatModel build(String apiKey, String baseUrl, String model, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(readTimeout);
        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(factory))
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(0.0)
                        .build())
                .build();
    }
}
