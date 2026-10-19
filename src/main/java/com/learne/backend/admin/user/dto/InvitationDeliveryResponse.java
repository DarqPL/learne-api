package com.learne.backend.admin.user.dto;

import java.time.Instant;

public record InvitationDeliveryResponse(String status, Instant requestedAt, Instant completedAt,
        String failureCode, boolean canResend) {}
