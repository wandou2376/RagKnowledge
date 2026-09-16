package com.itdou.ragknowledgesystem.infrastructure.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量化客户端的装配。
 */
@Configuration
@Slf4j
public class EmbeddingConfiguration {

    // api-key 留空时的占位值。Spring AI 构建客户端时会校验 key 非空，
    // 给个占位值让应用能起来，真正的调用会被 ChunkEmbeddingService 拦下并给出明确提示。
    private static final String NOT_CONFIGURED_API_KEY = "not-configured";

    /*
     * 手工构建 EmbeddingModel，不用 Spring AI 的 OpenAI 自动配置，原因有两个：
     * 1. spring.ai.openai.* 只有一份 base-url 和 api-key，chat 和 embedding 共用，
     *    而聊天在 DeepSeek、向量化在硅基流动，必须拆开；
     * 2. 自动配置里的 EmbeddingModel bean 带 @ConditionalOnMissingBean，这里定义之后它就不会生效。
     */
    @Bean
    EmbeddingModel embeddingModel(EmbeddingProperties properties) {
        if (!properties.configured()) {
            log.warn("未配置 app.embedding.api-key（环境变量 SILICONFLOW_API_KEY），向量化接口调用时会直接报错");
        }

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.configured() ? properties.apiKey() : NOT_CONFIGURED_API_KEY)
                .build();

        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(properties.model())
                // 刻意不设置 dimensions：硅基流动对 dimensions 参数校验严格，
                // 省略它即使用模型默认的 1024 维，与迁移里的列定义一致。
                .build();

        log.info("向量化模型已装配 model={} baseUrl={} dimension={} batchSize={}",
                properties.model(), properties.baseUrl(), properties.dimension(), properties.batchSize());

        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED, options);
    }
}
