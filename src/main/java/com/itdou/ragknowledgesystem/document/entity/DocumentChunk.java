package com.itdou.ragknowledgesystem.document.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * 文档分块，是后续向量化和检索的最小单位。
 *
 * 注意：表里的 metadata 列（JSONB）在这里没有映射。它带有 NOT NULL DEFAULT '{}' 默认值，
 * 本阶段需要的元数据（文档、知识库、页码、token 数、内容哈希）都已经是独立字段，
 * 暂时不引入 JSONB 映射，等结构化分块需要存标题层级时再补。
 */
@Entity
@Table(name = "document_chunk", schema = "rag")
@Getter
public class DocumentChunk {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "knowledge_base_id", nullable = false)
    private UUID knowledgeBaseId;

    // 同一文档内从 0 开始递增，与 uk_document_chunk_index 唯一约束对应
    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    // 数据库里是 TEXT，columnDefinition 必须和它一致，否则 Hibernate 的 ddl-auto=validate 会认为列类型不匹配
    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "page_number_start")
    private Integer pageNumberStart;

    @Column(name = "page_number_end")
    private Integer pageNumberEnd;

    // 阶段 3 还没有按标题层级切分，保留该列以便后续结构化分块使用
    @Column(name = "section_title", length = 500)
    private String sectionTitle;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    /*
     * embedding 列（pgvector 的 vector(1024)）刻意不做 JPA 映射：
     * Hibernate 没有内置的 vector 类型，硬映射要额外引入 pgvector 的 JDBC 类型。
     * 向量的写入和相似度查询都走原生 SQL，见 DocumentChunkRepository。
     * 下面两列与向量在同一条 SQL 里更新，在 Java 侧只读不写。
     */
    @Column(name = "embedding_model", length = 100)
    private String embeddingModel;

    @Column(name = "embedded_at")
    private Instant embeddedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DocumentChunk() {
    }

    public static DocumentChunk create(
            UUID documentId,
            UUID knowledgeBaseId,
            int chunkIndex,
            String content,
            String contentHash,
            Integer pageNumberStart,
            Integer pageNumberEnd,
            int tokenCount
    ) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.documentId = documentId;
        chunk.knowledgeBaseId = knowledgeBaseId;
        chunk.chunkIndex = chunkIndex;
        chunk.content = content;
        chunk.contentHash = contentHash;
        chunk.pageNumberStart = pageNumberStart;
        chunk.pageNumberEnd = pageNumberEnd;
        chunk.tokenCount = tokenCount;
        return chunk;
    }

    @PrePersist
    void initializeTimestamps() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }
    @PreUpdate
    void updateTimestamp() {
        updatedAt = Instant.now();
    }
}
