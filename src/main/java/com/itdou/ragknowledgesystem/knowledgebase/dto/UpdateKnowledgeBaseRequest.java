package com.itdou.ragknowledgesystem.knowledgebase.dto;

import com.itdou.ragknowledgesystem.knowledgebase.entity.KnowledgeBaseStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateKnowledgeBaseRequest(
        @NotBlank(message = "name must not be blank")
        @Size(max = 100, message = "name must not exceed 100 characters")
        String name,

        @Size(max = 500, message = "description must not exceed 500 characters")
        String description,

        @NotNull(message = "status must not be null")
        KnowledgeBaseStatus status
) {
}
