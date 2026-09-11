package com.itdou.ragknowledgesystem.document.service;

import com.itdou.ragknowledgesystem.document.dto.DocumentResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface DocumentService {

    DocumentResponse upload(UUID knowledgeBaseId, MultipartFile file);

    List<DocumentResponse> listByKnowledgeBaseId(UUID knowledgeBaseId);

    DocumentResponse getById(UUID documentId);

    void delete(UUID documentId);
}
