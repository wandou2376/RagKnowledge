package com.itdou.ragknowledgesystem.retrieval.service;

import com.itdou.ragknowledgesystem.retrieval.dto.SearchRequest;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchResultResponse;

import java.util.List;
import java.util.UUID;

/**
 * 检索的统一入口：校验知识库、按模式分发到向量或关键词通道、必要时融合。
 */
public interface ChunkSearchService {

    List<SearchResultResponse> search(UUID knowledgeBaseId, SearchRequest request);
}
