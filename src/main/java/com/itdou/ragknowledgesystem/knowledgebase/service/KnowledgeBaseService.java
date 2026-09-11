package com.itdou.ragknowledgesystem.knowledgebase.service;

import com.itdou.ragknowledgesystem.knowledgebase.dto.CreateKnowledgeBaseRequest;
import com.itdou.ragknowledgesystem.knowledgebase.dto.KnowledgeBaseResponse;
import com.itdou.ragknowledgesystem.knowledgebase.dto.UpdateKnowledgeBaseRequest;

import java.util.List;
import java.util.UUID;

public interface KnowledgeBaseService {

    KnowledgeBaseResponse create(CreateKnowledgeBaseRequest request);

    List<KnowledgeBaseResponse> list();

    KnowledgeBaseResponse getById(UUID id);

    KnowledgeBaseResponse update(UUID id, UpdateKnowledgeBaseRequest request);

    void delete(UUID id);
}
