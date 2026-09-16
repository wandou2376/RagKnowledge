package com.itdou.ragknowledgesystem.retrieval.service;

import com.itdou.ragknowledgesystem.retrieval.dto.SearchRequest;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchResultResponse;

import java.util.List;
import java.util.UUID;

public interface VectorSearchService {

    // 在指定知识库内做语义检索，返回相似度最高的若干分块
    List<SearchResultResponse> search(UUID knowledgeBaseId, SearchRequest request);
}
