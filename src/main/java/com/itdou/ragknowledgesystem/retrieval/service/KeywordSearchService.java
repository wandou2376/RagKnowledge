package com.itdou.ragknowledgesystem.retrieval.service;

import com.itdou.ragknowledgesystem.retrieval.dto.SearchRequest;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchResultResponse;

import java.util.List;
import java.util.UUID;

/**
 * 关键词召回通道。不依赖外部服务，Redis 或 Embedding 挂掉时仍然可用。
 */
public interface KeywordSearchService {

    List<SearchResultResponse> search(UUID knowledgeBaseId, SearchRequest request);
}
