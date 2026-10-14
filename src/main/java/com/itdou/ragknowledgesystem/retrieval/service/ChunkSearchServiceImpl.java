package com.itdou.ragknowledgesystem.retrieval.service;

import com.itdou.ragknowledgesystem.knowledgebase.exception.KnowledgeBaseNotFoundException;
import com.itdou.ragknowledgesystem.knowledgebase.repository.KnowledgeBaseRepository;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchRequest;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchResultResponse;
import com.itdou.ragknowledgesystem.retrieval.vector.ReciprocalRankFusion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 检索的统一入口。
 *
 * 三条分支：VECTOR 只用向量、KEYWORD 只用关键词、HYBRID 两路召回后按 RRF 融合。
 * 单通道模式保留下来，既方便排查「某条结果是谁召回的」，也是评测能分别量出
 * 每个通道贡献的前提。
 */
@Service
@Slf4j
public class ChunkSearchServiceImpl implements ChunkSearchService {

    private static final int DEFAULT_TOP_K = 5;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final VectorSearchService vectorSearchService;
    private final KeywordSearchService keywordSearchService;

    public ChunkSearchServiceImpl(
            KnowledgeBaseRepository knowledgeBaseRepository,
            VectorSearchService vectorSearchService,
            KeywordSearchService keywordSearchService
    ) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.vectorSearchService = vectorSearchService;
        this.keywordSearchService = keywordSearchService;
    }

    @Override
    public List<SearchResultResponse> search(UUID knowledgeBaseId, SearchRequest request) {
        if (!knowledgeBaseRepository.existsById(knowledgeBaseId)) {
            throw new KnowledgeBaseNotFoundException(knowledgeBaseId);
        }

        RetrievalMode mode = request.mode() == null ? RetrievalMode.HYBRID : request.mode();
        int topK = request.topK() == null ? DEFAULT_TOP_K : request.topK();

        long startedAt = System.currentTimeMillis();
        log.info("检索开始 knowledgeBaseId={} mode={} topK={} 问题长度={}",
                knowledgeBaseId, mode, topK, request.query().length());
        log.debug("检索问题原文: {}", request.query());

        List<SearchResultResponse> results = switch (mode) {
            case VECTOR -> vectorSearchService.search(knowledgeBaseId, request);
            case KEYWORD -> keywordSearchService.search(knowledgeBaseId, request);
            case HYBRID -> fuse(knowledgeBaseId, request, topK);
        };

        log.info("检索完成 knowledgeBaseId={} mode={} 命中={} 条 耗时={}ms",
                knowledgeBaseId, mode, results.size(), System.currentTimeMillis() - startedAt);
        return results;
    }

    private List<SearchResultResponse> fuse(UUID knowledgeBaseId, SearchRequest request, int topK) {
        // 两条通道各自取 topK 个候选再融合，融合后同样截到 topK
        SearchRequest channelRequest = new SearchRequest(request.query(), topK, null);
        List<SearchResultResponse> vectorHits = vectorSearchService.search(knowledgeBaseId, channelRequest);
        List<SearchResultResponse> keywordHits = keywordSearchService.search(knowledgeBaseId, channelRequest);
        log.debug("融合前 向量={} 条 关键词={} 条", vectorHits.size(), keywordHits.size());

        // 分渠道记分数，这样响应里能看出每条结果是被哪一路召回的
        Map<UUID, Double> vectorScores = new LinkedHashMap<>();
        vectorHits.forEach(hit -> vectorScores.put(hit.chunkId(), hit.score()));
        Map<UUID, Double> keywordScores = new LinkedHashMap<>();
        keywordHits.forEach(hit -> keywordScores.put(hit.chunkId(), hit.score()));

        Map<UUID, SearchResultResponse> byChunkId = new LinkedHashMap<>();
        vectorHits.forEach(hit -> byChunkId.put(hit.chunkId(), hit));
        keywordHits.forEach(hit -> byChunkId.putIfAbsent(hit.chunkId(), hit));

        List<ReciprocalRankFusion.FusedItem<UUID>> fused = ReciprocalRankFusion.fuse(
                List.of(
                        vectorHits.stream().map(SearchResultResponse::chunkId).toList(),
                        keywordHits.stream().map(SearchResultResponse::chunkId).toList()),
                topK);

        return fused.stream()
                .map(item -> withFusedScore(byChunkId.get(item.id()), vectorScores.get(item.id()),
                        keywordScores.get(item.id()), item.score()))
                .toList();
    }

    private static SearchResultResponse withFusedScore(
            SearchResultResponse hit,
            Double vectorScore,
            Double keywordScore,
            double fusedScore
    ) {
        return new SearchResultResponse(
                hit.chunkId(),
                hit.documentId(),
                hit.originalFileName(),
                hit.chunkIndex(),
                hit.content(),
                hit.pageNumberStart(),
                hit.pageNumberEnd(),
                hit.tokenCount(),
                fusedScore,
                vectorScore,
                keywordScore);
    }
}
