package com.itdou.ragknowledgesystem.document.repository;

import com.itdou.ragknowledgesystem.document.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findByKnowledgeBaseIdOrderByCreatedAtDesc(UUID knowledgeBaseId);

    boolean existsByKnowledgeBaseIdAndFileHash(UUID knowledgeBaseId, String fileHash);

    @Query("select document.storageKey from Document document where document.knowledgeBaseId = :knowledgeBaseId")
    List<String> findStorageKeysByKnowledgeBaseId(@Param("knowledgeBaseId") UUID knowledgeBaseId);

    /*
     * 用批量 DELETE 语句而不是派生删除方法：批量语句立即执行，
     * 不需要先把所有文档实体加载进持久化上下文。
     */
    @Modifying
    @Query("delete from Document document where document.knowledgeBaseId = :knowledgeBaseId")
    void deleteAllByKnowledgeBaseId(@Param("knowledgeBaseId") UUID knowledgeBaseId);
}
