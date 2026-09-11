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
        ensureKnowledgeBaseExists(knowledgeBaseId);
        validatePdf(file);

        //检查重复文件，JPA会根据方法名进行查询
        String fileHash = calculateSha256(file);
        if (documentRepository.existsByKnowledgeBaseIdAndFileHash(knowledgeBaseId, fileHash)) {
            throw new DuplicateDocumentException();
        }

        String storageKey = fileStorage.storePdf(file);//存储路径
        try {
            Document document = Document.pending(
                    knowledgeBaseId,
                    originalFileName(file),
                    storageKey,
                    PDF_MEDIA_TYPE,
                    file.getSize(),
                    fileHash
            );
            return toResponse(documentRepository.saveAndFlush(document));
        } catch (RuntimeException exception) {
            // Do not retain a local file when the database record cannot be created.
            try {
                fileStorage.delete(storageKey);
            } catch (RuntimeException ignored) {
                // The original persistence exception is more useful to the caller.
            }
            throw exception;
        }
    }

    //查询知识库中的文档
    @Override
    @Transactional(readOnly = true)
    public List<DocumentResponse> listByKnowledgeBaseId(UUID knowledgeBaseId) {
        ensureKnowledgeBaseExists(knowledgeBaseId);
        return documentRepository.findByKnowledgeBaseIdOrderByCreatedAtDesc(knowledgeBaseId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    //查询单个文档
    @Override
    @Transactional(readOnly = true)
    public DocumentResponse getById(UUID documentId) {
        return toResponse(findById(documentId));
    }

    //删除文档
    @Override
    public void delete(UUID documentId) {
        Document document = findById(documentId);

        // Flush first so a database failure does not delete a still-referenced local file.
        documentRepository.delete(document);
        documentRepository.flush();
        fileStorage.delete(document.getStorageKey());
    }

    //检查知识库是否存在
    private void ensureKnowledgeBaseExists(UUID knowledgeBaseId) {
        if (!knowledgeBaseRepository.existsById(knowledgeBaseId)) {
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
            throw new InvalidDocumentException("file must not be empty");
        }
        if (file.getSize() > maxFileSizeBytes) {
            throw new InvalidDocumentException("file exceeds the maximum allowed size");
        }
        if (!originalFileName(file).toLowerCase().endsWith(".pdf")) {
            throw new InvalidDocumentException("only PDF files are supported");
        }
        if (!hasPdfHeader(file)) {
            throw new InvalidDocumentException("uploaded content is not a valid PDF file");
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
            throw new InvalidDocumentException("failed to read uploaded file");
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
            throw new InvalidDocumentException("failed to calculate file hash");
        }
    }

    //读取并检验用户上传的原始文件名
    private String originalFileName(MultipartFile file) {
        String originalFileName = file.getOriginalFilename();
        if (originalFileName == null || originalFileName.isBlank()
                || originalFileName.contains("..") || originalFileName.contains("/") || originalFileName.contains("\\")) {
            throw new InvalidDocumentException("invalid original file name");
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
