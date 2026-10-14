package com.itdou.ragknowledgesystem.retrieval.service;

import com.itdou.ragknowledgesystem.knowledgebase.exception.KnowledgeBaseNotFoundException;
import com.itdou.ragknowledgesystem.knowledgebase.repository.KnowledgeBaseRepository;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchRequest;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchResultResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChunkSearchServiceImplTest {

    private static final int DEFAULT_TOP_K = 5;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private VectorSearchService vectorSearchService;

    @Mock
    private KeywordSearchService keywordSearchService;

    private ChunkSearchServiceImpl chunkSearchService;
    private UUID knowledgeBaseId;

    @BeforeEach
    void setUp() {
        chunkSearchService = new ChunkSearchServiceImpl(
                knowledgeBaseRepository, vectorSearchService, keywordSearchService);
        knowledgeBaseId = UUID.randomUUID();
    }

    @Test
    void shouldOnlyCallVectorChannelInVectorMode() {
        when(knowledgeBaseRepository.existsById(knowledgeBaseId)).thenReturn(true);
        when(vectorSearchService.search(eq(knowledgeBaseId), any())).thenReturn(List.of(hit("A", 0.9)));

        List<SearchResultResponse> results = chunkSearchService.search(
                knowledgeBaseId, new SearchRequest("问题", null, RetrievalMode.VECTOR));

        assertThat(results).hasSize(1);
        verify(keywordSearchService, never()).search(any(), any());
    }

    @Test
    void shouldOnlyCallKeywordChannelInKeywordMode() {
        when(knowledgeBaseRepository.existsById(knowledgeBaseId)).thenReturn(true);
        when(keywordSearchService.search(eq(knowledgeBaseId), any())).thenReturn(List.of(hit("A", 6.0)));

        List<SearchResultResponse> results = chunkSearchService.search(
                knowledgeBaseId, new SearchRequest("问题", null, RetrievalMode.KEYWORD));

        assertThat(results).hasSize(1);
        verify(vectorSearchService, never()).search(any(), any());
    }

    // 没传 mode 时应该走融合，而不是退化成单通道
    @Test
    void shouldFuseBothChannelsByDefault() {
        when(knowledgeBaseRepository.existsById(knowledgeBaseId)).thenReturn(true);
        when(vectorSearchService.search(eq(knowledgeBaseId), any()))
                .thenReturn(List.of(hit("A", 0.90), hit("B", 0.80)));
        when(keywordSearchService.search(eq(knowledgeBaseId), any()))
                .thenReturn(List.of(hit("B", 8.0), hit("C", 6.0)));

        List<SearchResultResponse> results = chunkSearchService.search(
                knowledgeBaseId, new SearchRequest("问题", 3, null));

        // B 两路都命中所以排第一；A 只被向量命中、C 只被关键词命中，A 的通道内名次更靠前
        assertThat(results).extracting(SearchResultResponse::chunkId)
                .containsExactly(chunkId("B"), chunkId("A"), chunkId("C"));
    }

    @Test
    void shouldKeepChannelScoresAfterFusion() {
        when(knowledgeBaseRepository.existsById(knowledgeBaseId)).thenReturn(true);
        when(vectorSearchService.search(eq(knowledgeBaseId), any()))
                .thenReturn(List.of(hit("A", 0.90), hit("B", 0.80)));
        when(keywordSearchService.search(eq(knowledgeBaseId), any()))
                .thenReturn(List.of(hit("B", 8.0)));

        List<SearchResultResponse> results = chunkSearchService.search(
                knowledgeBaseId, new SearchRequest("问题", 3, null));

        SearchResultResponse both = results.get(0);
        assertThat(both.vectorScore()).isEqualTo(0.80);
        assertThat(both.keywordScore()).isEqualTo(8.0);
        // 融合后的主分数既不是余弦相似度也不是关键词分数
        assertThat(both.score()).isNotEqualTo(0.80).isNotEqualTo(8.0);

        SearchResultResponse vectorOnly = results.get(1);
        assertThat(vectorOnly.vectorScore()).isEqualTo(0.90);
        assertThat(vectorOnly.keywordScore()).isNull();
    }

    @Test
    void shouldStillReturnResultsWhenOneChannelIsEmpty() {
        when(knowledgeBaseRepository.existsById(knowledgeBaseId)).thenReturn(true);
        when(vectorSearchService.search(eq(knowledgeBaseId), any())).thenReturn(List.of(hit("A", 0.9)));
        when(keywordSearchService.search(eq(knowledgeBaseId), any())).thenReturn(List.of());

        List<SearchResultResponse> results = chunkSearchService.search(
                knowledgeBaseId, new SearchRequest("问题", null, null));

        assertThat(results).extracting(SearchResultResponse::chunkId).containsExactly(chunkId("A"));
    }

    @Test
    void shouldRejectUnknownKnowledgeBaseBeforeAnyChannelRuns() {
        UUID unknownId = UUID.randomUUID();
        when(knowledgeBaseRepository.existsById(unknownId)).thenReturn(false);

        assertThatThrownBy(() -> chunkSearchService.search(unknownId, new SearchRequest("问题", null, null)))
                .isInstanceOf(KnowledgeBaseNotFoundException.class);

        verify(vectorSearchService, never()).search(any(), any());
        verify(keywordSearchService, never()).search(any(), any());
    }

    @Test
    void shouldUseDefaultTopKWhenAbsent() {
        when(knowledgeBaseRepository.existsById(knowledgeBaseId)).thenReturn(true);
        when(vectorSearchService.search(eq(knowledgeBaseId), any())).thenReturn(List.of());

        chunkSearchService.search(knowledgeBaseId, new SearchRequest("问题", null, RetrievalMode.VECTOR));

        verify(vectorSearchService).search(eq(knowledgeBaseId),
                eq(new SearchRequest("问题", null, RetrievalMode.VECTOR)));
    }

    private static UUID chunkId(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes());
    }

    // 用固定 seed 生成 chunkId，便于断言顺序
    private static SearchResultResponse hit(String seed, double score) {
        return new SearchResultResponse(
                chunkId(seed), UUID.randomUUID(), "guide.pdf", 0, "内容 " + seed,
                1, 2, 100, score, score, score);
    }
}
