package com.itdou.ragknowledgesystem.document.controller;

import com.itdou.ragknowledgesystem.document.dto.ChunkEmbeddingResponse;
import com.itdou.ragknowledgesystem.document.dto.DocumentChunkResponse;
import com.itdou.ragknowledgesystem.document.dto.DocumentParseResponse;
import com.itdou.ragknowledgesystem.document.dto.DocumentResponse;
import com.itdou.ragknowledgesystem.document.entity.DocumentParseStatus;
import com.itdou.ragknowledgesystem.document.exception.DocumentNotFoundException;
import com.itdou.ragknowledgesystem.document.exception.DocumentParsingException;
import com.itdou.ragknowledgesystem.document.exception.DuplicateDocumentException;
import com.itdou.ragknowledgesystem.document.exception.InvalidDocumentException;
import com.itdou.ragknowledgesystem.document.service.DocumentService;
import com.itdou.ragknowledgesystem.ingestion.service.DocumentIngestionService;
import com.itdou.ragknowledgesystem.knowledgebase.exception.KnowledgeBaseNotFoundException;
import com.itdou.ragknowledgesystem.retrieval.exception.EmbeddingFailedException;
import com.itdou.ragknowledgesystem.retrieval.service.ChunkEmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文档与解析接口的响应契约测试，同样只加载 web 层，不依赖数据库。
 */
@WebMvcTest(
        controllers = DocumentController.class,
        properties = {"RAG_POSTGRES_PASSWORD=test-password", "DEEPSEEK_API_KEY=test-api-key"}
)
class DocumentControllerTest {

    private static final UUID KNOWLEDGE_BASE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DOCUMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentService documentService;

    @MockitoBean
    private DocumentIngestionService documentIngestionService;

    @MockitoBean
    private ChunkEmbeddingService chunkEmbeddingService;

