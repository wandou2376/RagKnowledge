package com.itdou.ragknowledgesystem.retrieval.exception;

/**
 * 向量化失败：未配置 API Key、调用外部 Embedding 服务出错，或返回的向量不合法。
 *
 * 失败点在外部依赖上，所以 HTTP 层映射成 503 而不是 500，提示调用方这是依赖问题。
 */
public class EmbeddingFailedException extends RuntimeException {

    public EmbeddingFailedException(String message) {
        super(message);
    }

    public EmbeddingFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
