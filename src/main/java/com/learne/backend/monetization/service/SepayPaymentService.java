package com.learne.backend.monetization.service;

import com.learne.backend.monetization.entity.Order;
import com.learne.backend.monetization.enums.OrderStatus;
import com.learne.backend.monetization.enums.PaymentProvider;
import com.learne.backend.monetization.repository.OrderRepository;
import com.learne.backend.shared.api.ApiException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SepayPaymentService {
    private static final int MAX_REFERENCE_ATTEMPTS = 10;

    private final SepayPaymentProperties properties;
    private final OrderRepository orderRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public SepayPaymentService(SepayPaymentProperties properties, OrderRepository orderRepository) {
        this.properties = properties;
        this.orderRepository = orderRepository;
    }

    public boolean isEnabled() {
        return properties.enabled();
    }

    public String generatePaymentReference() {
        byte[] bytes = new byte[9];
        secureRandom.nextBytes(bytes);
        return properties.referencePrefix().toUpperCase(Locale.ROOT) + HexFormat.of().withUpperCase().formatHex(bytes);
    }

    public String generateUniquePaymentReference() {
        if (orderRepository == null) {
            return generatePaymentReference();
        }
        for (int attempt = 0; attempt < MAX_REFERENCE_ATTEMPTS; attempt++) {
            String reference = generatePaymentReference();
            if (!orderRepository.existsByPaymentReference(reference)) {
                return reference;
            }
        }
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not generate unique payment reference.");
    }

    public PaymentInstruction buildInstruction(String paymentReference, Long amountVnd) {
        validatePaymentConfig();
        String query = "acc=" + encode(properties.accountNumber())
                + "&bank=" + encode(properties.bankCode())
                + "&amount=" + amountVnd
                + "&des=" + encode(paymentReference);
        return new PaymentInstruction(
                properties.bankCode(),
                properties.bankName(),
                properties.accountNumber(),
                properties.accountName(),
                amountVnd,
                paymentReference,
                properties.qrBaseUrl() + "?" + query);
    }

    public PaymentInstruction instructionFor(Order order) {
        if (order == null
                || order.getPaymentProvider() != PaymentProvider.SEPAY
                || order.getStatus() != OrderStatus.PENDING_PAYMENT
                || isBlank(order.getPaymentReference())) {
            return null;
        }
        return buildInstruction(order.getPaymentReference(), order.getFinalAmountVnd());
    }

    public void validatePaymentConfig() {
        if (!properties.enabled()) {
            return;
        }
        if (isBlank(properties.bankCode())
                || isBlank(properties.bankName())
                || isBlank(properties.accountNumber())
                || isBlank(properties.accountName())) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SEPAY payment configuration is incomplete.");
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
