package com.huawei.cloud.sre.rca.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量存储配置。
 *
 * <p>默认使用 {@link SimpleVectorStore} 内存向量存储，适合开发和演示环境。
 * 生产环境可替换为 pgvector、Redis Vector Search 等实现（注入相同的 {@link VectorStore} 接口）。
 *
 * <p>嵌入模型由 {@code spring-ai-starter-model-openai} 自动配置（指向 vLLM 的 Embeddings API）。
 */
@Configuration
public class VectorStoreConfig {

    /**
     * 创建内存向量存储 Bean（dev/test 使用）。
     *
     * <p>该 Bean 可通过自定义 Spring 条件注解在生产环境替换为 pgvector 实现。
     *
     * @param embeddingModel Spring AI 自动配置的嵌入模型
     * @return 内存向量存储实例
     */
    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
