package com.huawei.cloud.sre.common.llm;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embedding 模型客户端配置。
 *
 * <p>用于 RAG（检索增强生成）场景，将故障描述向量化后检索相似历史案例。
 * Embedding 服务同样部署在 ECS 的 vLLM 上（或独立的 Embedding 服务）。
 */
@Configuration
public class EmbeddingClientConfig {

    private final String baseUrl;
    private final String apiKey;
    private final String embeddingModel;

    /**
     * @param baseUrl        Embedding 服务地址（默认与 Chat 服务同地址）
     * @param apiKey         API Key
     * @param embeddingModel Embedding 模型名称，如 bge-m3
     */
    public EmbeddingClientConfig(
            @Value("${spring.ai.openai.embedding.base-url:${spring.ai.openai.base-url:http://localhost:8000}}") String baseUrl,
            @Value("${spring.ai.openai.api-key:not-needed}") String apiKey,
            @Value("${spring.ai.openai.embedding.options.model:bge-m3}") String embeddingModel
    ) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 构建 Embedding 模型 Bean，供 VectorStore 使用。
     *
     * @return Spring AI EmbeddingModel
     */
    @Bean
    public OpenAiEmbeddingModel vllmEmbeddingModel() {
        var openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        var options = OpenAiEmbeddingOptions.builder()
                .model(embeddingModel)
                .build();

        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED, options);
    }
}
