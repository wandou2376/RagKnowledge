package com.itdou.ragknowledgesystem.retrieval.service;

import com.itdou.ragknowledgesystem.document.repository.ChunkSearchProjection;
import com.itdou.ragknowledgesystem.document.repository.DocumentChunkRepository;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchRequest;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchResultResponse;
import com.itdou.ragknowledgesystem.retrieval.keyword.KeywordTerms;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 关键词召回通道。用 pg_trgm 的 ILIKE 匹配，不调用任何外部服务。
 */
@Service
@Slf4j
public class KeywordSearchServiceImpl implements KeywordSearchService {

    private static final int DEFAULT_TOP_K = 5;
    private static final String TERM_SEPARATOR = "|";

    private final DocumentChunkRepository documentChunkRepository;

    public KeywordSearchServiceImpl(DocumentChunkRepository documentChunkRepository) {
        this.documentChunkRepository = documentChunkRepository;
    }

    @Override
    public List<SearchResultResponse> search(UUID knowledgeBaseId, SearchRequest request) {
        int topK = request.topK() == null ? DEFAULT_TOP_K : request.topK();

        Set<String> terms = KeywordTerms.extract(request.query());
        if (terms.isEmpty()) {
            log.debug("问题里没有可用的关键词元，跳过关键词召回 knowledgeBaseId={}", knowledgeBaseId);
            return List.of();
        }
        log.debug("关键词召回 knowledgeBaseId={} topK={} 词元={}", knowledgeBaseId, topK, terms);

        String termLiteral = String.join(TERM_SEPARATOR, terms);
        List<ChunkSearchProjection> hits = documentChunkRepository
                .searchByKeywords(knowledgeBaseId, termLiteral, topK);

        log.debug("关键词召回完成 knowledgeBaseId={} 命中={} 条", knowledgeBaseId, hits.size());
        return hits.stream().map(KeywordSearchServiceImpl::toResponse).toList();
    }

    private static SearchResultResponse toResponse(ChunkSearchProjection hit) {
        return new SearchResultResponse(
                hit.getChunkId(),
                hit.getDocumentId(),
                hit.getOriginalFileName(),
                hit.getChunkIndex(),
                hit.getContent(),
                hit.getPageNumberStart(),
                hit.getPageNumberEnd(),
                hit.getTokenCount(),
                hit.getScore(),
                null,
                hit.getScore());
    }
}
