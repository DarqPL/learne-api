package com.learne.backend.shared.api;

import com.learne.backend.ai.grammar.dto.GrammarCheckFailureResponse;
import com.learne.backend.ai.grammar.service.GrammarCheckFailedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(GrammarCheckFailedException.class)
    ResponseEntity<GrammarCheckFailureResponse> handleGrammarCheckFailedException(
            GrammarCheckFailedException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new GrammarCheckFailureResponse(exception.getCheckId(), "FAILED", exception.getMessage()));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception) {
        return ResponseEntity.status(exception.getStatus()).body(new ApiErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> handleValidationException() {
        return ResponseEntity.badRequest().body(new ApiErrorResponse("Invalid request."));
    }
}
