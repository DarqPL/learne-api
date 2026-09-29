package com.learne.backend.monetization.service;

import com.learne.backend.monetization.dto.SepayWebhookPayload;
import com.learne.backend.monetization.dto.SimulateSepayPaymentRequest;
import com.learne.backend.monetization.entity.Order;
import com.learne.backend.monetization.entity.SepayWebhookEvent;
import com.learne.backend.monetization.enums.OrderStatus;
import com.learne.backend.monetization.enums.PaymentProvider;
import com.learne.backend.monetization.enums.SepayWebhookEventStatus;
import com.learne.backend.monetization.service.OrderPaymentSettlementService.SepaySettlementResult;
import com.learne.backend.monetization.repository.OrderRepository;
import com.learne.backend.monetization.repository.SepayWebhookEventRepository;
import com.learne.backend.shared.api.ApiException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class SepayPaymentService {
    private static final int MAX_REFERENCE_ATTEMPTS = 10;
    private static final DateTimeFormatter SEPAY_TRANSACTION_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final SepayPaymentProperties properties;
    private final OrderRepository orderRepository;
    private final SepayWebhookEventRepository eventRepository;
    private final OrderPaymentSettlementService orderPaymentSettlementService;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public SepayPaymentService(SepayPaymentProperties properties,
                               OrderRepository orderRepository,
                               SepayWebhookEventRepository eventRepository,
                               OrderPaymentSettlementService orderPaymentSettlementService,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.orderRepository = orderRepository;
        this.eventRepository = eventRepository;
        this.orderPaymentSettlementService = orderPaymentSettlementService;
        this.objectMapper = objectMapper;
    }

    public SepayPaymentService(SepayPaymentProperties properties, OrderRepository orderRepository) {
        this(properties, orderRepository, null, null, new ObjectMapper());
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

    @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    public void processWebhook(String rawBody, HttpHeaders headers) {
        verifyWebhook(rawBody, headers);
        SepayWebhookPayload payload = parsePayload(rawBody);
        if (eventRepository.findBySepayTransactionId(payload.id()).isPresent()) {
            return;
        }
        SepayWebhookEvent event = newEvent(payload, rawBody);
        try {
            event = eventRepository.saveAndFlush(event);
        } catch (DataIntegrityViolationException duplicate) {
            if (!isDuplicateTransactionViolation(duplicate)) {
                throw duplicate;
            }
            return;
        }
        processTrustedEvent(event, payload);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void simulatePayment(SimulateSepayPaymentRequest request) {
        if (!properties.mockEnabled()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Payment simulation is disabled.");
        }
        if (request == null || isBlank(request.paymentReference()) || request.transferAmountVnd() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid SEPAY simulation request.");
        }
        Long transactionId = request.sepayTransactionId() == null ? generateSimulatedTransactionId() : request.sepayTransactionId();
        SepayWebhookPayload payload = new SepayWebhookPayload(
                transactionId,
                request.paymentReference().trim(),
                request.paymentReference().trim(),
                "in",
                request.transferAmountVnd(),
                request.paymentReference().trim(),
                SEPAY_TRANSACTION_DATE.format(LocalDateTime.now(VIETNAM_ZONE)),
                "SIMULATED_SEPAY",
                properties.accountNumber(),
                null,
                "Simulated SEPAY payment",
                null);
        if (eventRepository.findBySepayTransactionId(payload.id()).isPresent()) {
            return;
        }
        SepayWebhookEvent event = eventRepository.saveAndFlush(newEvent(payload, simulatedRawPayload(payload)));
        processTrustedEvent(event, payload);
        if (event.getStatus() != SepayWebhookEventStatus.PROCESSED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, event.getFailureReason());
        }
    }

    private void verifyWebhook(String rawBody, HttpHeaders headers) {
        if (properties.webhookAuthMode() == SepayPaymentProperties.WebhookAuthMode.NONE) {
            return;
        }
        if (properties.webhookAuthMode() == SepayPaymentProperties.WebhookAuthMode.API_KEY) {
            if (isBlank(properties.apiKey())) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SEPAY webhook API key is not configured.");
            }
            String expected = "Apikey " + properties.apiKey();
            if (!constantTimeEquals(expected, headers.getFirst(HttpHeaders.AUTHORIZATION))) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid SEPAY webhook API key.");
            }
            return;
        }
        if (isBlank(properties.webhookSecret())) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SEPAY webhook secret is not configured.");
        }
        String timestampHeader = headers.getFirst("X-SePay-Timestamp");
        String signatureHeader = headers.getFirst("X-SePay-Signature");
        long timestamp = parseTimestamp(timestampHeader);
        long skewSeconds = Math.abs(Instant.now().getEpochSecond() - timestamp);
        if (skewSeconds > properties.timestampToleranceSeconds()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SEPAY webhook timestamp is outside tolerance.");
        }
        String expectedSignature = "sha256=" + hmacSha256Hex(timestampHeader + "." + rawBody, properties.webhookSecret());
        if (!constantTimeEquals(expectedSignature, signatureHeader)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid SEPAY webhook signature.");
        }
    }

    private SepayWebhookPayload parsePayload(String rawBody) {
        try {
            SepayWebhookPayload payload = objectMapper.readValue(rawBody, SepayWebhookPayload.class);
            if (payload.id() == null || isBlank(payload.transferType()) || payload.transferAmount() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid SEPAY webhook payload.");
            }
            String transferType = payload.transferType().trim().toLowerCase(Locale.ROOT);
            if ((payload.transferAmount() < 0) || (!"in".equals(transferType) && !"out".equals(transferType))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid SEPAY webhook payload.");
            }
            return payload;
        } catch (JacksonException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid SEPAY webhook payload.");
        }
    }

    private SepayWebhookEvent newEvent(SepayWebhookPayload payload, String rawBody) {
        SepayWebhookEvent event = new SepayWebhookEvent();
        event.setSepayTransactionId(payload.id());
        event.setPaymentReference(extractPaymentReference(payload));
        event.setTransferType(payload.transferType().trim().toLowerCase(Locale.ROOT));
        event.setTransferAmountVnd(payload.transferAmount());
        event.setGateway(payload.gateway());
        event.setAccountNumber(payload.accountNumber());
        event.setReferenceCode(payload.referenceCode());
        event.setTransactionDate(parseTransactionDate(payload.transactionDate()));
        event.setStatus(SepayWebhookEventStatus.IGNORED);
        event.setRawPayload(rawBody);
        event.setReceivedAt(Instant.now());
        return event;
    }

    private void processTrustedEvent(SepayWebhookEvent event, SepayWebhookPayload payload) {
        Instant processedAt = Instant.now();
        if (!"in".equalsIgnoreCase(payload.transferType())) {
            finishEvent(event, SepayWebhookEventStatus.IGNORED, "Transfer type is not inbound.", processedAt);
            return;
        }
        String paymentReference = event.getPaymentReference();
        if (isBlank(paymentReference)) {
            finishEvent(event, SepayWebhookEventStatus.IGNORED, "Payment reference is missing.", processedAt);
            return;
        }
        try {
            SepaySettlementResult result = orderPaymentSettlementService.settleSepayPayment(
                    paymentReference,
                    payload.transferAmount(),
                    event.getTransactionDate() == null ? processedAt : event.getTransactionDate());
            event.setOrder(result.order());
            if (result.processed()) {
                finishEvent(event, SepayWebhookEventStatus.PROCESSED, null, Instant.now());
                return;
            }
            SepayWebhookEventStatus status = result.order() == null
                    ? SepayWebhookEventStatus.UNMATCHED
                    : SepayWebhookEventStatus.IGNORED;
            finishEvent(event, status, result.ignoredReason(), Instant.now());
        } catch (ApiException exception) {
            finishEvent(event, SepayWebhookEventStatus.FAILED, exception.getMessage(), Instant.now());
        }
    }

    private void finishEvent(SepayWebhookEvent event, SepayWebhookEventStatus status, String failureReason, Instant processedAt) {
        event.setStatus(status);
        event.setFailureReason(failureReason);
        event.setProcessedAt(processedAt);
        eventRepository.save(event);
    }

    private String extractPaymentReference(SepayWebhookPayload payload) {
        if (!isBlank(payload.code())) {
            return payload.code().trim();
        }
        if (!isBlank(payload.referenceCode()) && orderRepository.existsByPaymentReference(payload.referenceCode().trim())) {
            return payload.referenceCode().trim();
        }
        return extractReferenceFromText(payload.content());
    }

    private String extractReferenceFromText(String text) {
        if (isBlank(text)) {
            return null;
        }
        String prefix = properties.referencePrefix().toUpperCase(Locale.ROOT);
        for (String token : text.toUpperCase(Locale.ROOT).split("[^A-Z0-9]+")) {
            if (token.startsWith(prefix) && token.length() > prefix.length()) {
                return token;
            }
        }
        return null;
    }

    private Instant parseTransactionDate(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, SEPAY_TRANSACTION_DATE).atZone(VIETNAM_ZONE).toInstant();
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private long parseTimestamp(String timestampHeader) {
        if (isBlank(timestampHeader)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Missing SEPAY webhook timestamp.");
        }
        try {
            return Long.parseLong(timestampHeader);
        } catch (NumberFormatException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid SEPAY webhook timestamp.");
        }
    }

    private String hmacSha256Hex(String value, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not verify SEPAY webhook signature.");
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isDuplicateTransactionViolation(DataIntegrityViolationException exception) {
        Throwable cause = exception.getMostSpecificCause();
        String message = cause == null ? exception.getMessage() : cause.getMessage();
        return message != null && message.contains("sepay_webhook_events_transaction_unique");
    }

    private Long generateSimulatedTransactionId() {
        return secureRandom.nextLong(1, Long.MAX_VALUE);
    }

    private String simulatedRawPayload(SepayWebhookPayload payload) {
        return """
                {"id":%d,"code":"%s","content":"%s","transferType":"in","transferAmount":%d,"referenceCode":"%s","transactionDate":"%s","gateway":"SIMULATED_SEPAY","accountNumber":"%s","description":"Simulated SEPAY payment"}
                """.formatted(
                payload.id(),
                payload.code(),
                payload.content(),
                payload.transferAmount(),
                payload.referenceCode(),
                payload.transactionDate(),
                properties.accountNumber());
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
