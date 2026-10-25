package com.itdou.ragknowledgesystem.document.dto;

import com.itdou.ragknowledgesystem.document.entity.DocumentParseStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次文档解析的结果统计。
 *
 * @param pageCount            逻辑页数。含义随格式变化：PDF 是物理页，Word 是分页符切出的段，
 *                             Excel 是工作表个数——前端据此换单位显示
 * @param emptyPageCount       提取不到文本的逻辑页数（扫描版 PDF 页、空工作表）
 * @param chunkCount           实际入库的分块数
 * @param duplicateChunkCount  因为内容重复被跳过的分块数
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
