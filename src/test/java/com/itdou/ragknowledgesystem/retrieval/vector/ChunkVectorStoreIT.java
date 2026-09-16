package com.itdou.ragknowledgesystem.retrieval.vector;

import com.itdou.ragknowledgesystem.document.entity.Document;
import com.itdou.ragknowledgesystem.document.entity.DocumentChunk;
import com.itdou.ragknowledgesystem.document.repository.ChunkSearchProjection;
import com.itdou.ragknowledgesystem.document.repository.DocumentChunkRepository;
import com.itdou.ragknowledgesystem.document.repository.DocumentRepository;
import com.itdou.ragknowledgesystem.knowledgebase.entity.KnowledgeBase;
import com.itdou.ragknowledgesystem.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 向量存储与检索的集成测试：真正写进 pgvector 再查出来。
 *
 * 只依赖本地 PostgreSQL，不需要 Embedding 服务，向量由测试自己合成。
 * 单独测这一层的原因：写入用的是原生 SQL（embedding 列没有 JPA 映射），
 * 检索用的是原生查询加接口投影，这两处的问题单元测试和 Mock 都发现不了。
 *
 * 需要真实数据库，用 RUN_EXTERNAL_TESTS=true 开启，默认跳过。
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "RUN_EXTERNAL_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ChunkVectorStoreIT {

    private static final int DIMENSION = 1024;
    private static final String MODEL = "BAAI/bge-m3";

    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    @Test
    @Transactional
    void shouldRankChunksByCosineSimilarity() {
        KnowledgeBase knowledgeBase = createKnowledgeBase("向量排序测试");
        Document document = createDocument(knowledgeBase, "vector-test.pdf");

        DocumentChunk firstHalf = createChunk(document, knowledgeBase, 0, "前半段内容", 'a', 1, 2);
        DocumentChunk secondHalf = createChunk(document, knowledgeBase, 1, "后半段内容", 'b', 3, 4);

        embed(firstHalf, literal(1.0f, 0.0f));
        embed(secondHalf, literal(0.0f, 1.0f));

        // 用与「第一块」几乎同向的向量查询，它必须排第一，并且带出页码和来源文件名
        List<ChunkSearchProjection> hits = documentChunkRepository.searchByEmbedding(
                knowledgeBase.getId(), literal(0.99f, 0.01f), 2);

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).getChunkId()).isEqualTo(firstHalf.getId());
        assertThat(hits.get(0).getContent()).isEqualTo("前半段内容");
        assertThat(hits.get(0).getPageNumberStart()).isEqualTo(1);
        assertThat(hits.get(0).getPageNumberEnd()).isEqualTo(2);
        assertThat(hits.get(0).getOriginalFileName()).isEqualTo("vector-test.pdf");
        assertThat(hits.get(0).getScore()).isGreaterThan(hits.get(1).getScore());

        // 换成与「第二块」同向的向量，顺序反过来，说明排序确实按余弦距离走
        List<ChunkSearchProjection> reversed = documentChunkRepository.searchByEmbedding(
                knowledgeBase.getId(), literal(0.01f, 0.99f), 2);

        assertThat(reversed.get(0).getChunkId()).isEqualTo(secondHalf.getId());
        assertThat(reversed.get(0).getPageNumberStart()).isEqualTo(3);
    }

    @Test
    @Transactional
    void shouldOnlyReturnChunksOfTheRequestedKnowledgeBase() {
        KnowledgeBase first = createKnowledgeBase("库A");
        KnowledgeBase second = createKnowledgeBase("库B");

        Document documentInFirst = createDocument(first, "a.pdf");
        embed(createChunk(documentInFirst, first, 0, "库A的内容", 'c', 1, 1), literal(1.0f, 0.0f));

        String query = literal(1.0f, 0.0f);

        // 先证明数据确实写进去了，否则「另一个库查不到」这个断言毫无意义
        List<ChunkSearchProjection> hitsInFirst = documentChunkRepository.searchByEmbedding(
                first.getId(), query, 10);
        assertThat(hitsInFirst).hasSize(1);
        assertThat(hitsInFirst.get(0).getContent()).isEqualTo("库A的内容");

        assertThat(documentChunkRepository.searchByEmbedding(second.getId(), query, 10)).isEmpty();
    }

    // 没做向量化的分块不能出现在检索结果里；同库里放一个已向量化的作为对照，证明查询本身是有效的
    @Test
    @Transactional
    void shouldSkipChunksWithoutEmbedding() {
        KnowledgeBase knowledgeBase = createKnowledgeBase("未向量化测试");
        Document document = createDocument(knowledgeBase, "mixed.pdf");

        embed(createChunk(document, knowledgeBase, 0, "已向量化", 'd', 1, 1), literal(1.0f, 0.0f));
        createChunk(document, knowledgeBase, 1, "未向量化", 'e', 2, 2);

        List<ChunkSearchProjection> hits = documentChunkRepository.searchByEmbedding(
                knowledgeBase.getId(), literal(1.0f, 0.0f), 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getContent()).isEqualTo("已向量化");
    }

    /*
     * 写入向量前必须显式 flush：@Modifying 的原生查询不会自动把积压的 INSERT 刷下去
     * （Hibernate 只对 JPQL 自动 flush，原生语句它不知道会影响哪些表）。
     * 不 flush 的话 UPDATE 会作用在还不存在的行上，等于什么都没写，
     * 后面断言「查不到」的测试就会因为数据压根没落库而假通过。
     */
    private void embed(DocumentChunk chunk, String embeddingLiteral) {
        documentChunkRepository.flush();
        documentChunkRepository.updateEmbedding(chunk.getId(), embeddingLiteral, MODEL);
    }

    private KnowledgeBase createKnowledgeBase(String name) {
        return knowledgeBaseRepository.save(KnowledgeBase.create(name, "集成测试临时数据，结束后回滚"));
    }

    private Document createDocument(KnowledgeBase knowledgeBase, String fileName) {
        return documentRepository.save(Document.pending(
                knowledgeBase.getId(),
                fileName,
                "integration-test/" + UUID.randomUUID() + ".pdf",
                "application/pdf",
                1024L,
                UUID.randomUUID().toString().replace("-", "")));
    }

    private DocumentChunk createChunk(Document document, KnowledgeBase knowledgeBase, int chunkIndex,
                                      String content, char hashChar, int pageStart, int pageEnd) {
        return documentChunkRepository.save(DocumentChunk.create(
                document.getId(),
                knowledgeBase.getId(),
                chunkIndex,
                content,
                String.valueOf(hashChar).repeat(64),
                pageStart,
                pageEnd,
                100));
    }

    /*
     * 构造前 512 维填 firstValue、后 512 维填 secondValue 的合成向量。
     * (1,0) 与 (0,1) 正交，余弦距离为 1，便于验证排序确实按相似度走。
     * pgvector 的余弦距离与向量长度无关，所以不需要归一化。
     */
    private static String literal(float firstValue, float secondValue) {
        float[] embedding = new float[DIMENSION];
        for (int index = 0; index < DIMENSION; index++) {
            embedding[index] = index < DIMENSION / 2 ? firstValue : secondValue;
        }
        return VectorLiteral.toLiteral(embedding, DIMENSION);
    }
}
