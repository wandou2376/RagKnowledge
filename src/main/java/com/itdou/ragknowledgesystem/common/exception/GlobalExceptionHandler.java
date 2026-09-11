package com.itdou.ragknowledgesystem.common.exception;

import com.itdou.ragknowledgesystem.knowledgebase.exception.KnowledgeBaseNotFoundException;
import com.itdou.ragknowledgesystem.document.exception.DocumentNotFoundException;
import com.itdou.ragknowledgesystem.document.exception.DocumentStorageException;
import com.itdou.ragknowledgesystem.document.exception.DuplicateDocumentException;
import com.itdou.ragknowledgesystem.document.exception.InvalidDocumentException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Request validation failed");

        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_ERROR", message, Instant.now()));
    }

    @ExceptionHandler(KnowledgeBaseNotFoundException.class)
    public ResponseEntity<ApiError> handleKnowledgeBaseNotFound(KnowledgeBaseNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("KNOWLEDGE_BASE_NOT_FOUND", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ApiError> handleDocumentNotFound(DocumentNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("DOCUMENT_NOT_FOUND", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler({InvalidDocumentException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<ApiError> handleInvalidDocument(RuntimeException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_DOCUMENT", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(DuplicateDocumentException.class)
    public ResponseEntity<ApiError> handleDuplicateDocument(DuplicateDocumentException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("DUPLICATE_DOCUMENT", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(DocumentStorageException.class)
    public ResponseEntity<ApiError> handleDocumentStorage(DocumentStorageException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("DOCUMENT_STORAGE_ERROR", exception.getMessage(), Instant.now()));
    }
}
