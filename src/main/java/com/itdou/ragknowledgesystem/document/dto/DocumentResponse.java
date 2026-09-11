package com.itdou.ragknowledgesystem.document.dto;

import com.itdou.ragknowledgesystem.document.entity.DocumentParseStatus;

import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        UUID knowledgeBaseId,
        String originalFileName,
        String mediaType,
        long fileSize,
        DocumentParseStatus parseStatus,
        Instant createdAt,
        Instant updatedAt
) {
}
