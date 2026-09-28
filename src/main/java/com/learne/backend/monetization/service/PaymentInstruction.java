package com.learne.backend.monetization.service;

public record PaymentInstruction(
        String bankCode,
        String bankName,
        String accountNumber,
        String accountName,
        Long amountVnd,
        String paymentReference,
        String qrUrl
) {}
