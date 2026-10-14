package com.itdou.ragknowledgesystem.document.service;

import com.itdou.ragknowledgesystem.document.dto.DocumentResponse;
import com.itdou.ragknowledgesystem.document.entity.DocumentParseStatus;
import com.itdou.ragknowledgesystem.document.exception.DocumentStorageException;
import com.itdou.ragknowledgesystem.document.exception.InvalidDocumentException;
import com.itdou.ragknowledgesystem.document.repository.DocumentRepository;
import com.itdou.ragknowledgesystem.document.storage.FileStorage;
import com.itdou.ragknowledgesystem.document.storage.LocalStorageProperties;
import com.itdou.ragknowledgesystem.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceImplTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private FileStorage fileStorage;

    private DocumentServiceImpl documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentServiceImpl(
                documentRepository,
                knowledgeBaseRepository,
                fileStorage,
                new LocalStorageProperties(Path.of("storage/documents"), 50 * 1024 * 1024)
        );
    }

    @Test
    void uploadShouldStoreValidPdfAndCreatePendingDocument() {
        UUID knowledgeBaseId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "guide.pdf",
                "application/pdf",
                "%PDF-1.7\nexample content".getBytes(StandardCharsets.UTF_8)
        );
        when(knowledgeBaseRepository.existsById(knowledgeBaseId)).thenReturn(true);
        when(documentRepository.existsByKnowledgeBaseIdAndFileHash(any(), any())).thenReturn(false);
        when(fileStorage.storePdf(file)).thenReturn("2026/09/08/test.pdf");
        when(documentRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DocumentResponse response = documentService.upload(knowledgeBaseId, file);

        assertThat(response.originalFileName()).isEqualTo("guide.pdf");
        assertThat(response.parseStatus()).isEqualTo(DocumentParseStatus.PENDING);
        verify(fileStorage).storePdf(file);
        verify(documentRepository).saveAndFlush(any());
    }

    @Test
    void uploadShouldRejectNonPdfContentBeforeStorage() {
        UUID knowledgeBaseId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "notes.pdf",
                "application/pdf",
                "plain text".getBytes(StandardCharsets.UTF_8)
        );
        when(knowledgeBaseRepository.existsById(knowledgeBaseId)).thenReturn(true);

        assertThatThrownBy(() -> documentService.upload(knowledgeBaseId, file))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("不是有效的 PDF 文件");

        verify(fileStorage, never()).storePdf(any());
        verify(documentRepository, never()).saveAndFlush(any());
    }

    // 删除知识库时必须连物理文件一起清理，否则文件会永远留在磁盘上
    @Test
    void deleteAllUnderKnowledgeBaseShouldRemoveRecordsThenFiles() {
        UUID knowledgeBaseId = UUID.randomUUID();
        when(documentRepository.findStorageKeysByKnowledgeBaseId(knowledgeBaseId))
                .thenReturn(List.of("2026/09/12/a.pdf", "2026/09/12/b.pdf"));

        int deletedDocuments = documentService.deleteAllUnderKnowledgeBase(knowledgeBaseId);

        assertThat(deletedDocuments).isEqualTo(2);
        verify(documentRepository).deleteAllByKnowledgeBaseId(knowledgeBaseId);
        verify(fileStorage).delete("2026/09/12/a.pdf");
        verify(fileStorage).delete("2026/09/12/b.pdf");
    }

    @Test
    void deleteAllUnderKnowledgeBaseShouldDoNothingWhenThereIsNoDocument() {
        UUID knowledgeBaseId = UUID.randomUUID();
        when(documentRepository.findStorageKeysByKnowledgeBaseId(knowledgeBaseId)).thenReturn(List.of());

        assertThat(documentService.deleteAllUnderKnowledgeBase(knowledgeBaseId)).isZero();

        verify(documentRepository, never()).deleteAllByKnowledgeBaseId(any());
        verify(fileStorage, never()).delete(any());
    }

    // 单个文件删失败不应该阻断其余文件的清理
    @Test
    void deleteAllUnderKnowledgeBaseShouldContinueAfterFileFailure() {
        UUID knowledgeBaseId = UUID.randomUUID();
        when(documentRepository.findStorageKeysByKnowledgeBaseId(knowledgeBaseId))
                .thenReturn(List.of("bad.pdf", "good.pdf"));
        doThrow(new DocumentStorageException("删除失败", null)).when(fileStorage).delete("bad.pdf");

        assertThat(documentService.deleteAllUnderKnowledgeBase(knowledgeBaseId)).isEqualTo(2);

        verify(fileStorage).delete("good.pdf");
    }
}
