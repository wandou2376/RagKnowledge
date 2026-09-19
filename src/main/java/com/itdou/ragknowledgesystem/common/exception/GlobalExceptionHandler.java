package com.itdou.ragknowledgesystem.common.exception;

import com.itdou.ragknowledgesystem.common.response.Result;
import com.itdou.ragknowledgesystem.document.exception.DocumentNotFoundException;
import com.itdou.ragknowledgesystem.document.exception.DocumentParsingException;
import com.itdou.ragknowledgesystem.document.exception.DocumentStorageException;
import com.itdou.ragknowledgesystem.document.exception.DuplicateDocumentException;
import com.itdou.ragknowledgesystem.document.exception.InvalidDocumentException;
import com.itdou.ragknowledgesystem.chat.exception.AnswerGenerationException;
import com.itdou.ragknowledgesystem.knowledgebase.exception.KnowledgeBaseNotFoundException;
import com.itdou.ragknowledgesystem.retrieval.exception.EmbeddingFailedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常出口。
 *
 * 全部错误响应都统一成 Result 信封，同时保留真实的 HTTP 状态码。
 * 注意不要在这里处理 actuator 的端点：actuator 走 WebMvcEndpointHandlerMapping，
 * 本来就不在 @ControllerAdvice 的作用范围内。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("请求参数校验失败");

        return failure(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    @ExceptionHandler(KnowledgeBaseNotFoundException.class)
    public ResponseEntity<Result<Void>> handleKnowledgeBaseNotFound(KnowledgeBaseNotFoundException exception) {
        return failure(HttpStatus.NOT_FOUND, "KNOWLEDGE_BASE_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<Result<Void>> handleDocumentNotFound(DocumentNotFoundException exception) {
        return failure(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler({InvalidDocumentException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<Result<Void>> handleInvalidDocument(RuntimeException exception) {
        return failure(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT", exception.getMessage());
    }

    @ExceptionHandler(DuplicateDocumentException.class)
    public ResponseEntity<Result<Void>> handleDuplicateDocument(DuplicateDocumentException exception) {
        return failure(HttpStatus.CONFLICT, "DUPLICATE_DOCUMENT", exception.getMessage());
    }

    @ExceptionHandler(DocumentStorageException.class)
    public ResponseEntity<Result<Void>> handleDocumentStorage(DocumentStorageException exception) {
        return failure(HttpStatus.INTERNAL_SERVER_ERROR, "DOCUMENT_STORAGE_ERROR", exception.getMessage());
    }

    // 文件能读到但内容解析不了（损坏、加密、扫描版无文本），属于请求内容问题
    @ExceptionHandler(DocumentParsingException.class)
    public ResponseEntity<Result<Void>> handleDocumentParsing(DocumentParsingException exception) {
        return failure(HttpStatus.UNPROCESSABLE_ENTITY, "DOCUMENT_PARSING_FAILED", exception.getMessage());
    }

    /*
     * 唯一约束冲突，例如并发上传同一个文件、或并发解析同一个文档。
     * 不加这个处理会返回 500，调用方看不出是数据冲突。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Result<Void>> handleDataIntegrityViolation(DataIntegrityViolationException exception) {
        log.warn("数据库约束冲突", exception);
        return failure(HttpStatus.CONFLICT, "DATA_INTEGRITY_VIOLATION", "数据冲突，请确认是否存在重复内容或并发操作");
    }

    /*
     * 向量化失败。失败点在外部 Embedding 服务或配置上，用 503 而不是 500，
     * 明确告诉调用方这是依赖问题（可能是没配 Key，也可能是对方服务不可用）。
     */
    @ExceptionHandler(EmbeddingFailedException.class)
    public ResponseEntity<Result<Void>> handleEmbeddingFailed(EmbeddingFailedException exception) {
        log.error("向量化失败", exception);
        return failure(HttpStatus.SERVICE_UNAVAILABLE, "EMBEDDING_FAILED", exception.getMessage());
    }

    /*
     * 生成回答失败。和向量化一样，失败点在外部服务上，用 503 而不是 500。
     */
    @ExceptionHandler(AnswerGenerationException.class)
    public ResponseEntity<Result<Void>> handleAnswerGenerationFailed(AnswerGenerationException exception) {
        log.error("生成回答失败", exception);
        return failure(HttpStatus.SERVICE_UNAVAILABLE, "ANSWER_GENERATION_FAILED", exception.getMessage());
    }

    /*
     * 下面几个是 Spring MVC 自己会抛的异常。不显式处理的话它们会走默认错误体
     * （{timestamp, status, error, path}），统一信封就出现缺口了。
     */

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY", "请求体格式错误，无法解析为合法 JSON");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return failure(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER_TYPE",
                "请求参数类型不正确: " + exception.getName());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        return failure(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "不支持的请求方法: " + exception.getMethod());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException exception) {
        return failure(HttpStatus.NOT_FOUND, "ENDPOINT_NOT_FOUND", "请求的接口不存在");
    }

    /**
     * 兜底处理，必须放在最后便于阅读（Spring 按「最具体的异常类型」匹配，与声明顺序无关）。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception exception) {
        /*
         * Spring 内置的 4xx（缺少请求参数或 multipart 部件、媒体类型不支持等）都实现了 ErrorResponse。
         * 如果一律压成 500，会把「调用方自己发错请求」伪装成服务端故障，所以这类保留原本的状态码。
         */
        if (exception instanceof ErrorResponse errorResponse) {
            // 这类是 Spring 自己抛的 4xx，状态码和日志由 failure() 统一处理
            return failure(errorResponse.getStatusCode(), "REQUEST_REJECTED", exception.getMessage());
        }

        // 未预期的异常必须留完整堆栈，否则 500 会变成静默失败
        log.error("未预期的服务端异常", exception);
        return failure(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务器内部错误，请稍后重试");
    }

    /*
     * 所有错误响应的唯一出口，异常日志集中在这里打，避免每个 handler 各记一遍。
     * 4xx 是调用方的问题，DEBUG 足够；5xx 说明服务端有故障，用 WARN 让它显眼。
     * 注意这里拿不到异常对象，需要堆栈的 handler 自己另外记。
     */
    private static ResponseEntity<Result<Void>> failure(HttpStatusCode status, String code, String message) {
        if (status.is5xxServerError()) {
            log.warn("请求处理失败 status={} code={} message={}", status.value(), code, message);
        } else {
            log.debug("请求被拒绝 status={} code={} message={}", status.value(), code, message);
        }
        return ResponseEntity.status(status).body(Result.<Void>failure(code, message));
    }
}
