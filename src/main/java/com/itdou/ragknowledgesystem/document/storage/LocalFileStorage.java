package com.itdou.ragknowledgesystem.document.storage;

import com.itdou.ragknowledgesystem.document.exception.DocumentStorageException;
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
public class LocalFileStorage implements FileStorage {

    private static final DateTimeFormatter DATE_PATH_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final Path rootPath;

    public LocalFileStorage(LocalStorageProperties properties) {
        this.rootPath = properties.localPath().toAbsolutePath().normalize();
    }

    @Override
    public String storePdf(MultipartFile file) {
        String storageKey = LocalDate.now().format(DATE_PATH_FORMAT) + "/" + UUID.randomUUID() + ".pdf";
        Path targetPath = resolve(storageKey);

        try {
            Files.createDirectories(targetPath.getParent());
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            return storageKey;
        } catch (IOException exception) {
            throw new DocumentStorageException("Failed to save uploaded file", exception);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException exception) {
            throw new DocumentStorageException("Failed to delete stored file", exception);
        }
    }

    private Path resolve(String storageKey) {
        Path resolvedPath = rootPath.resolve(storageKey).normalize();
        if (!resolvedPath.startsWith(rootPath)) {
            throw new DocumentStorageException("Invalid storage key", null);
        }
        return resolvedPath;
    }
}
