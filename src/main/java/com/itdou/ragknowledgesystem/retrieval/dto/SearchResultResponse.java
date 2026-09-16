package com.itdou.ragknowledgesystem.retrieval.dto;

import java.util.UUID;

/**
 * 语义检索的命中结果。
 *
 * 页码、原文和来源文件名都在这里，阶段 5 的引用溯源可以直接用。
 *
 * @param score 余弦相似度，1 表示完全一致，越接近 1 越相关
 */
public record SearchResultResponse(
        UUID chunkId,
        UUID documentId,
        String originalFileName,
        int chunkIndex,
        String content,
        Integer pageNumberStart,
        Integer pageNumberEnd,
        int tokenCount,
        double score
) {
}
