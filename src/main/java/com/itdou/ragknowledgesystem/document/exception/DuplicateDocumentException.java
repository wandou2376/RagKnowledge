package com.itdou.ragknowledgesystem.document.exception;

public class DuplicateDocumentException extends RuntimeException {

    public DuplicateDocumentException() {
        super("同一知识库中已存在内容完全相同的文档");
    }
}
