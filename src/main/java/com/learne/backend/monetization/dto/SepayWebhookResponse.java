package com.learne.backend.monetization.dto;

public record SepayWebhookResponse(boolean success) {
    public static SepayWebhookResponse ok() {
        return new SepayWebhookResponse(true);
    }
}
