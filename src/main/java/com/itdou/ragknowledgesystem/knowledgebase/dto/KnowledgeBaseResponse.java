package com.itdou.ragknowledgesystem.knowledgebase.dto;

import com.itdou.ragknowledgesystem.knowledgebase.entity.KnowledgeBaseStatus;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeBaseResponse(
        UUID id,
        String name,
        String description,
        KnowledgeBaseStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
