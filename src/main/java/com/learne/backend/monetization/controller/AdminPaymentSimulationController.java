package com.learne.backend.monetization.controller;

import com.learne.backend.monetization.dto.SepayWebhookResponse;
import com.learne.backend.monetization.dto.SimulateSepayPaymentRequest;
import com.learne.backend.monetization.service.SepayPaymentService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/payments/sepay")
class AdminPaymentSimulationController {
    private final SepayPaymentService sepayPaymentService;

    AdminPaymentSimulationController(SepayPaymentService sepayPaymentService) {
        this.sepayPaymentService = sepayPaymentService;
    }

    @PostMapping("/simulate")
    SepayWebhookResponse simulate(@RequestBody SimulateSepayPaymentRequest request) {
        sepayPaymentService.simulatePayment(request);
        return SepayWebhookResponse.ok();
    }
}
