package com.itdou.ragknowledgesystem.document.service;

import com.itdou.ragknowledgesystem.document.dto.DocumentResponse;
import com.itdou.ragknowledgesystem.document.entity.Document;
import com.itdou.ragknowledgesystem.document.exception.DocumentNotFoundException;
import com.itdou.ragknowledgesystem.document.exception.DuplicateDocumentException;
import com.itdou.ragknowledgesystem.document.exception.InvalidDocumentException;
import com.itdou.ragknowledgesystem.document.repository.DocumentRepository;
import com.itdou.ragknowledgesystem.document.storage.FileStorage;
import com.itdou.ragknowledgesystem.document.storage.LocalStorageProperties;
import com.itdou.ragknowledgesystem.knowledgebase.exception.KnowledgeBaseNotFoundException;
import com.itdou.ragknowledgesystem.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
@Slf4j
public class DocumentServiceImpl implements DocumentService {

    private static final int PDF_HEADER_SCAN_SIZE = 1_024;
    private static final String PDF_MEDIA_TYPE = "application/pdf";

    private final DocumentRepository documentRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final FileStorage fileStorage;
    private final long maxFileSizeBytes;

    public DocumentServiceImpl(
            DocumentRepository documentRepository,
            KnowledgeBaseRepository knowledgeBaseRepository,
            FileStorage fileStorage,
            LocalStorageProperties storageProperties
    ) {
        this.documentRepository = documentRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.fileStorage = fileStorage;
        this.maxFileSizeBytes = storageProperties.maxFileSizeBytes();
    }

    @Override
    public DocumentResponse upload(UUID knowledgeBaseId, MultipartFile file) {
        log.info("收到文档上传请求 knowledgeBaseId={} fileName={} size={} 字节",
                knowledgeBaseId,
                file == null ? null : file.getOriginalFilename(),
                file == null ? null : file.getSize());

        ensureKnowledgeBaseExists(knowledgeBaseId);
        validatePdf(file);
        log.debug("文件校验通过 fileName={} mediaType={}", file.getOriginalFilename(), file.getContentType());

        //检查重复文件，JPA会根据方法名进行查询
        String fileHash = calculateSha256(file);
        log.debug("文件哈希计算完成 fileName={} fileHash={}", file.getOriginalFilename(), fileHash);

        if (documentRepository.existsByKnowledgeBaseIdAndFileHash(knowledgeBaseId, fileHash)) {
            log.warn("知识库中已存在相同文件，拒绝上传 knowledgeBaseId={} fileName={} fileHash={}",
                    knowledgeBaseId, file.getOriginalFilename(), fileHash);
            throw new DuplicateDocumentException();
        }

        String storageKey = fileStorage.storePdf(file);//存储路径
        log.info("原文件已保存 storageKey={}", storageKey);

        try {
            Document document = Document.pending(
                    knowledgeBaseId,
                    originalFileName(file),
                    storageKey,
                    PDF_MEDIA_TYPE,
                    file.getSize(),
                    fileHash
            );
            DocumentResponse response = toResponse(documentRepository.saveAndFlush(document));
            log.info("文档记录已创建 documentId={} parseStatus={}（等待调用解析接口）",
                    response.id(), response.parseStatus());
            return response;
        } catch (RuntimeException exception) {
            // 数据库记录没建成，就不要把文件留在磁盘上
            log.error("文档入库失败，回滚已保存的物理文件 storageKey={}", storageKey, exception);
            try {
                fileStorage.delete(storageKey);
            } catch (RuntimeException rollbackFailure) {
                // 回滚失败只记日志：抛原始异常对调用方排查更有价值
                log.warn("回滚物理文件也失败了，需要手工清理 storageKey={}", storageKey, rollbackFailure);
            }
            throw exception;
        }
    }

