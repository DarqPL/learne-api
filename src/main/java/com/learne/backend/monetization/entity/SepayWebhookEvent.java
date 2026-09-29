package com.learne.backend.monetization.entity;

import com.learne.backend.monetization.enums.SepayWebhookEventStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "sepay_webhook_events")
public class SepayWebhookEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sepay_transaction_id", nullable = false)
    private Long sepayTransactionId;

    @Column(name = "payment_reference", length = 120)
    private String paymentReference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(name = "transfer_type", nullable = false, length = 16)
    private String transferType;

    @Column(name = "transfer_amount_vnd", nullable = false)
    private Long transferAmountVnd;

    @Column(name = "gateway", length = 120)
    private String gateway;

    @Column(name = "account_number", length = 64)
    private String accountNumber;

    @Column(name = "reference_code", length = 120)
    private String referenceCode;

    @Column(name = "transaction_date")
    private Instant transactionDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private SepayWebhookEventStatus status;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "raw_payload", nullable = false, columnDefinition = "TEXT")
    private String rawPayload;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    @Column(name = "processed_at")
    private Instant processedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSepayTransactionId() { return sepayTransactionId; }
    public void setSepayTransactionId(Long sepayTransactionId) { this.sepayTransactionId = sepayTransactionId; }
    public String getPaymentReference() { return paymentReference; }
    public void setPaymentReference(String paymentReference) { this.paymentReference = paymentReference; }
    public Order getOrder() { return order; }
    public void setOrder(Order order) { this.order = order; }
    public String getTransferType() { return transferType; }
    public void setTransferType(String transferType) { this.transferType = transferType; }
    public Long getTransferAmountVnd() { return transferAmountVnd; }
    public void setTransferAmountVnd(Long transferAmountVnd) { this.transferAmountVnd = transferAmountVnd; }
    public String getGateway() { return gateway; }
    public void setGateway(String gateway) { this.gateway = gateway; }
    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
    public String getReferenceCode() { return referenceCode; }
    public void setReferenceCode(String referenceCode) { this.referenceCode = referenceCode; }
    public Instant getTransactionDate() { return transactionDate; }
    public void setTransactionDate(Instant transactionDate) { this.transactionDate = transactionDate; }
    public SepayWebhookEventStatus getStatus() { return status; }
    public void setStatus(SepayWebhookEventStatus status) { this.status = status; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public String getRawPayload() { return rawPayload; }
    public void setRawPayload(String rawPayload) { this.rawPayload = rawPayload; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
