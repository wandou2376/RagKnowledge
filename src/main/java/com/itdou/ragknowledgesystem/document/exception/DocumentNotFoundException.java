package com.itdou.ragknowledgesystem.document.exception;

import java.util.UUID;

public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(UUID id) {
        super("Document does not exist: " + id);
    }
}
