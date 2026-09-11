package com.itdou.ragknowledgesystem.document.repository;

import com.itdou.ragknowledgesystem.document.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findByKnowledgeBaseIdOrderByCreatedAtDesc(UUID knowledgeBaseId);

    boolean existsByKnowledgeBaseIdAndFileHash(UUID knowledgeBaseId, String fileHash);
}
