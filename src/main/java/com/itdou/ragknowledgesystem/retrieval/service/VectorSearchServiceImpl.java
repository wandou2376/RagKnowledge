package com.itdou.ragknowledgesystem.retrieval.service;

import com.itdou.ragknowledgesystem.document.repository.ChunkSearchProjection;
import com.itdou.ragknowledgesystem.document.repository.DocumentChunkRepository;
import com.itdou.ragknowledgesystem.infrastructure.ai.EmbeddingProperties;
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
 * 向量召回通道：把问题向量化，再在 pgvector 里按余弦相似度取 Top-K。
 *
 * 它是 RetrievalMode.HYBRID 的两条通道之一，知识库是否存在的校验在
 * ChunkSearchServiceImpl 里统一做，这里只管召回本身。
 */
@Service
@Slf4j
public class VectorSearchServiceImpl implements VectorSearchService {

    private static final int DEFAULT_TOP_K = 5;

    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingProperties properties;

    public VectorSearchServiceImpl(
            DocumentChunkRepository documentChunkRepository,
            EmbeddingModel embeddingModel,
            EmbeddingProperties properties
    ) {
        this.documentChunkRepository = documentChunkRepository;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
    }

    @Override
    public List<SearchResultResponse> search(UUID knowledgeBaseId, SearchRequest request) {
        if (!properties.configured()) {
            throw new EmbeddingFailedException("未配置 Embedding 服务，请先设置环境变量 SILICONFLOW_API_KEY");
        }

        int topK = request.topK() == null ? DEFAULT_TOP_K : request.topK();
        log.debug("向量召回 knowledgeBaseId={} topK={} 问题长度={}",
                knowledgeBaseId, topK, request.query().length());

        String literal = VectorLiteral.toLiteral(embedQuery(request.query()), properties.dimension());
        List<ChunkSearchProjection> hits = documentChunkRepository
                .searchByEmbedding(knowledgeBaseId, literal, topK);

        log.debug("向量召回完成 knowledgeBaseId={} 命中={} 条", knowledgeBaseId, hits.size());
        return hits.stream().map(VectorSearchServiceImpl::toResponse).toList();
    }

    private float[] embedQuery(String query) {
        try {
            return embeddingModel.embed(query);
        } catch (RuntimeException exception) {
            // 堆栈统一由 GlobalExceptionHandler 打，这里只负责转换成 503 的业务异常
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
                hit.getScore(),
                hit.getScore(),
                null);
    }
}
