package com.itdou.ragknowledgesystem.document.dto;

import com.itdou.ragknowledgesystem.document.entity.DocumentParseStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次文档解析的结果统计。
 *
 * @param pageCount             PDF 总页数
 * @param emptyPageCount        提取不到文本的页数（扫描版或纯图片页）
 * @param chunkCount            实际入库的分块数
 * @param duplicateChunkCount   因为内容重复被跳过的分块数
 */
public record DocumentParseResponse(
        UUID documentId,
        DocumentParseStatus parseStatus,
        int pageCount,
        int emptyPageCount,
        int chunkCount,
        int duplicateChunkCount,
        Instant parsedAt
) {
}
