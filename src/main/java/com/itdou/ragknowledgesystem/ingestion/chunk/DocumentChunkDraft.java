package com.itdou.ragknowledgesystem.ingestion.chunk;

/**
 * 分块结果，尚未落库。
 *
 * @param chunkIndex     同一文档内从 0 开始，已按去重后的顺序重新编号
 * @param content        分块正文，多行用换行符连接
 * @param contentHash    正文的 SHA-256，用于知识库内去重
 * @param pageNumberStart 分块起始页码
 * @param pageNumberEnd   分块结束页码
 * @param tokenCount     正文的 token 数，由分词器实际统计
 */
public record DocumentChunkDraft(
        int chunkIndex,
        String content,
        String contentHash,
        Integer pageNumberStart,
        Integer pageNumberEnd,
        int tokenCount
) {
}
