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
}
