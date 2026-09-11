package com.itdou.ragknowledgesystem.knowledgebase.exception;

import java.util.UUID;

public class KnowledgeBaseNotFoundException extends RuntimeException {

    public KnowledgeBaseNotFoundException(UUID id) {
        super("Knowledge base does not exist: " + id);
    }
}
