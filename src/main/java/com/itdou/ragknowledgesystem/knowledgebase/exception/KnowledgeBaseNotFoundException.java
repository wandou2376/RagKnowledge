package com.itdou.ragknowledgesystem.knowledgebase.exception;

import java.util.UUID;

public class KnowledgeBaseNotFoundException extends RuntimeException {

    public KnowledgeBaseNotFoundException(UUID id) {
        super("知识库不存在: " + id);
    }
}
