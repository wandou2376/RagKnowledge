package com.itdou.ragknowledgesystem.document.entity;

import java.util.Locale;
import java.util.Optional;

/**
 * 支持上传和解析的文档格式。
 *
 * <p>{@code requiredZipPart} 只对 OOXML（docx / xlsx）有意义：这两种格式本质上是 ZIP，
 * 校验压缩包里有没有对应的主部件，能挡住「把别的文件改个后缀传上来」，
 * 比只看扩展名可靠。PDF 不看这一列，改用文件头的 {@code %PDF-} 魔数。
 *
 * <p>刻意不支持 .doc / .xls：它们是 OLE2 复合文档，要用 poi-scratchpad 单独解析，
 * 而且现在几乎没人上传这两种格式。上传时会被明确拒绝而不是解析到一半才失败。
 */
public enum DocumentFormat {

    PDF(".pdf", "application/pdf", null),

    DOCX(".docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "word/document.xml"),

    XLSX(".xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "xl/workbook.xml");

    private final String extension;
    private final String mediaType;
    private final String requiredZipPart;

    DocumentFormat(String extension, String mediaType, String requiredZipPart) {
        this.extension = extension;
        this.mediaType = mediaType;
        this.requiredZipPart = requiredZipPart;
    }

    public String extension() {
        return extension;
    }

    public String mediaType() {
        return mediaType;
    }

    /**
     * OOXML 压缩包里必须存在的部件名；PDF 返回 null。
     */
    public String requiredZipPart() {
        return requiredZipPart;
    }

    public static Optional<DocumentFormat> fromFileName(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        String lowerCaseName = fileName.toLowerCase(Locale.ROOT);
        for (DocumentFormat format : values()) {
            if (lowerCaseName.endsWith(format.extension)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    public static Optional<DocumentFormat> fromMediaType(String mediaType) {
        if (mediaType == null) {
            return Optional.empty();
        }
        for (DocumentFormat format : values()) {
            if (format.mediaType.equalsIgnoreCase(mediaType)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    /**
     * 给调用方看的格式清单，用于错误提示。
     */
    public static String supportedExtensions() {
        return "PDF（.pdf）、Word（.docx）、Excel（.xlsx）";
    }
}
