package com.itdou.ragknowledgesystem.ingestion.service;

import com.itdou.ragknowledgesystem.document.dto.DocumentChunkResponse;
import com.itdou.ragknowledgesystem.document.dto.DocumentParseResponse;
import com.itdou.ragknowledgesystem.document.entity.Document;
import com.itdou.ragknowledgesystem.document.entity.DocumentChunk;
import com.itdou.ragknowledgesystem.document.exception.DocumentNotFoundException;
import com.itdou.ragknowledgesystem.document.exception.DocumentParsingException;
import com.itdou.ragknowledgesystem.document.repository.DocumentChunkRepository;
import com.itdou.ragknowledgesystem.document.repository.DocumentRepository;
import com.itdou.ragknowledgesystem.document.storage.FileStorage;
import com.itdou.ragknowledgesystem.ingestion.chunk.ChunkingProperties;
import com.itdou.ragknowledgesystem.ingestion.chunk.DocumentChunkDraft;
import com.itdou.ragknowledgesystem.ingestion.chunk.PageTextCleaner;
import com.itdou.ragknowledgesystem.ingestion.chunk.TokenChunkSplitter;
import com.itdou.ragknowledgesystem.ingestion.parser.PdfPage;
import com.itdou.ragknowledgesystem.ingestion.parser.PdfTextExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 文档解析入库的编排。
 *
 * 这里刻意不加类级 @Transactional：提取和分块是纯 CPU 工作，一本大 PDF 可能要跑几十秒，
 * 如果整段包在事务里会长时间占用数据库连接。所以只把状态更新和分块写入包进短事务，
 * 用 TransactionTemplate 显式控制边界（在同一个类里调用自己的 @Transactional 方法不会走代理）。
 */
