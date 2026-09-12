package com.itdou.ragknowledgesystem.document.exception;

import java.util.UUID;

public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(UUID id) {
        super("文档不存在: " + id);
    }
}
