package com.learne.backend.monetization.controller;

import com.learne.backend.monetization.dto.SepayWebhookResponse;
import com.learne.backend.monetization.service.SepayPaymentService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhooks/sepay")
class SepayWebhookController {
    private final SepayPaymentService sepayPaymentService;

    SepayWebhookController(SepayPaymentService sepayPaymentService) {
        this.sepayPaymentService = sepayPaymentService;
    }

    @PostMapping
    SepayWebhookResponse receive(@RequestBody String rawBody, @RequestHeader HttpHeaders headers) {
        sepayPaymentService.processWebhook(rawBody, headers);
        return SepayWebhookResponse.ok();
    }
}