@Service
@Slf4j
public class DocumentIngestionServiceImpl implements DocumentIngestionService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final FileStorage fileStorage;
    private final PdfTextExtractor pdfTextExtractor;
    private final PageTextCleaner pageTextCleaner;
    private final TokenChunkSplitter tokenChunkSplitter;
    private final ChunkingProperties chunkingProperties;
    private final TransactionTemplate transactionTemplate;

    public DocumentIngestionServiceImpl(
            DocumentRepository documentRepository,
            DocumentChunkRepository documentChunkRepository,
            FileStorage fileStorage,
            PdfTextExtractor pdfTextExtractor,
            PageTextCleaner pageTextCleaner,
            TokenChunkSplitter tokenChunkSplitter,
            ChunkingProperties chunkingProperties,
            PlatformTransactionManager transactionManager
    ) {
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.fileStorage = fileStorage;
        this.pdfTextExtractor = pdfTextExtractor;
        this.pageTextCleaner = pageTextCleaner;
        this.tokenChunkSplitter = tokenChunkSplitter;
        this.chunkingProperties = chunkingProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public DocumentParseResponse parse(UUID documentId) {
        Document document = loadDocument(documentId);
        log.info("开始解析文档 documentId={} fileName={}", documentId, document.getOriginalFileName());

        Path pdfPath = fileStorage.loadPath(document.getStorageKey());

        // 先落 PARSING 状态，这样解析期间查文档能看到进度，而不是一直停在 PENDING
        markParsing(documentId);
        log.debug("文档状态已置为 PARSING documentId={}", documentId);

        List<PdfPage> pages;
        List<DocumentChunkDraft> drafts;
        int emptyPageCount;
        try {
            pages = pdfTextExtractor.extract(pdfPath);
            emptyPageCount = (int) pages.stream().filter(page -> !page.hasText()).count();
            log.info("PDF 文本提取完成 documentId={} 总页数={} 无文本页数={}",
                    documentId, pages.size(), emptyPageCount);

            List<PdfPage> cleanedPages = pageTextCleaner.clean(pages, chunkingProperties);
            drafts = tokenChunkSplitter.split(cleanedPages);
            log.info("文本清洗与分块完成 documentId={} 分块数={}", documentId, drafts.size());

            if (drafts.isEmpty()) {
                log.warn("未从 PDF 中提取到任何可用文本 documentId={}", documentId);
                throw new DocumentParsingException("未从 PDF 中提取到任何文本，可能是扫描版或图片型 PDF");
            }
        } catch (RuntimeException exception) {
            log.error("文档解析失败 documentId={} 原因={}", documentId, exception.getMessage(), exception);
            // 失败原因要留在文档上，否则调用方只能看到一个 4xx/5xx，查不到为什么
            markFailed(documentId, exception.getMessage());
            if (exception instanceof DocumentParsingException parsingException) {
                throw parsingException;
            }
            throw new DocumentParsingException("解析 PDF 失败: " + exception.getMessage(), exception);
        }

        return persist(documentId, document.getKnowledgeBaseId(), drafts, pages.size(), emptyPageCount);
    }

    @Override
    public List<DocumentChunkResponse> listChunks(UUID documentId) {
        log.debug("查询文档分块 documentId={}", documentId);

        if (!documentRepository.existsById(documentId)) {
            log.warn("文档不存在，查询分块被拒绝 documentId={}", documentId);
            throw new DocumentNotFoundException(documentId);
        }

        List<DocumentChunkResponse> chunks = documentChunkRepository
                .findByDocumentIdOrderByChunkIndexAsc(documentId)
                .stream()
                .map(DocumentIngestionServiceImpl::toResponse)
                .toList();

        log.info("查询文档分块完成 documentId={} 共 {} 条", documentId, chunks.size());
        return chunks;
    }

    // 只接收 id 而不是整个游离态实体：文档主键由数据库生成，游离对象在 flush 之前拿不到 id
    private DocumentParseResponse persist(
            UUID documentId,
            UUID knowledgeBaseId,
            List<DocumentChunkDraft> drafts,
            int pageCount,
            int emptyPageCount
    ) {
        return transactionTemplate.execute(status -> {
            // 重新解析要幂等，先清空旧分块
            documentChunkRepository.deleteAllByDocumentId(documentId);

            // uk_knowledge_base_chunk_hash 保证同一知识库内分块内容唯一，重复内容直接跳过
            Set<String> takenHashes = new HashSet<>(
                    documentChunkRepository.findContentHashesByKnowledgeBaseId(knowledgeBaseId));

            List<DocumentChunk> chunks = new ArrayList<>(drafts.size());
            for (DocumentChunkDraft draft : drafts) {
                if (!takenHashes.add(draft.contentHash())) {
                    continue;
                }
                // 去重后重新连续编号，保证 chunk_index 没有空洞
                chunks.add(DocumentChunk.create(
                        documentId,
                        knowledgeBaseId,
                        chunks.size(),
                        draft.content(),
                        draft.contentHash(),
                        draft.pageNumberStart(),
                        draft.pageNumberEnd(),
                        draft.tokenCount()
                ));
            }
            log.debug("分块去重完成 documentId={} 实际写入={} 去重跳过={}",
                    documentId, chunks.size(), drafts.size() - chunks.size());
            documentChunkRepository.saveAll(chunks);
            log.debug("分块已写入 document_chunk documentId={} 条数={}", documentId, chunks.size());

            // 重新取一次托管态实体再改状态：进入事务的实体是游离对象
            Document managed = documentRepository.findById(documentId)
                    .orElseThrow(() -> new DocumentNotFoundException(documentId));
            managed.markCompleted(Instant.now());
            documentRepository.saveAndFlush(managed);

            int duplicateChunkCount = drafts.size() - chunks.size();
            log.info("文档解析完成 documentId={} 页数={} 空页数={} 分块数={} 去重跳过={}",
                    documentId, pageCount, emptyPageCount, chunks.size(), duplicateChunkCount);

            return new DocumentParseResponse(
                    documentId,
                    managed.getParseStatus(),
                    pageCount,
                    emptyPageCount,
                    chunks.size(),
                    duplicateChunkCount,
                    managed.getParsedAt()
            );
        });
    }

    private void markParsing(UUID documentId) {
        transactionTemplate.executeWithoutResult(status -> documentRepository.findById(documentId)
                .ifPresent(Document::markParsing));
    }

    private void markFailed(UUID documentId, String message) {
        try {
            transactionTemplate.executeWithoutResult(status -> documentRepository.findById(documentId)
                    .ifPresent(document -> document.markFailed(message)));
            log.debug("文档状态已置为 FAILED documentId={} errorMessage={}", documentId, message);
        } catch (RuntimeException exception) {
            // 回写状态失败不应该盖掉真正的解析异常，记日志后继续抛原始异常
            log.warn("回写文档解析失败状态时出错 documentId={}", documentId, exception);
        }
    }

    private Document loadDocument(UUID documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    private static DocumentChunkResponse toResponse(DocumentChunk chunk) {
        return new DocumentChunkResponse(
                chunk.getId(),
                chunk.getDocumentId(),
                chunk.getChunkIndex(),
                chunk.getContent(),
                chunk.getContentHash(),
                chunk.getPageNumberStart(),
                chunk.getPageNumberEnd(),
                chunk.getTokenCount(),
                chunk.getCreatedAt()
        );
    }
}
