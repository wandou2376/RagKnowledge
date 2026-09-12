package com.itdou.ragknowledgesystem.document.exception;

/**
 * PDF 解析失败：文件损坏、加密，或提取不到任何文本（多为扫描版 / 图片型 PDF）。
 */
public class DocumentParsingException extends RuntimeException {

    public DocumentParsingException(String message) {
        super(message);
    }

    public DocumentParsingException(String message, Throwable cause) {
        super(message, cause);
    }
}
