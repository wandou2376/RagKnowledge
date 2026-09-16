package com.itdou.ragknowledgesystem.retrieval.service;

import com.itdou.ragknowledgesystem.document.repository.ChunkSearchProjection;
import com.itdou.ragknowledgesystem.document.repository.DocumentChunkRepository;
import com.itdou.ragknowledgesystem.infrastructure.ai.EmbeddingProperties;
import com.itdou.ragknowledgesystem.knowledgebase.exception.KnowledgeBaseNotFoundException;
import com.itdou.ragknowledgesystem.knowledgebase.repository.KnowledgeBaseRepository;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchRequest;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchResultResponse;
import com.itdou.ragknowledgesystem.retrieval.exception.EmbeddingFailedException;
import com.itdou.ragknowledgesystem.retrieval.vector.VectorLiteral;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 语义检索：把问题向量化，再在 pgvector 里按余弦相似度取 Top-K。
 */
@Service
@Slf4j
public class VectorSearchServiceImpl implements VectorSearchService {

    private static final int DEFAULT_TOP_K = 5;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingProperties properties;

    public VectorSearchServiceImpl(
            KnowledgeBaseRepository knowledgeBaseRepository,
            DocumentChunkRepository documentChunkRepository,
            EmbeddingModel embeddingModel,
            EmbeddingProperties properties
    ) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
    }

    @Override
    public List<SearchResultResponse> search(UUID knowledgeBaseId, SearchRequest request) {
        if (!properties.configured()) {
            throw new EmbeddingFailedException("未配置 Embedding 服务，请先设置环境变量 SILICONFLOW_API_KEY");
        }
        if (!knowledgeBaseRepository.existsById(knowledgeBaseId)) {
            throw new KnowledgeBaseNotFoundException(knowledgeBaseId);
        }

        int topK = request.topK() == null ? DEFAULT_TOP_K : request.topK();
        log.info("语义检索开始 knowledgeBaseId={} topK={} 问题长度={}",
                knowledgeBaseId, topK, request.query().length());
        // 问题原文放 debug：它是用户输入，正文里不重复打
        log.debug("检索问题原文: {}", request.query());

        String literal = VectorLiteral.toLiteral(embedQuery(request.query()), properties.dimension());
        List<ChunkSearchProjection> hits = documentChunkRepository
                .searchByEmbedding(knowledgeBaseId, literal, topK);

        log.info("语义检索完成 knowledgeBaseId={} 命中 {} 条", knowledgeBaseId, hits.size());
        if (hits.isEmpty()) {
            log.warn("知识库内没有可检索的分块（可能还没做向量化）knowledgeBaseId={}", knowledgeBaseId);
        }
        return hits.stream().map(VectorSearchServiceImpl::toResponse).toList();
    }

    private float[] embedQuery(String query) {
        try {
            return embeddingModel.embed(query);
        } catch (RuntimeException exception) {
            log.error("检索问题向量化失败", exception);
            throw new EmbeddingFailedException("检索问题向量化失败: " + exception.getMessage(), exception);
        }
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
                hit.getScore()
        );
    }
}
