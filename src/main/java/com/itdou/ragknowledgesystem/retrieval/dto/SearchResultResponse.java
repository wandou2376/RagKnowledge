package com.itdou.ragknowledgesystem.retrieval.dto;

import java.util.UUID;

/**
 * 检索的命中结果。
 *
 * 页码、原文和来源文件名都在这里，阶段 5 的引用溯源可以直接用。
 *
 * @param score        主分数。单通道模式下就是该通道的分数；融合模式下是 RRF 分数，
 *                     它的绝对值没有直观含义（只反映排名），不要跟相似度混为一谈
 * @param vectorScore  向量通道的余弦相似度，该通道没参与本次检索时为 null
 * @param keywordScore 关键词通道的分数（命中词元长度之和），该通道没参与时为 null
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
        double score,
        Double vectorScore,
        Double keywordScore
) {
}
