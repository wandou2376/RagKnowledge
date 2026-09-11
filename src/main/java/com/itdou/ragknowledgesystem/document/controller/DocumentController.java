package com.itdou.ragknowledgesystem.document.controller;

import com.itdou.ragknowledgesystem.document.dto.DocumentResponse;
import com.itdou.ragknowledgesystem.document.service.DocumentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public ResponseEntity<DocumentResponse> upload(
            @PathVariable UUID knowledgeBaseId,
            @RequestParam("file") MultipartFile file
    ) {
        DocumentResponse response = documentService.upload(knowledgeBaseId, file);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/documents/{id}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public List<DocumentResponse> listByKnowledgeBase(@PathVariable UUID knowledgeBaseId) {
        return documentService.listByKnowledgeBaseId(knowledgeBaseId);
    }

    @GetMapping("/documents/{documentId}")
    public DocumentResponse getById(@PathVariable UUID documentId) {
        return documentService.getById(documentId);
    }

    @DeleteMapping("/documents/{documentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID documentId) {
        documentService.delete(documentId);
        return ResponseEntity.noContent().build();
    }
}
