package com.learne.backend.shared.api;

public record ApiErrorResponse(String message,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String code) {
    public ApiErrorResponse(String message) { this(message, null); }
}
