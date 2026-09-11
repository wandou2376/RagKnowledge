package com.itdou.ragknowledgesystem.knowledgebase.repository;

import com.itdou.ragknowledgesystem.knowledgebase.entity.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, UUID> {

}
