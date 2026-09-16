package com.itdou.ragknowledgesystem.document.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次文档向量化的结果统计。
 *
 * @param embeddedCount        本次新写入向量的分块数
 * @param alreadyEmbeddedCount 已经用当前模型向量化过、本次跳过的分块数
 */
public record ChunkEmbeddingResponse(
        UUID documentId,
        String embeddingModel,
        int dimension,
        int embeddedCount,
        int alreadyEmbeddedCount,
        Instant finishedAt
) {
}
