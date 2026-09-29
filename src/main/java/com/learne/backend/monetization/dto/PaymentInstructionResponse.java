package com.learne.backend.monetization.dto;

import com.learne.backend.monetization.service.PaymentInstruction;

public record PaymentInstructionResponse(
        String bankCode,
        String bankName,
        String accountNumber,
        String accountName,
        Long amountVnd,
        String paymentReference,
        String qrUrl
) {
    public static PaymentInstructionResponse from(PaymentInstruction instruction) {
        if (instruction == null) return null;
        return new PaymentInstructionResponse(
                instruction.bankCode(),
                instruction.bankName(),
                instruction.accountNumber(),
                instruction.accountName(),
                instruction.amountVnd(),
                instruction.paymentReference(),
                instruction.qrUrl());
    }
}
