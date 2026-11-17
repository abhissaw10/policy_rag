package com.example.policyrag.api;

import com.example.policyrag.service.PolicyValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadableJson(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "Provide a valid JSON request with employeeId and prompt."));
    }

    @ExceptionHandler(PolicyValidationException.class)
    ResponseEntity<ApiError> handleValidation(PolicyValidationException exception) {
        return ResponseEntity.badRequest().body(new ApiError(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiError("FILE_TOO_LARGE", "File size must not exceed 25 KB"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("MISSING_FIELD", exception.getParameterName() + " is required"));
    }

    @ExceptionHandler(PolicyProcessingException.class)
    ResponseEntity<ApiError> handleProcessing(PolicyProcessingException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("PROCESSING_UNAVAILABLE", exception.getMessage()));
    }

    public static class PolicyProcessingException extends RuntimeException {
        public PolicyProcessingException(String message, Throwable cause) {
            super(message, cause);
        }

        public PolicyProcessingException(String message) {
            super(message);
        }
    }
}
