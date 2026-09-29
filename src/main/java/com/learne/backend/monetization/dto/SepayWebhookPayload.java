package com.learne.backend.monetization.dto;

public record SepayWebhookPayload(
        Long id,
        String code,
        String content,
        String transferType,
        Long transferAmount,
        String referenceCode,
        String transactionDate,
        String gateway,
        String accountNumber,
        String subAccount,
        String description,
        Long accumulated
) {}
