package com.itdou.ragknowledgesystem.retrieval.service;

import com.itdou.ragknowledgesystem.document.repository.ChunkSearchProjection;
import com.itdou.ragknowledgesystem.document.repository.DocumentChunkRepository;
import com.itdou.ragknowledgesystem.infrastructure.ai.EmbeddingProperties;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchRequest;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchResultResponse;
import com.itdou.ragknowledgesystem.retrieval.exception.EmbeddingFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 向量召回通道的单元测试。知识库是否存在的校验在 ChunkSearchServiceImpl，这里不重复测。
 */
@ExtendWith(MockitoExtension.class)
class VectorSearchServiceImplTest {

    private static final int DIMENSION = 1024;
    private static final int DEFAULT_TOP_K = 5;

    @Mock
    private DocumentChunkRepository documentChunkRepository;

    @Mock
    private EmbeddingModel embeddingModel;

    private VectorSearchServiceImpl vectorSearchService;
    private UUID knowledgeBaseId;

    @BeforeEach
    void setUp() {
        EmbeddingProperties properties = new EmbeddingProperties(
                "https://api.siliconflow.cn/v1", "BAAI/bge-m3", "test-api-key", DIMENSION, 16);
        vectorSearchService = new VectorSearchServiceImpl(documentChunkRepository, embeddingModel, properties);
        knowledgeBaseId = UUID.randomUUID();
    }

    @Test
    void shouldReturnHitsWithPageNumbersAndOriginalText() {
        UUID chunkId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        // 必须先建好投影：在 when() 的参数里再调一次会做桩的方法，Mockito 会报 UnfinishedStubbing
        ChunkSearchProjection projection = hit(chunkId, documentId, "E-4012 表示数据库连接超时", 2, 3, 0.87);

        when(embeddingModel.embed("错误码 E-4012 是什么")).thenReturn(vector());
        when(documentChunkRepository.searchByEmbedding(eq(knowledgeBaseId), anyString(), eq(DEFAULT_TOP_K)))
                .thenReturn(List.of(projection));

        List<SearchResultResponse> results = vectorSearchService.search(
                knowledgeBaseId, new SearchRequest("错误码 E-4012 是什么", null, null));

        assertThat(results).hasSize(1);
        SearchResultResponse result = results.get(0);
        assertThat(result.chunkId()).isEqualTo(chunkId);
        assertThat(result.documentId()).isEqualTo(documentId);
        assertThat(result.originalFileName()).isEqualTo("guide.pdf");
        assertThat(result.content()).isEqualTo("E-4012 表示数据库连接超时");
        assertThat(result.pageNumberStart()).isEqualTo(2);
        assertThat(result.pageNumberEnd()).isEqualTo(3);
        assertThat(result.score()).isEqualTo(0.87);
        // 向量通道只填自己的分数，关键词分数留空
        assertThat(result.vectorScore()).isEqualTo(0.87);
        assertThat(result.keywordScore()).isNull();
    }

    @Test
    void shouldUseProvidedTopK() {
        when(embeddingModel.embed("问题")).thenReturn(vector());
        when(documentChunkRepository.searchByEmbedding(eq(knowledgeBaseId), anyString(), eq(20)))
                .thenReturn(List.of());

        vectorSearchService.search(knowledgeBaseId, new SearchRequest("问题", 20, null));

        verify(documentChunkRepository).searchByEmbedding(eq(knowledgeBaseId), anyString(), eq(20));
    }

    @Test
    void shouldReturnEmptyListWhenNothingMatches() {
        when(embeddingModel.embed("问题")).thenReturn(vector());
        when(documentChunkRepository.searchByEmbedding(eq(knowledgeBaseId), anyString(), eq(DEFAULT_TOP_K)))
                .thenReturn(List.of());

        assertThat(vectorSearchService.search(knowledgeBaseId, new SearchRequest("问题", null, null))).isEmpty();
    }

    @Test
    void shouldWrapQueryEmbeddingFailure() {
        when(embeddingModel.embed("问题")).thenThrow(new IllegalStateException("connection reset"));

        assertThatThrownBy(() -> vectorSearchService.search(
                knowledgeBaseId, new SearchRequest("问题", null, null)))
                .isInstanceOf(EmbeddingFailedException.class)
                .hasMessageContaining("检索问题向量化失败")
                .hasRootCauseMessage("connection reset");
    }

    // 一条都没命中时不该去查数据库
    @Test
    void shouldNotQueryDatabaseWhenEmbeddingFails() {
        when(embeddingModel.embed("问题")).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> vectorSearchService.search(
                knowledgeBaseId, new SearchRequest("问题", null, null)))
                .isInstanceOf(EmbeddingFailedException.class);

        verify(documentChunkRepository, never()).searchByEmbedding(any(), anyString(), anyInt());
    }

    private static ChunkSearchProjection hit(UUID chunkId, UUID documentId, String content,
                                             Integer pageStart, Integer pageEnd, double score) {
        ChunkSearchProjection hit = mock(ChunkSearchProjection.class);
        when(hit.getChunkId()).thenReturn(chunkId);
        when(hit.getDocumentId()).thenReturn(documentId);
        when(hit.getOriginalFileName()).thenReturn("guide.pdf");
        when(hit.getContent()).thenReturn(content);
        when(hit.getPageNumberStart()).thenReturn(pageStart);
        when(hit.getPageNumberEnd()).thenReturn(pageEnd);
        when(hit.getScore()).thenReturn(score);
        return hit;
    }

    private static float[] vector() {
        float[] embedding = new float[DIMENSION];
        java.util.Arrays.fill(embedding, 0.1f);
        return embedding;
    }
}
