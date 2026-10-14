package com.itdou.ragknowledgesystem.chat.dto;

import java.util.UUID;

/**
 * 回答里可引用的一条来源。
 *
 * @param index            引用编号，对应回答里的 [n]
 * @param cited            模型是否真的引用了它。false 表示只是召回了但没被用到，
 *                         前端可以据此决定要不要展示
 * @param pageNumberStart  起始页码，可能为 null
 * @param pageNumberEnd    结束页码，可能为 null
 * @param score            检索分数。注意问答链路固定走 HYBRID 检索，所以这里是 RRF 分数，
 *                         只反映排名、不是相似度；需要相似度请另调 /search 并指定 mode=VECTOR
 */
public record CitationResponse(
        int index,
        boolean cited,
        UUID chunkId,
        UUID documentId,
        String originalFileName,
        String content,
        Integer pageNumberStart,
        Integer pageNumberEnd,
        double score
) {
}
