package com.itdou.ragknowledgesystem.document.repository;

import com.itdou.ragknowledgesystem.document.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    List<DocumentChunk> findByDocumentIdOrderByChunkIndexAsc(UUID documentId);

    /*
     * 用批量 DELETE 语句，而不是 deleteByDocumentId 这种派生方法。
     * Hibernate 在同一个事务里固定按 insert -> delete 的顺序刷写，
     * 先删旧分块再插新分块会撞上 uk_document_chunk_index，
     * 批量语句会立即执行，删除先落到数据库。
     */
    @Modifying
    @Query("delete from DocumentChunk chunk where chunk.documentId = :documentId")
    void deleteAllByDocumentId(@Param("documentId") UUID documentId);

    // 跨文档去重用：uk_knowledge_base_chunk_hash 保证同一知识库内分块内容唯一
    @Query("select chunk.contentHash from DocumentChunk chunk where chunk.knowledgeBaseId = :knowledgeBaseId")
    Set<String> findContentHashesByKnowledgeBaseId(@Param("knowledgeBaseId") UUID knowledgeBaseId);

    /*
     * 待向量化的分块：还没有向量，或者向量的产生模型和当前配置的不一致。
     * 后者用于换 Embedding 模型后的重算——维度与语义空间都变了，必须整体重做。
     */
    @Query("""
            select chunk from DocumentChunk chunk
            where chunk.documentId = :documentId
              and (chunk.embeddedAt is null or chunk.embeddingModel <> :embeddingModel)
            order by chunk.chunkIndex asc
            """)
    List<DocumentChunk> findChunksNeedingEmbedding(
            @Param("documentId") UUID documentId,
            @Param("embeddingModel") String embeddingModel);

    /*
     * 写入向量和向量化元数据。embedding 列没有 JPA 映射（原因见 DocumentChunk 的注释），只能走原生 SQL。
     * 三列在一条语句里写完，避免出现「写了向量但 embedded_at 为空」导致下次被重复向量化。
     * :embedding 传的是 pgvector 字面量，形如 [0.1,0.2,...]，所以必须显式 CAST 成 vector。
     */
    @Modifying
    @Query(value = """
            update rag.document_chunk
               set embedding = CAST(:embedding AS vector),
                   embedding_model = :embeddingModel,
                   embedded_at = now()
             where id = :id
            """, nativeQuery = true)
    void updateEmbedding(
            @Param("id") UUID id,
            @Param("embedding") String embedding,
            @Param("embeddingModel") String embeddingModel);

    long countByDocumentId(UUID documentId);

    /*
     * 余弦相似度检索。pgvector 的 <=> 返回余弦距离，1 - 距离就是相似度。
     * ORDER BY 必须也用 <=> 才能命中 HNSW 索引，换成别的表达式会退化成全表扫描。
     */
    @Query(value = """
            SELECT c.id                                             AS "chunkId",
                   c.document_id                                    AS "documentId",
                   c.chunk_index                                    AS "chunkIndex",
                   c.content                                        AS "content",
                   c.page_number_start                              AS "pageNumberStart",
                   c.page_number_end                                AS "pageNumberEnd",
                   c.token_count                                    AS "tokenCount",
                   d.original_file_name                             AS "originalFileName",
                   1 - (c.embedding <=> CAST(:embedding AS vector)) AS "score"
            FROM rag.document_chunk c
            JOIN rag.document d ON d.id = c.document_id
            WHERE c.knowledge_base_id = :knowledgeBaseId
              AND c.embedding IS NOT NULL
            ORDER BY c.embedding <=> CAST(:embedding AS vector)
            LIMIT :topK
            """, nativeQuery = true)
    List<ChunkSearchProjection> searchByEmbedding(
            @Param("knowledgeBaseId") UUID knowledgeBaseId,
            @Param("embedding") String embedding,
            @Param("topK") int topK);

    /*
     * 关键词召回。用 ILIKE 做包含匹配而不是 PostgreSQL 全文检索：
     * 默认分词器不认识中文，实测整句中文只会被切成一个 token，搜词根本匹配不上，
     * 镜像里也没有 zhparser 这类中文分词扩展。三元组索引（gin_trgm_ops）能加速 ILIKE '%词%'。
     *
     * 排序按「命中词元的长度之和」——长度是「专有性」的粗略代理：
     * 命中「智能系统学报」显然比命中「中文」更有说服力，而后者几乎每个分块都有。
     * 不这么做的话，像「中文」这种通用词会把专有词的分辨力稀释掉。
     *
     * :terms 是 | 拼接的词元串，由 KeywordTerms 抽取，词元里不会含 | 或 LIKE 通配符。
     */
    @Query(value = """
            SELECT c.id                  AS "chunkId",
                   c.document_id         AS "documentId",
                   c.chunk_index         AS "chunkIndex",
                   c.content             AS "content",
                   c.page_number_start   AS "pageNumberStart",
                   c.page_number_end     AS "pageNumberEnd",
                   c.token_count         AS "tokenCount",
                   d.original_file_name  AS "originalFileName",
                   (SELECT coalesce(sum(length(term)), 0)
                      FROM unnest(string_to_array(:terms, '|')) AS term
                     WHERE c.content ILIKE '%' || term || '%') AS "score"
            FROM rag.document_chunk c
            JOIN rag.document d ON d.id = c.document_id
            WHERE c.knowledge_base_id = :knowledgeBaseId
              AND EXISTS (SELECT 1
                            FROM unnest(string_to_array(:terms, '|')) AS term
                           WHERE c.content ILIKE '%' || term || '%')
            ORDER BY "score" DESC, c.chunk_index ASC
            LIMIT :topK
            """, nativeQuery = true)
    List<ChunkSearchProjection> searchByKeywords(
            @Param("knowledgeBaseId") UUID knowledgeBaseId,
            @Param("terms") String terms,
            @Param("topK") int topK);
}
