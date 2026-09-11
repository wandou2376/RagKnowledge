package com.itdou.ragknowledgesystem.document.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "app.storage")
public record LocalStorageProperties(
        @DefaultValue("storage/documents") Path localPath,
        @DefaultValue("52428800") long maxFileSizeBytes
) {
}
