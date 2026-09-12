package com.itdou.ragknowledgesystem.document.dto;

import java.time.Instant;
import java.util.UUID;

public record DocumentChunkResponse(
        UUID id,
        UUID documentId,
        int chunkIndex,
        String content,
        String contentHash,
        Integer pageNumberStart,
        Integer pageNumberEnd,
        int tokenCount,
        Instant createdAt
) {
}
