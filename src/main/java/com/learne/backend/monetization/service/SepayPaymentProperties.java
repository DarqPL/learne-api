package com.learne.backend.monetization.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.payments.sepay")
public record SepayPaymentProperties(
        boolean enabled,
        boolean mockEnabled,
        WebhookAuthMode webhookAuthMode,
        String webhookSecret,
        String apiKey,
        String bankCode,
        String bankName,
        String accountNumber,
        String accountName,
        String qrBaseUrl,
        String referencePrefix,
        long timestampToleranceSeconds
) {
    public enum WebhookAuthMode { HMAC, API_KEY, NONE }

    public SepayPaymentProperties {
        if (webhookAuthMode == null) webhookAuthMode = WebhookAuthMode.HMAC;
        if (qrBaseUrl == null || qrBaseUrl.isBlank()) qrBaseUrl = "https://vietqr.app/img";
        if (referencePrefix == null || referencePrefix.isBlank()) referencePrefix = "LE";
        if (timestampToleranceSeconds <= 0) timestampToleranceSeconds = 300;
    }
}
