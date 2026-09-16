package com.itdou.ragknowledgesystem.retrieval.service;

import com.itdou.ragknowledgesystem.document.dto.ChunkEmbeddingResponse;
import com.itdou.ragknowledgesystem.document.entity.Document;
import com.itdou.ragknowledgesystem.document.entity.DocumentChunk;
import com.itdou.ragknowledgesystem.document.exception.DocumentNotFoundException;
import com.itdou.ragknowledgesystem.document.repository.DocumentChunkRepository;
import com.itdou.ragknowledgesystem.document.repository.DocumentRepository;
import com.itdou.ragknowledgesystem.infrastructure.ai.EmbeddingProperties;
import com.itdou.ragknowledgesystem.retrieval.exception.EmbeddingFailedException;
import com.itdou.ragknowledgesystem.retrieval.vector.VectorLiteral;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 分块向量化的编排。
 *
 * 和文档解析一样，这里刻意不加类级 @Transactional：
 * 调用外部 Embedding 服务可能一次要几百毫秒到几秒，整段包在事务里会长时间占用数据库连接。
 * 所以只在写库时开短事务，用 TransactionTemplate 显式控制边界。
 */
@Service
@Slf4j
public class ChunkEmbeddingServiceImpl implements ChunkEmbeddingService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingProperties properties;
    private final TransactionTemplate transactionTemplate;

    public ChunkEmbeddingServiceImpl(
            DocumentRepository documentRepository,
            DocumentChunkRepository documentChunkRepository,
            EmbeddingModel embeddingModel,
            EmbeddingProperties properties,
            PlatformTransactionManager transactionManager
    ) {
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public ChunkEmbeddingResponse embedDocument(UUID documentId) {
        // 没有 Key 时直接给出明确提示，不要等 HTTP 调用失败再抛一个看不懂的错
        if (!properties.configured()) {
            throw new EmbeddingFailedException("未配置 Embedding 服务，请先设置环境变量 SILICONFLOW_API_KEY");
        }

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        log.info("开始向量化文档 documentId={} fileName={} model={}",
                documentId, document.getOriginalFileName(), properties.model());

        List<DocumentChunk> pending = documentChunkRepository
                .findChunksNeedingEmbedding(documentId, properties.model());
        long totalCount = documentChunkRepository.countByDocumentId(documentId);
        int alreadyEmbeddedCount = (int) (totalCount - pending.size());

        if (pending.isEmpty()) {
            log.info("文档无需向量化，分块已全部由当前模型处理过 documentId={} 分块数={}", documentId, totalCount);
            return new ChunkEmbeddingResponse(documentId, properties.model(), properties.dimension(),
                    0, alreadyEmbeddedCount, null);
        }
        log.info("待向量化分块 {} 个 documentId={} 批大小={}", pending.size(), documentId, properties.batchSize());

        int embeddedCount = 0;
        for (int start = 0; start < pending.size(); start += properties.batchSize()) {
            int end = Math.min(start + properties.batchSize(), pending.size());
            embeddedCount += embedBatch(pending.subList(start, end));
            log.debug("向量化进度 documentId={} {}/{}", documentId, embeddedCount, pending.size());
        }

        log.info("文档向量化完成 documentId={} 本次向量化={} 已跳过={}",
                documentId, embeddedCount, alreadyEmbeddedCount);
        return new ChunkEmbeddingResponse(documentId, properties.model(), properties.dimension(),
                embeddedCount, alreadyEmbeddedCount, Instant.now());
    }

    private int embedBatch(List<DocumentChunk> batch) {
        List<String> contents = batch.stream().map(DocumentChunk::getContent).toList();

        List<float[]> vectors;
        try {
            vectors = embeddingModel.embed(contents);
        } catch (RuntimeException exception) {
            log.error("调用 Embedding 服务失败 批大小={}", batch.size(), exception);
            throw new EmbeddingFailedException("调用 Embedding 服务失败: " + exception.getMessage(), exception);
        }
        if (vectors.size() != batch.size()) {
            throw new EmbeddingFailedException("Embedding 服务返回的向量数量与请求不一致：请求 " + batch.size()
                    + " 条，返回 " + vectors.size() + " 条");
        }

        // 先在事务外把字面量拼好：维度或数值不合法时不需要开事务就能失败
        List<String> literals = new ArrayList<>(batch.size());
        for (float[] vector : vectors) {
            literals.add(VectorLiteral.toLiteral(vector, properties.dimension()));
        }

        String model = properties.model();
        transactionTemplate.executeWithoutResult(status -> {
            for (int index = 0; index < batch.size(); index++) {
                documentChunkRepository.updateEmbedding(batch.get(index).getId(), literals.get(index), model);
            }
        });
        return batch.size();
    }
}
