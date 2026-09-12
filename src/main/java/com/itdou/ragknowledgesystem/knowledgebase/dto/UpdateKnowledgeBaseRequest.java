package com.itdou.ragknowledgesystem.knowledgebase.dto;

import com.itdou.ragknowledgesystem.knowledgebase.entity.KnowledgeBaseStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateKnowledgeBaseRequest(
        @NotBlank(message = "知识库名称不能为空")
        @Size(max = 100, message = "知识库名称长度不能超过 100 个字符")
        String name,

        @Size(max = 500, message = "知识库描述长度不能超过 500 个字符")
        String description,

        @NotNull(message = "知识库状态不能为空")
        KnowledgeBaseStatus status
) {
}
