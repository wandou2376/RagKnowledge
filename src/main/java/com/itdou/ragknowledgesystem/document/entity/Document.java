package com.itdou.ragknowledgesystem.document.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document", schema = "rag")
@Getter
public class Document {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "knowledge_base_id", nullable = false)
    private UUID knowledgeBaseId;

    @Column(name = "original_file_name", nullable = false, length = 255)
    private String originalFileName;

    @Column(name = "storage_key", nullable = false, length = 500, unique = true)
    private String storageKey;

    @Column(name = "media_type", nullable = false, length = 100)
    private String mediaType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "file_hash", nullable = false, length = 64)
    private String fileHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", nullable = false, length = 20)
    private DocumentParseStatus parseStatus;

    @Column(name = "parsed_at")
    private Instant parsedAt;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Document() {
    }

    // error_message 列是 VARCHAR(255)，超出长度直接写库会被数据库拒绝，所以统一在这里截断
    private static final int ERROR_MESSAGE_MAX_LENGTH = 255;

    public static Document pending(
            UUID knowledgeBaseId,
            String originalFileName,
            String storageKey,
            String mediaType,
            long fileSize,
            String fileHash
    ) {
        Document document = new Document();
        document.knowledgeBaseId = knowledgeBaseId;
        document.originalFileName = originalFileName;
        document.storageKey = storageKey;
        document.mediaType = mediaType;
        document.fileSize = fileSize;
        document.fileHash = fileHash;
        document.parseStatus = DocumentParseStatus.PENDING;
        return document;
    }

    // 进入解析流程，同时清掉上一次解析留下的结果，重新解析才是幂等的
    public void markParsing() {
        this.parseStatus = DocumentParseStatus.PARSING;
        this.parsedAt = null;
        this.errorMessage = null;
    }

    public void markCompleted(Instant parsedAt) {
        this.parseStatus = DocumentParseStatus.COMPLETED;
        this.parsedAt = parsedAt;
        this.errorMessage = null;
    }

    public void markFailed(String errorMessage) {
        this.parseStatus = DocumentParseStatus.FAILED;
        this.parsedAt = null;
        this.errorMessage = truncate(errorMessage);
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= ERROR_MESSAGE_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, ERROR_MESSAGE_MAX_LENGTH);
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
