package com.itdou.ragknowledgesystem.chat.exception;

/**
 * 生成回答失败：聊天模型不可用、超时，或返回了不符合预期的内容。
 *
 * 失败点在外部服务上，HTTP 层映射成 503，与 500 服务端自身故障区分开。
 */
public class AnswerGenerationException extends RuntimeException {

    public AnswerGenerationException(String message) {
        super(message);
    }

    public AnswerGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
