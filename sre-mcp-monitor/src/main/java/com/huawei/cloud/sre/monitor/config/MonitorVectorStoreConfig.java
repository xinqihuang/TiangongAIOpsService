package com.huawei.cloud.sre.monitor.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Monitor 模块向量存储配置。
 *
 * <p>使用 {@link SimpleVectorStore} 内存向量存储存放应急预案（应急预案 RAG 知识库）。
 * 生产环境可替换为 pgvector 等持久化实现，只需提供相同的 {@link VectorStore} Bean。
 *
 * <p>嵌入模型复用 {@code sre-mcp-common} 的 {@code vllmEmbeddingModel} Bean（nomic-embed-text / bge-m3）。
 */
@Configuration
public class MonitorVectorStoreConfig {

    /**
     * 创建应急预案向量存储 Bean。
     *
     * @param embeddingModel Spring AI 自动注入的嵌入模型（来自 common 模块）
     * @return 内存向量存储实例
     */
    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
