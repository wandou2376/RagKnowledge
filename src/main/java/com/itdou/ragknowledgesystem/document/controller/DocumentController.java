package com.itdou.ragknowledgesystem.document.controller;

import com.itdou.ragknowledgesystem.common.response.Result;
import com.itdou.ragknowledgesystem.document.dto.DocumentChunkResponse;
import com.itdou.ragknowledgesystem.document.dto.DocumentParseResponse;
import com.itdou.ragknowledgesystem.document.dto.DocumentResponse;
import com.itdou.ragknowledgesystem.document.service.DocumentService;
import com.itdou.ragknowledgesystem.ingestion.service.DocumentIngestionService;
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
    private final DocumentIngestionService documentIngestionService;

    public DocumentController(
            DocumentService documentService,
            DocumentIngestionService documentIngestionService
    ) {
        this.documentService = documentService;
        this.documentIngestionService = documentIngestionService;
    }

    // 上传成功返回 201，Location 头指向文档元数据，body 同样是 Result 信封
    @PostMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public ResponseEntity<Result<DocumentResponse>> upload(
            @PathVariable UUID knowledgeBaseId,
            @RequestParam("file") MultipartFile file
    ) {
        DocumentResponse response = documentService.upload(knowledgeBaseId, file);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/documents/{id}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(Result.success(response));
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public Result<List<DocumentResponse>> listByKnowledgeBase(@PathVariable UUID knowledgeBaseId) {
        return Result.success(documentService.listByKnowledgeBaseId(knowledgeBaseId));
    }

    @GetMapping("/documents/{documentId}")
    public Result<DocumentResponse> getById(@PathVariable UUID documentId) {
        return Result.success(documentService.getById(documentId));
    }

    // 删除保持 204 No Content：与知识库删除接口一致，是不返回信封的例外
    @DeleteMapping("/documents/{documentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID documentId) {
        documentService.delete(documentId);
        return ResponseEntity.noContent().build();
    }

    /*
     * 解析文档：提取文本、清洗、分块并写入 document_chunk。
     * 阶段 3 先做成同步接口，异步任务和失败重试放到阶段 8 再补。
     */
    @PostMapping("/documents/{documentId}/parse")
    public Result<DocumentParseResponse> parse(@PathVariable UUID documentId) {
        return Result.success(documentIngestionService.parse(documentId));
    }

    @GetMapping("/documents/{documentId}/chunks")
    public Result<List<DocumentChunkResponse>> listChunks(@PathVariable UUID documentId) {
        return Result.success(documentIngestionService.listChunks(documentId));
    }
}
