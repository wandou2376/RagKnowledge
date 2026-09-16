package com.itdou.ragknowledgesystem.infrastructure.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Embedding 服务配置，对应 application-dev.yml 里的 app.embedding。
 *
 * 之所以单独配一份而不是复用 spring.ai.openai：聊天走 DeepSeek、向量化走硅基流动，
 * 是两家不同的服务，而 Spring AI 的 OpenAI 配置只有一份 base-url 和 api-key。
 */
@ConfigurationProperties(prefix = "app.embedding")
public record EmbeddingProperties(
        @DefaultValue("https://api.siliconflow.cn") String baseUrl,
        @DefaultValue("BAAI/bge-m3") String model,
        // 留空表示还没配置。此时应用能正常启动，只有真正调用向量化接口时才报错，
        // 这样没拿到 Key 也能先跑通上传和解析。
        @DefaultValue("") String apiKey,
        // 必须与 V5 迁移里 embedding vector(N) 的 N 一致
        @DefaultValue("1024") int dimension,
        // 单次请求最多提交多少个分块，避免触发服务端的批量上限
        @DefaultValue("16") int batchSize
) {

    public boolean configured() {
        return !apiKey.isBlank();
    }
}
