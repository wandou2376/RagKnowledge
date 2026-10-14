package com.itdou.ragknowledgesystem.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itdou.ragknowledgesystem.document.dto.DocumentResponse;
import com.itdou.ragknowledgesystem.document.repository.DocumentChunkRepository;
import com.itdou.ragknowledgesystem.document.service.DocumentService;
import com.itdou.ragknowledgesystem.ingestion.service.DocumentIngestionService;
import com.itdou.ragknowledgesystem.knowledgebase.dto.KnowledgeBaseResponse;
import com.itdou.ragknowledgesystem.knowledgebase.dto.CreateKnowledgeBaseRequest;
import com.itdou.ragknowledgesystem.knowledgebase.service.KnowledgeBaseService;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchRequest;
import com.itdou.ragknowledgesystem.retrieval.dto.SearchResultResponse;
import com.itdou.ragknowledgesystem.retrieval.service.ChunkEmbeddingService;
import com.itdou.ragknowledgesystem.retrieval.service.ChunkSearchService;
import com.itdou.ragknowledgesystem.retrieval.service.RetrievalMode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索效果评测。
 *
 * 为什么要有这个：阶段 7 之后的每一项优化（融合、Rerank、查询改写）都属于「感觉应该更好」的改动，
 * 没有评测集就只能凭感觉调参。这个测试把「好多少」变成数字。
 *
 * 做法：走真实链路建库（上传 test.pdf → 解析 → 向量化），再对评测集里的每个问题用三种模式各检索一次，
 * 计算 Recall@K 和 MRR。评测集的期望答案是先从真实分块内容里反查出来的，不是凭印象写的。
 *
 * 需要真实数据库、真实的 Embedding 与聊天模型额度，用 RUN_EXTERNAL_TESTS=true 开启，默认跳过。
 * 测试结束会删掉自己建的知识库，不留数据。
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "RUN_EXTERNAL_TESTS", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RetrievalEvaluationIT {

    /*
     * 必须用多档 K 一起看：语料只有 5 个分块，一旦 K >= 分块总数，
     * 每种模式都会返回全部分块，Recall 恒等于 1，指标完全失去区分度。
     * K 小于语料规模时 Recall 才有意义。
     */
    private static final List<Integer> TOP_KS = List.of(1, 2, 3);
    private static final String EVAL_SET_RESOURCE = "retrieval-eval-set.json";

    // 低于这个线说明检索明显退化了，需要查原因，而不是简单调低阈值
    private static final double MRR_FLOOR = 0.50;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentIngestionService documentIngestionService;

    @Autowired
    private ChunkEmbeddingService chunkEmbeddingService;

    @Autowired
    private ChunkSearchService chunkSearchService;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID knowledgeBaseId;
    private UUID documentId;
    private List<EvalCase> evalSet;

    @BeforeAll
    void prepareCorpus() throws Exception {
        KnowledgeBaseResponse knowledgeBase = knowledgeBaseService.create(
                new CreateKnowledgeBaseRequest("检索评测临时库", "评测用完即删"));

        DocumentResponse document;
        try (InputStream pdf = getClass().getClassLoader().getResourceAsStream("test.pdf")) {
            assertThat(pdf).as("测试用的 test.pdf 必须存在").isNotNull();
            document = documentService.upload(knowledgeBase.id(), new MockMultipartFile(
                    "file", "test.pdf", "application/pdf", pdf));
        }

        documentIngestionService.parse(document.id());
        chunkEmbeddingService.embedDocument(document.id());

        knowledgeBaseId = knowledgeBase.id();
        documentId = document.id();
        evalSet = loadEvalSet();

        System.out.println("评测语料就绪：分块数=" + documentChunkRepository.countByDocumentId(documentId)
                + " 评测问题数=" + evalSet.size());
    }

    @AfterAll
    void cleanUp() {
        if (knowledgeBaseId != null) {
            // 知识库删除会级联清掉文档、分块和物理文件
            knowledgeBaseService.delete(knowledgeBaseId);
        }
    }

    @Test
    void shouldReportMetricsForEveryRetrievalMode() {
        StringBuilder report = new StringBuilder();

        for (int topK : TOP_KS) {
            Map<RetrievalMode, Metrics> metricsByMode = new EnumMap<>(RetrievalMode.class);
            for (RetrievalMode mode : RetrievalMode.values()) {
                metricsByMode.put(mode, evaluate(mode, topK));
            }

            printReport(report, topK, metricsByMode);

            // MRR 用最后一档 K（最大候选集）评估，此时不至于因为候选太少而失真
            if (topK == TOP_KS.get(TOP_KS.size() - 1)) {
                metricsByMode.forEach((mode, metrics) -> assertThat(metrics.reciprocalRank())
                        .as("%s 模式的 MRR 不应低于 %.2f", mode, MRR_FLOOR)
                        .isGreaterThanOrEqualTo(MRR_FLOOR));
            }
        }

        System.out.println(report);
    }

    private Metrics evaluate(RetrievalMode mode, int topK) {
        List<Double> recalls = new ArrayList<>();
        List<Double> reciprocalRanks = new ArrayList<>();
        int hits = 0;

        for (EvalCase evalCase : evalSet) {
            List<SearchResultResponse> results = chunkSearchService.search(
                    knowledgeBaseId, new SearchRequest(evalCase.question(), topK, mode));

            List<Integer> returned = results.stream().map(SearchResultResponse::chunkIndex).toList();
            Set<Integer> expected = new LinkedHashSet<>(evalCase.expectedChunkIndexes());

            Set<Integer> matched = new LinkedHashSet<>(returned);
            matched.retainAll(expected);
            recalls.add((double) matched.size() / expected.size());

            int firstHitRank = 0;
            for (int position = 0; position < returned.size(); position++) {
                if (expected.contains(returned.get(position))) {
                    firstHitRank = position + 1;
                    break;
                }
            }
            reciprocalRanks.add(firstHitRank == 0 ? 0.0 : 1.0 / firstHitRank);
            if (firstHitRank > 0) {
                hits++;
            }
        }

        return new Metrics(average(recalls), average(reciprocalRanks), (double) hits / evalSet.size());
    }

    private void printReport(StringBuilder report, int topK, Map<RetrievalMode, Metrics> metricsByMode) {
        report.append(String.format("%n===== 检索效果对比  K=%d（%d 个问题，语料 %d 个分块）=====%n",
                topK, evalSet.size(), documentChunkRepository.countByDocumentId(documentId)));
        report.append(String.format("%-10s %-12s %-10s %-10s%n", "模式", "Recall@" + topK, "MRR", "命中率"));
        metricsByMode.forEach((mode, metrics) -> report.append(String.format("%-10s %-12.4f %-10.4f %-10.4f%n",
                mode, metrics.recallAtK(), metrics.reciprocalRank(), metrics.hitRate())));
    }

    private List<EvalCase> loadEvalSet() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(EVAL_SET_RESOURCE)) {
            assertThat(in).as("评测集 %s 必须存在", EVAL_SET_RESOURCE).isNotNull();
            return objectMapper.readValue(in, new TypeReference<List<EvalCase>>() {
            });
        }
    }

    private static double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    record EvalCase(String question, List<Integer> expectedChunkIndexes, String note) {
    }

    record Metrics(double recallAtK, double reciprocalRank, double hitRate) {
    }
}
