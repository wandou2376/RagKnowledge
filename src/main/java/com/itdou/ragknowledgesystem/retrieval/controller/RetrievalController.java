package com.itdou.ragknowledgesystem.retrieval.controller;

import com.itdou.ragknowledgesystem.common.response.Result;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchRequest;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchResultResponse;
import com.itdou.ragknowledgesystem.retrieval.service.VectorSearchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 检索接口。用 POST 而不是 GET：问题文本可能较长，放在请求体里比塞进 URL 安全，也不受 URL 长度限制。
 */
@RestController
@RequestMapping("/api/knowledge-bases")
public class RetrievalController {

    private final VectorSearchService vectorSearchService;

    public RetrievalController(VectorSearchService vectorSearchService) {
        this.vectorSearchService = vectorSearchService;
    }

    @PostMapping("/{knowledgeBaseId}/search")
    public Result<List<SearchResultResponse>> search(
            @PathVariable UUID knowledgeBaseId,
            @Valid @RequestBody SearchRequest request
    ) {
        return Result.success(vectorSearchService.search(knowledgeBaseId, request));
    }
}
