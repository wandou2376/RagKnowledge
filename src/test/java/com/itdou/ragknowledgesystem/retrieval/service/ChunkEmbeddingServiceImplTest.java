package com.itdou.ragknowledgesystem.retrieval.service;

import com.itdou.ragknowledgesystem.document.dto.ChunkEmbeddingResponse;
import com.itdou.ragknowledgesystem.document.entity.Document;
import com.itdou.ragknowledgesystem.document.entity.DocumentChunk;
import com.itdou.ragknowledgesystem.document.exception.DocumentNotFoundException;
import com.itdou.ragknowledgesystem.document.repository.DocumentChunkRepository;
import com.itdou.ragknowledgesystem.document.repository.DocumentRepository;
import com.itdou.ragknowledgesystem.infrastructure.ai.EmbeddingProperties;
import com.itdou.ragknowledgesystem.retrieval.exception.EmbeddingFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChunkEmbeddingServiceImplTest {

    private static final int DIMENSION = 1024;
    private static final String MODEL = "BAAI/bge-m3";

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentChunkRepository documentChunkRepository;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private PlatformTransactionManager transactionManager;

    private ChunkEmbeddingServiceImpl embeddingService;
    private UUID documentId;
    private UUID knowledgeBaseId;

    @BeforeEach
    void setUp() {
        EmbeddingProperties properties = new EmbeddingProperties(
                "https://api.siliconflow.cn/v1", MODEL, "test-api-key", DIMENSION, 16);
        embeddingService = new ChunkEmbeddingServiceImpl(
                documentRepository, documentChunkRepository, embeddingModel, properties, transactionManager);

        // 有测试在拿到文档之前就失败了，不会走到事务，所以放宽这条桩的严格校验
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        documentId = UUID.randomUUID();
        knowledgeBaseId = UUID.randomUUID();
    }

    @Test
    void shouldEmbedPendingChunksAndReportCounts() {
        givenDocument();
        givenChunks(chunk("第一块内容"), chunk("第二块内容"));
        when(documentChunkRepository.countByDocumentId(documentId)).thenReturn(2L);
        when(embeddingModel.embed(any(List.class))).thenReturn(List.of(vector(0.1f), vector(0.2f)));

        ChunkEmbeddingResponse response = embeddingService.embedDocument(documentId);

        assertThat(response.documentId()).isEqualTo(documentId);
        assertThat(response.embeddingModel()).isEqualTo(MODEL);
        assertThat(response.dimension()).isEqualTo(DIMENSION);
        assertThat(response.embeddedCount()).isEqualTo(2);
        assertThat(response.alreadyEmbeddedCount()).isZero();
        assertThat(response.finishedAt()).isNotNull();

        verify(documentChunkRepository, times(2)).updateEmbedding(any(), anyString(), eq(MODEL));
    }

    // 已经向量化过的分块必须跳过，否则重复调用会白花钱
    @Test
    void shouldSkipWhenNothingNeedsEmbedding() {
        givenDocument();
        givenChunks();
        when(documentChunkRepository.countByDocumentId(documentId)).thenReturn(5L);

        ChunkEmbeddingResponse response = embeddingService.embedDocument(documentId);

        assertThat(response.embeddedCount()).isZero();
        assertThat(response.alreadyEmbeddedCount()).isEqualTo(5);
        // 没有待处理分块时不应该调用外部服务，也不该有任何写入
        assertThat(response.finishedAt()).isNull();
        verify(embeddingModel, never()).embed(any(List.class));
        verify(documentChunkRepository, never()).updateEmbedding(any(), anyString(), anyString());
    }

    // 维度不匹配时要在写库之前失败，不能让数据库抛一个看不懂的错
    @Test
    void shouldFailBeforeWritingWhenDimensionMismatches() {
        givenDocument();
        givenChunks(chunk("内容"));
        when(documentChunkRepository.countByDocumentId(documentId)).thenReturn(1L);
        // 模型返回 3 维，配置是 1024 维
        when(embeddingModel.embed(any(List.class))).thenReturn(List.of(new float[]{0.1f, 0.2f, 0.3f}));

        assertThatThrownBy(() -> embeddingService.embedDocument(documentId))
                .isInstanceOf(EmbeddingFailedException.class)
                .hasMessageContaining("向量维度与配置不一致");

        verify(documentChunkRepository, never()).updateEmbedding(any(), anyString(), anyString());
    }

    @Test
    void shouldFailWhenProviderReturnsFewerVectorsThanRequested() {
        givenDocument();
        givenChunks(chunk("第一块"), chunk("第二块"));
        when(documentChunkRepository.countByDocumentId(documentId)).thenReturn(2L);
        when(embeddingModel.embed(any(List.class))).thenReturn(List.of(vector(0.1f)));

        assertThatThrownBy(() -> embeddingService.embedDocument(documentId))
                .isInstanceOf(EmbeddingFailedException.class)
                .hasMessageContaining("向量数量与请求不一致");
    }

    @Test
    void shouldWrapProviderFailureIntoEmbeddingFailedException() {
        givenDocument();
        givenChunks(chunk("内容"));
        when(documentChunkRepository.countByDocumentId(documentId)).thenReturn(1L);
        when(embeddingModel.embed(any(List.class))).thenThrow(new IllegalStateException("401 Unauthorized"));

        assertThatThrownBy(() -> embeddingService.embedDocument(documentId))
                .isInstanceOf(EmbeddingFailedException.class)
                .hasMessageContaining("调用 Embedding 服务失败")
                .hasRootCauseMessage("401 Unauthorized");
    }

    @Test
    void shouldRejectUnknownDocument() {
        UUID unknownId = UUID.randomUUID();
        when(documentRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> embeddingService.embedDocument(unknownId))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    private void givenDocument() {
        when(documentRepository.findById(documentId))
                .thenReturn(Optional.of(Document.pending(knowledgeBaseId, "guide.pdf", "2026/09/12/x.pdf",
                        "application/pdf", 2048L, "file-hash")));
    }

    private void givenChunks(DocumentChunk... chunks) {
        when(documentChunkRepository.findChunksNeedingEmbedding(documentId, MODEL))
                .thenReturn(List.of(chunks));
    }

    private static DocumentChunk chunk(String content) {
        return DocumentChunk.create(UUID.randomUUID(), UUID.randomUUID(), 0, content,
                "a".repeat(64), 1, 1, 10);
    }

    private static float[] vector(float value) {
        float[] embedding = new float[DIMENSION];
        java.util.Arrays.fill(embedding, value);
        return embedding;
    }
}
