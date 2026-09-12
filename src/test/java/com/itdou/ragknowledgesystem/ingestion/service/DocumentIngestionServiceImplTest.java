package com.itdou.ragknowledgesystem.ingestion.service;

import com.itdou.ragknowledgesystem.document.dto.DocumentParseResponse;
import com.itdou.ragknowledgesystem.document.entity.Document;
import com.itdou.ragknowledgesystem.document.entity.DocumentChunk;
import com.itdou.ragknowledgesystem.document.entity.DocumentParseStatus;
import com.itdou.ragknowledgesystem.document.exception.DocumentNotFoundException;
import com.itdou.ragknowledgesystem.document.exception.DocumentParsingException;
import com.itdou.ragknowledgesystem.document.repository.DocumentChunkRepository;
import com.itdou.ragknowledgesystem.document.repository.DocumentRepository;
import com.itdou.ragknowledgesystem.document.storage.FileStorage;
import com.itdou.ragknowledgesystem.ingestion.chunk.ChunkingProperties;
import com.itdou.ragknowledgesystem.ingestion.chunk.PageTextCleaner;
import com.itdou.ragknowledgesystem.ingestion.chunk.TokenChunkSplitter;
import com.itdou.ragknowledgesystem.ingestion.parser.PdfPage;
import com.itdou.ragknowledgesystem.ingestion.parser.PdfTextExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionServiceImplTest {

    private static final String STORAGE_KEY = "2026/09/11/stored.pdf";
    private static final String PAGE_TEXT = "第一行内容\n第二行内容";

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentChunkRepository documentChunkRepository;

    @Mock
    private FileStorage fileStorage;

    @Mock
    private PdfTextExtractor pdfTextExtractor;

    @Mock
    private PlatformTransactionManager transactionManager;

    private DocumentIngestionServiceImpl ingestionService;
    private UUID documentId;
    private UUID knowledgeBaseId;
    private Document document;

    @BeforeEach
    void setUp() {
        ChunkingProperties properties = new ChunkingProperties(600, 80, 120, 0.6, 3);
        // 用真实的清洗器和分块器，只把数据库和文件存储替换成 mock
        ingestionService = new DocumentIngestionServiceImpl(
                documentRepository,
                documentChunkRepository,
                fileStorage,
                pdfTextExtractor,
                new PageTextCleaner(),
                new TokenChunkSplitter(properties),
                properties,
                transactionManager
        );

        // 有些测试在拿到文档之前就失败了，不会走到事务，所以放宽这条桩的严格校验
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        documentId = UUID.randomUUID();
        knowledgeBaseId = UUID.randomUUID();
        document = Document.pending(knowledgeBaseId, "guide.pdf", STORAGE_KEY, "application/pdf", 2048L, "file-hash");
    }

    @Test
    void parseShouldPersistChunksAndMarkDocumentCompleted() {
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(fileStorage.loadPath(STORAGE_KEY)).thenReturn(Path.of("stored.pdf"));
        when(pdfTextExtractor.extract(any())).thenReturn(List.of(new PdfPage(1, PAGE_TEXT)));
        when(documentChunkRepository.findContentHashesByKnowledgeBaseId(knowledgeBaseId)).thenReturn(Set.of());

        DocumentParseResponse response = ingestionService.parse(documentId);

        assertThat(response.documentId()).isEqualTo(documentId);
        assertThat(response.parseStatus()).isEqualTo(DocumentParseStatus.COMPLETED);
        assertThat(response.pageCount()).isEqualTo(1);
        assertThat(response.emptyPageCount()).isZero();
        assertThat(response.chunkCount()).isEqualTo(1);
        assertThat(response.duplicateChunkCount()).isZero();
        assertThat(response.parsedAt()).isNotNull();
        assertThat(document.getParseStatus()).isEqualTo(DocumentParseStatus.COMPLETED);

        // 重新解析必须幂等：先清掉旧分块再写入
        verify(documentChunkRepository).deleteAllByDocumentId(documentId);

        ArgumentCaptor<List<DocumentChunk>> saved = ArgumentCaptor.forClass(List.class);
        verify(documentChunkRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
        DocumentChunk chunk = saved.getValue().get(0);
        assertThat(chunk.getDocumentId()).isEqualTo(documentId);
        assertThat(chunk.getKnowledgeBaseId()).isEqualTo(knowledgeBaseId);
        assertThat(chunk.getChunkIndex()).isZero();
        assertThat(chunk.getContent()).isEqualTo(PAGE_TEXT);
        assertThat(chunk.getPageNumberStart()).isEqualTo(1);
        assertThat(chunk.getPageNumberEnd()).isEqualTo(1);
        assertThat(chunk.getTokenCount()).isPositive();
    }

    @Test
    void parseShouldFailWhenPdfHasNoExtractableText() {
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(fileStorage.loadPath(STORAGE_KEY)).thenReturn(Path.of("scanned.pdf"));
        when(pdfTextExtractor.extract(any())).thenReturn(List.of(new PdfPage(1, "   ")));

        assertThatThrownBy(() -> ingestionService.parse(documentId))
                .isInstanceOf(DocumentParsingException.class)
                .hasMessageContaining("未从 PDF 中提取到任何文本");

        // 失败原因要写回文档，否则调用方查不到为什么失败
        assertThat(document.getParseStatus()).isEqualTo(DocumentParseStatus.FAILED);
        assertThat(document.getErrorMessage()).contains("未从 PDF 中提取到任何文本");
        verify(documentChunkRepository, never()).saveAll(any());
    }

    @Test
    void parseShouldWrapUnexpectedExtractionFailureIntoParsingException() {
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(fileStorage.loadPath(STORAGE_KEY)).thenReturn(Path.of("broken.pdf"));
        when(pdfTextExtractor.extract(any())).thenThrow(new IllegalStateException("磁盘读取失败"));

        assertThatThrownBy(() -> ingestionService.parse(documentId))
                .isInstanceOf(DocumentParsingException.class)
                .hasMessageContaining("解析 PDF 失败")
                .hasRootCauseMessage("磁盘读取失败");

        assertThat(document.getParseStatus()).isEqualTo(DocumentParseStatus.FAILED);
    }

    @Test
    void parseShouldSkipChunkWhoseContentAlreadyExistsInKnowledgeBase() throws Exception {
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(fileStorage.loadPath(STORAGE_KEY)).thenReturn(Path.of("stored.pdf"));
        when(pdfTextExtractor.extract(any())).thenReturn(List.of(new PdfPage(1, PAGE_TEXT)));
        // 同一知识库里已经有内容相同的分块，uk_knowledge_base_chunk_hash 会拒绝重复写入
        when(documentChunkRepository.findContentHashesByKnowledgeBaseId(knowledgeBaseId))
                .thenReturn(Set.of(sha256(PAGE_TEXT)));

        DocumentParseResponse response = ingestionService.parse(documentId);

        assertThat(response.chunkCount()).isZero();
        assertThat(response.duplicateChunkCount()).isEqualTo(1);
        assertThat(response.parseStatus()).isEqualTo(DocumentParseStatus.COMPLETED);
    }

    @Test
    void parseShouldRejectUnknownDocument() {
        UUID unknownId = UUID.randomUUID();
        when(documentRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ingestionService.parse(unknownId))
                .isInstanceOf(DocumentNotFoundException.class)
                .hasMessageContaining(unknownId.toString());
    }

    @Test
    void listChunksShouldRejectUnknownDocument() {
        UUID unknownId = UUID.randomUUID();
        when(documentRepository.existsById(unknownId)).thenReturn(false);

        assertThatThrownBy(() -> ingestionService.listChunks(unknownId))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    // 与 TokenChunkSplitter 使用同一套 SHA-256 口径，用来构造「已存在的分块」
    private String sha256(String content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
    }
}