    @Test
    void uploadShouldReturn201WithLocationAndWrappedBody() throws Exception {
        given(documentService.upload(eq(KNOWLEDGE_BASE_ID), any())).willReturn(documentResponse());

        mockMvc.perform(multipart("/api/knowledge-bases/{id}/documents", KNOWLEDGE_BASE_ID)
                        .file(pdfFile()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/documents/" + DOCUMENT_ID)))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.id").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$.data.originalFileName").value("guide.pdf"))
                .andExpect(jsonPath("$.data.parseStatus").value("PENDING"));
    }

    @Test
    void uploadShouldReturnInvalidDocumentEnvelopeForNonPdf() throws Exception {
        given(documentService.upload(eq(KNOWLEDGE_BASE_ID), any()))
                .willThrow(new InvalidDocumentException("只支持 PDF 格式的文件"));

        mockMvc.perform(multipart("/api/knowledge-bases/{id}/documents", KNOWLEDGE_BASE_ID)
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT"))
                .andExpect(jsonPath("$.message").value("只支持 PDF 格式的文件"));
    }

    @Test
    void uploadShouldReturnConflictEnvelopeForDuplicateFile() throws Exception {
        given(documentService.upload(eq(KNOWLEDGE_BASE_ID), any())).willThrow(new DuplicateDocumentException());

        mockMvc.perform(multipart("/api/knowledge-bases/{id}/documents", KNOWLEDGE_BASE_ID)
                        .file(pdfFile()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_DOCUMENT"));
    }

    @Test
    void uploadShouldReturnNotFoundEnvelopeWhenKnowledgeBaseMissing() throws Exception {
        given(documentService.upload(eq(KNOWLEDGE_BASE_ID), any()))
                .willThrow(new KnowledgeBaseNotFoundException(KNOWLEDGE_BASE_ID));

        mockMvc.perform(multipart("/api/knowledge-bases/{id}/documents", KNOWLEDGE_BASE_ID)
                        .file(pdfFile()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NOT_FOUND"));
    }

    /*
     * 请求里没有 file 部件时 Spring 会抛 MissingServletRequestPartException，
     * 它实现了 ErrorResponse，兜底 handler 必须保留它的 400 而不是压成 500。
     */
    @Test
    void uploadWithoutFilePartShouldReturnBadRequestEnvelope() throws Exception {
        mockMvc.perform(multipart("/api/knowledge-bases/{id}/documents", KNOWLEDGE_BASE_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));
    }

    @Test
    void listByKnowledgeBaseShouldWrapItemsInEnvelope() throws Exception {
        given(documentService.listByKnowledgeBaseId(KNOWLEDGE_BASE_ID)).willReturn(List.of(documentResponse()));

        mockMvc.perform(get("/api/knowledge-bases/{id}/documents", KNOWLEDGE_BASE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(DOCUMENT_ID.toString()));
    }

    @Test
    void getByIdShouldWrapInEnvelope() throws Exception {
        given(documentService.getById(DOCUMENT_ID)).willReturn(documentResponse());

        mockMvc.perform(get("/api/documents/{id}", DOCUMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.id").value(DOCUMENT_ID.toString()));
    }

    @Test
    void getByIdShouldReturnNotFoundEnvelopeWhenMissing() throws Exception {
        given(documentService.getById(DOCUMENT_ID)).willThrow(new DocumentNotFoundException(DOCUMENT_ID));

        mockMvc.perform(get("/api/documents/{id}", DOCUMENT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"))
                .andExpect(content().string(containsString("\"data\":null")));
    }

    @Test
    void deleteShouldReturn204WithoutBody() throws Exception {
        mockMvc.perform(delete("/api/documents/{id}", DOCUMENT_ID))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(documentService).delete(DOCUMENT_ID);
    }

    @Test
    void parseShouldWrapInEnvelope() throws Exception {
        given(documentIngestionService.parse(DOCUMENT_ID)).willReturn(parseResponse());

        mockMvc.perform(post("/api/documents/{id}/parse", DOCUMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.documentId").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$.data.parseStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.pageCount").value(6))
                .andExpect(jsonPath("$.data.chunkCount").value(5))
                .andExpect(jsonPath("$.data.duplicateChunkCount").value(0));
    }

    // 扫描版 PDF 提取不到文本，属于请求内容问题，用 422 而不是 500
    @Test
    void parseShouldReturnUnprocessableEntityEnvelopeWhenPdfHasNoText() throws Exception {
        given(documentIngestionService.parse(DOCUMENT_ID))
                .willThrow(new DocumentParsingException("未从 PDF 中提取到任何文本，可能是扫描版或图片型 PDF"));

        mockMvc.perform(post("/api/documents/{id}/parse", DOCUMENT_ID))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DOCUMENT_PARSING_FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("未从 PDF 中提取到任何文本")));
    }

    @Test
    void listChunksShouldWrapItemsInEnvelope() throws Exception {
        given(documentIngestionService.listChunks(DOCUMENT_ID)).willReturn(List.of(chunkResponse()));

        mockMvc.perform(get("/api/documents/{id}/chunks", DOCUMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].chunkIndex").value(0))
                .andExpect(jsonPath("$.data[0].pageNumberStart").value(1))
                .andExpect(jsonPath("$.data[0].pageNumberEnd").value(3))
                .andExpect(jsonPath("$.data[0].tokenCount").value(603));
    }

    @Test
    void listChunksShouldReturnEmptyArrayInsideEnvelope() throws Exception {
        given(documentIngestionService.listChunks(DOCUMENT_ID)).willReturn(List.of());

        mockMvc.perform(get("/api/documents/{id}/chunks", DOCUMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void listChunksShouldReturnNotFoundEnvelopeWhenDocumentMissing() throws Exception {
        given(documentIngestionService.listChunks(DOCUMENT_ID))
                .willThrow(new DocumentNotFoundException(DOCUMENT_ID));

        mockMvc.perform(get("/api/documents/{id}/chunks", DOCUMENT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void embedShouldWrapInEnvelope() throws Exception {
        given(chunkEmbeddingService.embedDocument(DOCUMENT_ID)).willReturn(
                new ChunkEmbeddingResponse(DOCUMENT_ID, "BAAI/bge-m3", 1024, 5, 0, Instant.now()));

        mockMvc.perform(post("/api/documents/{id}/embed", DOCUMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("成功"))
                .andExpect(jsonPath("$.data.documentId").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$.data.embeddingModel").value("BAAI/bge-m3"))
                .andExpect(jsonPath("$.data.dimension").value(1024))
                .andExpect(jsonPath("$.data.embeddedCount").value(5))
                .andExpect(jsonPath("$.data.alreadyEmbeddedCount").value(0));
    }

    @Test
    void embedShouldReturnNotFoundEnvelopeWhenDocumentMissing() throws Exception {
        given(chunkEmbeddingService.embedDocument(DOCUMENT_ID))
                .willThrow(new DocumentNotFoundException(DOCUMENT_ID));

        mockMvc.perform(post("/api/documents/{id}/embed", DOCUMENT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    // Embedding 是外部依赖，未配置或不可用时用 503，与普通服务端故障区分开
    @Test
    void embedShouldReturnServiceUnavailableWhenEmbeddingIsNotConfigured() throws Exception {
        given(chunkEmbeddingService.embedDocument(DOCUMENT_ID))
                .willThrow(new EmbeddingFailedException("未配置 Embedding 服务，请先设置环境变量 SILICONFLOW_API_KEY"));

        mockMvc.perform(post("/api/documents/{id}/embed", DOCUMENT_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EMBEDDING_FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("SILICONFLOW_API_KEY")));
    }

    private static MockMultipartFile pdfFile() {        return new MockMultipartFile("file", "guide.pdf", "application/pdf",
                "%PDF-1.7\nexample".getBytes(StandardCharsets.UTF_8));
    }

    private static DocumentResponse documentResponse() {
        Instant now = Instant.now();
        return new DocumentResponse(DOCUMENT_ID, KNOWLEDGE_BASE_ID, "guide.pdf", "application/pdf",
                2048L, DocumentParseStatus.PENDING, now, now);
    }

    private static DocumentParseResponse parseResponse() {
        return new DocumentParseResponse(DOCUMENT_ID, DocumentParseStatus.COMPLETED, 6, 0, 5, 0, Instant.now());
    }

    private static DocumentChunkResponse chunkResponse() {
        return new DocumentChunkResponse(UUID.randomUUID(), DOCUMENT_ID, 0, "第一块内容",
                "a".repeat(64), 1, 3, 603, Instant.now());
    }
}