    //查询知识库中的文档
    @Override
    @Transactional(readOnly = true)
    public List<DocumentResponse> listByKnowledgeBaseId(UUID knowledgeBaseId) {
        log.debug("查询知识库文档列表 knowledgeBaseId={}", knowledgeBaseId);

        ensureKnowledgeBaseExists(knowledgeBaseId);
        List<DocumentResponse> documents = documentRepository
                .findByKnowledgeBaseIdOrderByCreatedAtDesc(knowledgeBaseId)
                .stream()
                .map(this::toResponse)
                .toList();

        log.info("查询知识库文档列表完成 knowledgeBaseId={} 共 {} 条", knowledgeBaseId, documents.size());
        return documents;
    }

    //查询单个文档
    @Override
    @Transactional(readOnly = true)
    public DocumentResponse getById(UUID documentId) {
        log.debug("查询文档详情 documentId={}", documentId);

        return toResponse(findById(documentId));
    }

    //删除文档
    @Override
    public void delete(UUID documentId) {
        log.info("删除文档 documentId={}", documentId);

        Document document = findById(documentId);

        // 先 flush 再删物理文件：数据库删失败时，文件必须还在，否则会出现记录还在但文件没了
        documentRepository.delete(document);
        documentRepository.flush();
        log.debug("文档记录已删除，开始清理物理文件 storageKey={}", document.getStorageKey());

        fileStorage.delete(document.getStorageKey());
        log.info("文档删除完成 documentId={}", documentId);
    }

    //检查知识库是否存在
    private void ensureKnowledgeBaseExists(UUID knowledgeBaseId) {
        if (!knowledgeBaseRepository.existsById(knowledgeBaseId)) {
            log.warn("知识库不存在，操作被拒绝 knowledgeBaseId={}", knowledgeBaseId);
            throw new KnowledgeBaseNotFoundException(knowledgeBaseId);
        }
    }

    private Document findById(UUID documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    //校验PDF
    private void validatePdf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidDocumentException("上传文件不能为空");
        }
        if (file.getSize() > maxFileSizeBytes) {
            log.warn("上传文件超过大小上限 size={} 字节 上限={} 字节", file.getSize(), maxFileSizeBytes);
            throw new InvalidDocumentException("上传文件超过大小上限");
        }
        if (!originalFileName(file).toLowerCase().endsWith(".pdf")) {
            throw new InvalidDocumentException("只支持 PDF 格式的文件");
        }
        if (!hasPdfHeader(file)) {
            throw new InvalidDocumentException("上传内容不是有效的 PDF 文件");
        }
    }

    //在前1024个字节里查找%PDF-
    private boolean hasPdfHeader(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            byte[] header = inputStream.readNBytes(PDF_HEADER_SCAN_SIZE);
            for (int index = 0; index <= header.length - 5; index++) {
                if (header[index] == '%' && header[index + 1] == 'P' && header[index + 2] == 'D'
                        && header[index + 3] == 'F' && header[index + 4] == '-') {
                    return true;
                }
            }
            return false;
        } catch (IOException exception) {
            log.error("读取上传文件内容失败 fileName={}", file.getOriginalFilename(), exception);
            throw new InvalidDocumentException("读取上传文件失败");
        }
    }

    //计算SHA-256，判断一个知识库中是否上传过一个完全相同的文件
    private String calculateSha256(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            log.error("计算文件哈希失败 fileName={}", file.getOriginalFilename(), exception);
            throw new InvalidDocumentException("计算文件哈希失败");
        }
    }

    //读取并检验用户上传的原始文件名
    private String originalFileName(MultipartFile file) {
        String originalFileName = file.getOriginalFilename();
        if (originalFileName == null || originalFileName.isBlank()
                || originalFileName.contains("..") || originalFileName.contains("/") || originalFileName.contains("\\")) {
            log.warn("非法的原始文件名，拒绝上传 fileName={}", originalFileName);
            throw new InvalidDocumentException("原始文件名不合法");
        }
        return originalFileName;
    }

    //将数据库实体转换成接口响应DTO
    private DocumentResponse toResponse(Document document) {
        return new DocumentResponse(
                document.getId(),
                document.getKnowledgeBaseId(),
                document.getOriginalFileName(),
                document.getMediaType(),
                document.getFileSize(),
                document.getParseStatus(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }
}
