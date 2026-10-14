package com.itdou.ragknowledgesystem.document.dto;

import com.itdou.ragknowledgesystem.document.entity.DocumentParseStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * 文档元数据。
 *
 * @param errorMessage 解析失败的原因，成功时为 null。前端要把它显示在文档列表里，
 *                     否则状态为 FAILED 的行只能看到一个「失败」，看不出为什么
 * @param parsedAt     解析完成时间，未解析或解析失败时为 null
 */
public record DocumentResponse(
        UUID id,
        UUID knowledgeBaseId,
        String originalFileName,
        String mediaType,
        long fileSize,
        DocumentParseStatus parseStatus,
        String errorMessage,
        Instant parsedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
