package com.itdou.ragknowledgesystem.document.service;

import com.itdou.ragknowledgesystem.document.dto.DocumentResponse;
import com.itdou.ragknowledgesystem.document.entity.Document;
import com.itdou.ragknowledgesystem.document.entity.DocumentFormat;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@Transactional
@Slf4j
public class DocumentServiceImpl implements DocumentService {

    private static final int MAGIC_HEADER_SCAN_SIZE = 1_024;
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] ZIP_MAGIC = {'P', 'K', 0x03, 0x04};

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
        DocumentFormat format = validateDocument(file);

        //检查重复文件，JPA会根据方法名进行查询
        String fileHash = calculateSha256(file);

        if (documentRepository.existsByKnowledgeBaseIdAndFileHash(knowledgeBaseId, fileHash)) {
            log.warn("知识库中已存在相同文件，拒绝上传 knowledgeBaseId={} fileName={} fileHash={}",
                    knowledgeBaseId, file.getOriginalFilename(), fileHash);
            throw new DuplicateDocumentException();
        }

        String storageKey = fileStorage.store(file, format);//存储路径
        log.debug("原文件已保存 storageKey={}", storageKey);

        try {
            Document document = Document.pending(
                    knowledgeBaseId,
                    originalFileName(file),
                    storageKey,
                    format.mediaType(),
                    file.getSize(),
                    fileHash
            );
            DocumentResponse response = toResponse(documentRepository.saveAndFlush(document));
            // 一次上传只在成功这里打一行，把 id、文件名和后续状态放在一起
            log.info("文档上传完成 documentId={} fileName={} parseStatus={}（解析需另调接口）",
                    response.id(), response.originalFileName(), response.parseStatus());
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
        ensureKnowledgeBaseExists(knowledgeBaseId);
        List<DocumentResponse> documents = documentRepository
                .findByKnowledgeBaseIdOrderByCreatedAtDesc(knowledgeBaseId)
                .stream()
                .map(this::toResponse)
                .toList();

        // 读接口会被前端每次切知识库、开抽屉时调用，放 debug 才不会刷屏
        log.debug("查询知识库文档列表 knowledgeBaseId={} 共 {} 条", knowledgeBaseId, documents.size());
        return documents;
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

        // 先 flush 再删物理文件：数据库删失败时，文件必须还在，否则会出现记录还在但文件没了
        documentRepository.delete(document);
        documentRepository.flush();
        log.debug("文档记录已删除，开始清理物理文件 storageKey={}", document.getStorageKey());

        fileStorage.delete(document.getStorageKey());
        log.info("文档删除完成 documentId={}", documentId);
    }

    @Override
    public int deleteAllUnderKnowledgeBase(UUID knowledgeBaseId) {
        List<String> storageKeys = documentRepository.findStorageKeysByKnowledgeBaseId(knowledgeBaseId);
        if (storageKeys.isEmpty()) {
            log.debug("知识库下没有文档需要清理 knowledgeBaseId={}", knowledgeBaseId);
            return 0;
        }

        // 先把数据库的行删掉再删文件：反过来的话数据库失败就会留下有记录但没文件的悬空状态
        documentRepository.deleteAllByKnowledgeBaseId(knowledgeBaseId);

        int deletedFiles = 0;
        for (String storageKey : storageKeys) {
            try {
                fileStorage.delete(storageKey);
                deletedFiles++;
            } catch (RuntimeException exception) {
                // 单个文件删失败不应该阻断其余文件的清理，记下来人工处理即可
                log.warn("清理物理文件失败，需要人工确认 storageKey={}", storageKey, exception);
            }
        }
        // 汇总交调用方（KnowledgeBaseServiceImpl.delete）打，一次删除只出一条 info
        log.debug("知识库文档清理完成 knowledgeBaseId={} 文档数={} 文件数={}",
                knowledgeBaseId, storageKeys.size(), deletedFiles);
        return storageKeys.size();
    }

    //检查知识库是否存在
    private void ensureKnowledgeBaseExists(UUID knowledgeBaseId) {
        if (!knowledgeBaseRepository.existsById(knowledgeBaseId)) {
            // 这是 4xx，异常消息里已经带了 id，交给全局出口记一条 debug 就够了
            throw new KnowledgeBaseNotFoundException(knowledgeBaseId);
        }
    }

    private Document findById(UUID documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    //校验文档：先看扩展名，再按格式核对文件内部特征
    private DocumentFormat validateDocument(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidDocumentException("上传文件不能为空");
        }
        if (file.getSize() > maxFileSizeBytes) {
            log.warn("上传文件超过大小上限 size={} 字节 上限={} 字节", file.getSize(), maxFileSizeBytes);
            throw new InvalidDocumentException("上传文件超过大小上限");
        }
        String fileName = originalFileName(file);
        DocumentFormat format = DocumentFormat.fromFileName(fileName)
                .orElseThrow(() -> new InvalidDocumentException(
                        "只支持 " + DocumentFormat.supportedExtensions() + "，不支持其他格式"));

        if (format == DocumentFormat.PDF) {
            checkPdfMagic(file);
        } else {
            checkOoxmlPart(file, format);
        }
        return format;
    }

    //在前1024个字节里查找%PDF-
    private void checkPdfMagic(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            byte[] header = inputStream.readNBytes(MAGIC_HEADER_SCAN_SIZE);
            for (int index = 0; index <= header.length - PDF_MAGIC.length; index++) {
                if (matchesAt(header, index, PDF_MAGIC)) {
                    return;
                }
            }
        } catch (IOException exception) {
            log.error("读取上传文件内容失败 fileName={}", file.getOriginalFilename(), exception);
            throw new InvalidDocumentException("读取上传文件失败");
        }
        throw new InvalidDocumentException("上传内容不是有效的 PDF 文件");
    }

    /*
     * docx / xlsx 都是 ZIP 容器。只看 ZIP 魔数不够——任何压缩包都能改个后缀传上来，
     * 所以再进压缩包里确认主部件（word/document.xml 或 xl/workbook.xml）存在。
     * 这一步把「上传成功、解析时才炸」提前成了上传即拒绝。
     */
    private void checkOoxmlPart(MultipartFile file, DocumentFormat format) {
        boolean requiredPartFound = false;
        try (InputStream inputStream = file.getInputStream()) {
            byte[] magic = inputStream.readNBytes(ZIP_MAGIC.length);
            if (!matchesAt(magic, 0, ZIP_MAGIC)) {
                throw new InvalidDocumentException("上传内容与文件扩展名不符，不是有效的 " + format.extension() + " 文件");
            }
        } catch (IOException exception) {
            log.error("读取上传文件内容失败 fileName={}", file.getOriginalFilename(), exception);
            throw new InvalidDocumentException("读取上传文件失败");
        }

        try (ZipInputStream zipStream = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zipStream.getNextEntry()) != null) {
                if (format.requiredZipPart().equals(entry.getName())) {
                    requiredPartFound = true;
                    break;
                }
            }
        } catch (IOException exception) {
            log.error("读取 OOXML 压缩包失败 fileName={}", file.getOriginalFilename(), exception);
            throw new InvalidDocumentException("读取上传文件失败");
        }

        if (!requiredPartFound) {
            throw new InvalidDocumentException("上传内容与文件扩展名不符，不是有效的 " + format.extension() + " 文件");
        }
    }

    private static boolean matchesAt(byte[] source, int offset, byte[] expected) {
        if (source.length - offset < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (source[offset + index] != expected[index]) {
                return false;
            }
        }
        return true;
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
                document.getErrorMessage(),
                document.getParsedAt(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }
}
