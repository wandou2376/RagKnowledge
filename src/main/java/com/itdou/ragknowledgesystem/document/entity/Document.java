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
