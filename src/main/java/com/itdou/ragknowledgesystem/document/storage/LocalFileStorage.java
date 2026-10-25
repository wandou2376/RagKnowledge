package com.itdou.ragknowledgesystem.document.storage;

import com.itdou.ragknowledgesystem.document.entity.DocumentFormat;
import com.itdou.ragknowledgesystem.document.exception.DocumentStorageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
@Slf4j
public class LocalFileStorage implements FileStorage {

    private static final DateTimeFormatter DATE_PATH_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final Path rootPath;

    public LocalFileStorage(LocalStorageProperties properties) {
        this.rootPath = properties.localPath().toAbsolutePath().normalize();
        log.info("本地文件存储根目录 {}", rootPath);
    }

    @Override
    public String store(MultipartFile file, DocumentFormat format) {
        String storageKey = LocalDate.now().format(DATE_PATH_FORMAT) + "/" + UUID.randomUUID() + format.extension();
        Path targetPath = resolve(storageKey);

        try {
            Files.createDirectories(targetPath.getParent());
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            log.debug("上传文件写入完成 storageKey={} 大小={} 字节", storageKey, Files.size(targetPath));
            return storageKey;
        } catch (IOException exception) {
            log.error("上传文件写入失败 storageKey={} 目标路径={}", storageKey, targetPath, exception);
            throw new DocumentStorageException("保存上传文件失败", exception);
        }
    }

    @Override
    public void delete(String storageKey) {
        Path path = resolve(storageKey);
        try {
            boolean removed = Files.deleteIfExists(path);
            log.debug("清理物理文件 storageKey={} 是否真的删除了={}", storageKey, removed);
        } catch (IOException exception) {
            log.error("清理物理文件失败 storageKey={} 路径={}", storageKey, path, exception);
            throw new DocumentStorageException("删除已存文件失败", exception);
        }
    }

    @Override
    public Path loadPath(String storageKey) {
        Path path = resolve(storageKey);
        if (!Files.isRegularFile(path)) {
            log.error("待解析的物理文件不存在 storageKey={} 期望路径={}", storageKey, path);
            throw new DocumentStorageException("已存文件不存在: " + storageKey, null);
        }
        return path;
    }

    // 把 storageKey 拼成绝对路径；同时挡住越出存储根目录的路径穿越
    private Path resolve(String storageKey) {
        Path resolvedPath = rootPath.resolve(storageKey).normalize();
        if (!resolvedPath.startsWith(rootPath)) {
            // 解析结果越出存储根目录，说明 storageKey 被篡改过（路径穿越）
            log.error("非法的 storageKey，解析结果越出存储根目录 storageKey={} 解析结果={}", storageKey, resolvedPath);
            throw new DocumentStorageException("storageKey 不合法", null);
        }
        return resolvedPath;
    }
}
