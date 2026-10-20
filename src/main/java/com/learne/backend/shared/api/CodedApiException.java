package com.learne.backend.shared.api;

import org.springframework.http.HttpStatus;

public class CodedApiException extends ApiException {
    private final String code;

    public CodedApiException(HttpStatus status, String message, String code) {
        super(status, message);
        this.code = code;
    }

    public String getCode() { return code; }
}
