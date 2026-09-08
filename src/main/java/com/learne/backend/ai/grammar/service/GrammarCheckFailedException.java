package com.learne.backend.ai.grammar.service;

import com.learne.backend.shared.api.ApiException;
import org.springframework.http.HttpStatus;

public class GrammarCheckFailedException extends ApiException {
    private final Long checkId;

    public GrammarCheckFailedException(HttpStatus status, String message, Long checkId) {
        super(status, message);
        this.checkId = checkId;
    }

    public Long getCheckId() {
        return checkId;
    }
}
