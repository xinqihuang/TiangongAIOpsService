package com.huawei.cloud.sre.common.llm;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * vLLM Chat 客户端配置。
 *
 * <p>vLLM 部署在 ECS 上，兼容 OpenAI API 格式，使用 Spring AI 的 OpenAI Starter 接入。
 * 配置低 temperature 保证推理输出稳定可复现。
 */
@Configuration
public class VllmChatClientConfig {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Double temperature;
    private final Integer maxTokens;

    /**
     * @param baseUrl     vLLM 服务地址，如 http://10.0.0.1:8000
     * @param apiKey      vLLM API Key（vLLM 默认无需鉴权，填任意非空字符串即可）
     * @param model       模型名称，如 Qwen2.5-72B-Instruct
     * @param temperature 推理温度，推荐 0.1 保证确定性
     * @param maxTokens   最大生成 Token 数
     */
    public VllmChatClientConfig(
            @Value("${spring.ai.openai.base-url:http://localhost:8000}") String baseUrl,
            @Value("${spring.ai.openai.api-key:not-needed}") String apiKey,
            @Value("${spring.ai.openai.chat.options.model:Qwen2.5-72B-Instruct}") String model,
            @Value("${spring.ai.openai.chat.options.temperature:0.1}") Double temperature,
            @Value("${spring.ai.openai.chat.options.max-tokens:4096}") Integer maxTokens
    ) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    /**
     * 构建 OpenAI 兼容的 ChatModel，指向 ECS 上的 vLLM 服务。
     *
     * @return Spring AI ChatModel，可直接注入 ChatClient.Builder
     */
    @Bean
    public OpenAiChatModel vllmChatModel() {
        var openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        var options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }
}
