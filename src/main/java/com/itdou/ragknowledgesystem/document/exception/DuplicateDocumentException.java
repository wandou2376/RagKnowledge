package com.itdou.ragknowledgesystem.document.exception;

public class DuplicateDocumentException extends RuntimeException {

    public DuplicateDocumentException() {
        super("An identical document already exists in this knowledge base");
    }
}
