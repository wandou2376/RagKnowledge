package com.itdou.ragknowledgesystem.ingestion.chunk;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 分块相关参数，对应 application-dev.yml 里的 app.chunking。
 */
@ConfigurationProperties(prefix = "app.chunking")
public record ChunkingProperties(
        // 目标分块大小（token），项目计划建议 500~800
        @DefaultValue("600") int targetTokens,
        // 相邻分块之间的重叠 token 数，避免答案正好被切在边界上
        @DefaultValue("80") int overlapTokens,
        // 尾块小于该值时并入上一块，避免产生只有一两句话的碎片 chunk
        @DefaultValue("120") int minChunkTokens,
        // 页眉页脚判定阈值：同一行出现在多少比例的页面上就认为是重复的页眉或页脚
        @DefaultValue("0.6") double boilerplatePageRatio,
        // 页数少于该值时不做页眉页脚剔除，样本太小容易误判
        @DefaultValue("3") int boilerplateMinPages
) {
}
