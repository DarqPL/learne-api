package com.learne.backend.monetization.dto;

public record SimulateSepayPaymentRequest(
        String paymentReference,
        Long transferAmountVnd,
        Long sepayTransactionId
) {}
