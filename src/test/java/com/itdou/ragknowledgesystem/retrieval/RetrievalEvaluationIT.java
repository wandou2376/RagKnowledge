package com.itdou.ragknowledgesystem.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itdou.ragknowledgesystem.document.dto.DocumentResponse;
import com.itdou.ragknowledgesystem.document.service.DocumentService;
import com.itdou.ragknowledgesystem.ingestion.service.DocumentIngestionService;
import com.itdou.ragknowledgesystem.knowledgebase.dto.CreateKnowledgeBaseRequest;
import com.itdou.ragknowledgesystem.knowledgebase.dto.KnowledgeBaseResponse;
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
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索效果评测。
 *
 * 为什么要有它：检索相关的每一项优化（融合、Rerank、查询改写）都属于「感觉应该更好」的改动，
 * 没有评测集就只能凭感觉调参。这个测试把「好多少」变成数字，并且按问题类型分开看，
 * 这样能看出每个召回通道各自擅长什么。
 *
 * 做法：走真实链路灌语料（上传 → 解析 → 向量化），再对评测集里每个问题用三种模式检索，
 * 计算命中率、Recall@K 和 MRR。评测集的期望答案是从真实分块内容里反查出来的，
 * 不是凭印象手写的，生成脚本见 tools/corpus/build_eval_set.py。
 *
 * 指标口径上有一条要如实说明：
 * 为了省调用次数，每个 (问题, 模式) 只在 topK=10 下检索一次，再在内存里截断出更小的 K。
 * 对 VECTOR 和 KEYWORD 这是精确等价的（排序本身是包含关系）；
 * 对 HYBRID 只是近似——融合会从两条通道各取更多候选，候选变多时融合结果可能有细微差别。
 * 三种模式用的是同一套口径，所以横向对比仍然是公平的。
 *
 * 需要真实数据库与 Embedding 额度（不调聊天模型），用 RUN_EXTERNAL_TESTS=true 开启，默认跳过。
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "RUN_EXTERNAL_TESTS", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RetrievalEvaluationIT {

    private static final List<Integer> TOP_KS = List.of(1, 2, 3, 5, 10);
    private static final int MAX_K = 10;
    private static final String CORPUS_DIR = "corpus";
    private static final String EVAL_SET_RESOURCE = "retrieval-eval-set.json";
    /** 展示模式 × 问题类型细节时用的 K */
    private static final int DETAIL_K = 3;
    /**
     * 各模式在 K=10 下 MRR 的下限，按实测基线留出余量。
     * 低于它说明检索明显退化了，应该去查原因，而不是调低这个数。
     */
    private static final Map<RetrievalMode, Double> MRR_FLOOR = Map.of(
            RetrievalMode.VECTOR, 0.55,
            RetrievalMode.KEYWORD, 0.75,
            RetrievalMode.HYBRID, 0.70);
    /** 报告同时落一份文件，方便回看（surefire 不会把 stdout 收进报告文件） */
    private static final Path REPORT_FILE = Path.of("target", "retrieval-eval-report.txt");

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
    private ObjectMapper objectMapper;

    private UUID knowledgeBaseId;
    /** documentId -> 原始文件名，用来把检索结果映射回评测集里的文档名 */
    private final Map<UUID, String> fileNameByDocumentId = new HashMap<>();
    private int chunkTotal;
    private List<EvalCase> evalSet;

    @BeforeAll
    void prepareCorpus() throws Exception {
        KnowledgeBaseResponse knowledgeBase = knowledgeBaseService.create(
                new CreateKnowledgeBaseRequest("检索评测临时库", "评测用完即删"));
        knowledgeBaseId = knowledgeBase.id();

        List<Path> corpusFiles = listCorpusFiles();
        chunkTotal = 0;
        for (Path file : corpusFiles) {
            DocumentResponse document;
            try (InputStream in = Files.newInputStream(file)) {
                document = documentService.upload(knowledgeBaseId, new MockMultipartFile(
                        "file", file.getFileName().toString(), "application/pdf", in));
            }
            int chunks = documentIngestionService.parse(document.id()).chunkCount();
            // 向量化是整个语料一次性建索引，这里逐文档做，和线上「上传后单独触发向量化」一致
            chunkEmbeddingService.embedDocument(document.id());

            fileNameByDocumentId.put(document.id(), file.getFileName().toString());
            chunkTotal += chunks;
        }

        evalSet = loadEvalSet();
        System.out.printf("%n语料就绪：%d 份文档，%d 个分块；评测集 %d 题%n",
                corpusFiles.size(), chunkTotal, evalSet.size());
    }
    @AfterAll
    void cleanUp() {
        if (knowledgeBaseId != null) {
            knowledgeBaseService.delete(knowledgeBaseId);
        }
    }

    @Test
    void shouldReportMetricsPerModeKAndQuestionType() throws Exception {
        // (问题, 模式) 只检索一次，结果按题缓存下来供各个 K 复用
        Map<RetrievalMode, List<List<String>>> rankingsByMode = new EnumMap<>(RetrievalMode.class);
        for (RetrievalMode mode : RetrievalMode.values()) {
            List<List<String>> rankings = new ArrayList<>(evalSet.size());
            for (EvalCase evalCase : evalSet) {
                rankings.add(searchKeys(mode, evalCase.question()));
            }
            rankingsByMode.put(mode, rankings);
        }

        StringBuilder report = new StringBuilder();
        report.append(String.format("%n检索效果评测（语料 %d 份文档 / %d 个分块，评测集 %d 题）%n",
                fileNameByDocumentId.size(), chunkTotal, evalSet.size()));

        Map<RetrievalMode, Map<Integer, Metrics>> overall = new EnumMap<>(RetrievalMode.class);
        for (RetrievalMode mode : RetrievalMode.values()) {
            // 这里不能用 EnumMap：键是 K 的整数值，EnumMap 只接受枚举键
            overall.put(mode, new LinkedHashMap<>());
            for (int topK : TOP_KS) {
                overall.get(mode).put(topK, summarize(rankingsByMode.get(mode), topK, null));
            }
        }
        printOverallTable(report, overall);

        Map<RetrievalMode, Map<String, Metrics>> byType = new EnumMap<>(RetrievalMode.class);
        for (RetrievalMode mode : RetrievalMode.values()) {
            byType.put(mode, new LinkedHashMap<>());
            for (String type : questionTypes()) {
                byType.get(mode).put(type, summarize(rankingsByMode.get(mode), DETAIL_K, type));
            }
        }
        printTypeTable(report, byType);

        printMisses(report, rankingsByMode);

        System.out.println(report);
        Files.writeString(REPORT_FILE, report.toString(), StandardCharsets.UTF_8);
        System.out.println("报告已写入 " + REPORT_FILE.toAbsolutePath());

        MRR_FLOOR.forEach((mode, floor) -> {
            double actual = overall.get(mode).get(MAX_K).reciprocalRank();
            assertThat(actual)
                    .as("%s 模式在 K=%d 下的 MRR 不应低于 %.2f", mode, MAX_K, floor)
                    .isGreaterThanOrEqualTo(floor);
        });

        /*
         * 设计不变量：模糊问法（问句里不含文档原词）应当靠语义召回，而不是靠字面命中。
         * 这是当初把它单独分一类的理由，如果这条不再成立，说明要么语料变了、要么召回坏了。
         */
        double paraphraseHybrid = byType.get(RetrievalMode.HYBRID).get("PARAPHRASE").reciprocalRank();
        double paraphraseKeyword = byType.get(RetrievalMode.KEYWORD).get("PARAPHRASE").reciprocalRank();
        assertThat(paraphraseHybrid)
                .as("模糊问法上融合应优于纯关键词（这是设置 PARAPHRASE 这一类的依据）")
                .isGreaterThan(paraphraseKeyword);
    }

    /** 检索一次，返回按相关性排序的 "文件名#块序号" 列表。 */
    private List<String> searchKeys(RetrievalMode mode, String question) {
        List<SearchResultResponse> results = chunkSearchService.search(
                knowledgeBaseId, new SearchRequest(question, MAX_K, mode));
        return results.stream()
                .map(hit -> key(fileNameByDocumentId.get(hit.documentId()), hit.chunkIndex()))
                .toList();
    }

    private Metrics summarize(List<List<String>> rankings, int topK, String typeFilter) {
        List<Double> hits = new ArrayList<>();
        List<Double> recalls = new ArrayList<>();
        List<Double> reciprocalRanks = new ArrayList<>();

        for (int index = 0; index < evalSet.size(); index++) {
            EvalCase evalCase = evalSet.get(index);
            if (typeFilter != null && !evalCase.type().equals(typeFilter)) {
                continue;
            }
            Set<String> expected = expectedKeys(evalCase);
            List<String> returned = rankings.get(index).stream().limit(topK).toList();

            Set<String> matched = new LinkedHashSet<>(returned);
            matched.retainAll(expected);

            hits.add(matched.isEmpty() ? 0.0 : 1.0);
            recalls.add((double) matched.size() / expected.size());

            int firstHit = 0;
            for (int position = 0; position < returned.size(); position++) {
                if (expected.contains(returned.get(position))) {
                    firstHit = position + 1;
                    break;
                }
            }
            reciprocalRanks.add(firstHit == 0 ? 0.0 : 1.0 / firstHit);
        }
        return new Metrics(average(hits), average(recalls), average(reciprocalRanks));
    }

    private Set<String> expectedKeys(EvalCase evalCase) {
        Set<String> keys = new LinkedHashSet<>();
        evalCase.expected().forEach(ref -> keys.add(key(ref.document(), ref.chunkIndex())));
        return keys;
    }

    private static String key(String documentName, int chunkIndex) {
        return documentName + "#" + chunkIndex;
    }

    private void printOverallTable(StringBuilder report, Map<RetrievalMode, Map<Integer, Metrics>> overall) {
        report.append(String.format("%n【整体指标】%n"));
        report.append(String.format("%-9s", "模式"));
        for (int topK : TOP_KS) {
            report.append(String.format("  %-22s", "K=" + topK));
        }
        report.append(String.format("%n%-9s", ""));
        for (int ignored : TOP_KS) {
            report.append(String.format("  %-22s", "命中率 / Recall / MRR"));
        }
        report.append('\n');
        for (RetrievalMode mode : RetrievalMode.values()) {
            report.append(String.format("%-9s", mode));
            for (int topK : TOP_KS) {
                Metrics metrics = overall.get(mode).get(topK);
                report.append(String.format("  %-22s", String.format("%.3f / %.3f / %.3f",
                        metrics.hitRate(), metrics.recallAtK(), metrics.reciprocalRank())));
            }
            report.append('\n');
        }
    }

    private void printTypeTable(StringBuilder report, Map<RetrievalMode, Map<String, Metrics>> byType) {
        report.append(String.format("%n【按问题类型拆分】K=%d，看 MRR%n", DETAIL_K));
        report.append(String.format("%-9s", "模式"));
        for (String type : questionTypes()) {
            report.append(String.format("  %-11s", type));
        }
        report.append('\n');
        for (RetrievalMode mode : RetrievalMode.values()) {
            report.append(String.format("%-9s", mode));
            for (String type : questionTypes()) {
                report.append(String.format("  %-11.3f", byType.get(mode).get(type).reciprocalRank()));
            }
            report.append('\n');
        }
    }

    private void printMisses(StringBuilder report, Map<RetrievalMode, List<List<String>>> rankingsByMode) {
        report.append(String.format("%n【K=%d 下一条期望分块都没召回的题目】%n", MAX_K));
        for (RetrievalMode mode : RetrievalMode.values()) {
            List<String> misses = new ArrayList<>();
            for (int index = 0; index < evalSet.size(); index++) {
                EvalCase evalCase = evalSet.get(index);
                Set<String> returned = new LinkedHashSet<>(rankingsByMode.get(mode).get(index));
                returned.retainAll(expectedKeys(evalCase));
                if (returned.isEmpty()) {
                    misses.add(evalCase.type() + " " + evalCase.question());
                }
            }
            report.append(String.format("  %-9s %d 题%n", mode, misses.size()));
            misses.forEach(miss -> report.append("      ").append(miss).append('\n'));
        }
    }

    private List<String> questionTypes() {
        return evalSet.stream().map(EvalCase::type).distinct().toList();
    }

    private List<Path> listCorpusFiles() throws Exception {
        URL url = getClass().getClassLoader().getResource(CORPUS_DIR);
        assertThat(url).as("语料目录 %s 必须存在", CORPUS_DIR).isNotNull();
        try (var files = Files.list(Path.of(url.toURI()))) {
            List<Path> pdfs = files
                    .filter(path -> path.getFileName().toString().endsWith(".pdf"))
                    .sorted()
                    .toList();
            assertThat(pdfs).as("语料目录里必须有 PDF").isNotEmpty();
            return pdfs;
        }
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

    record ChunkRef(String document, int chunkIndex) {
    }

    record EvalCase(String type, String question, List<ChunkRef> expected, String note) {
    }

    record Metrics(double hitRate, double recallAtK, double reciprocalRank) {
    }
}
