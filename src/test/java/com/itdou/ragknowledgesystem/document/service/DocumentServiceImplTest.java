package com.itdou.ragknowledgesystem.document.service;

import com.itdou.ragknowledgesystem.document.dto.DocumentResponse;
import com.itdou.ragknowledgesystem.document.entity.DocumentParseStatus;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
                .hasMessageContaining("not a valid PDF");

        verify(fileStorage, never()).storePdf(any());
        verify(documentRepository, never()).saveAndFlush(any());
    }
}
