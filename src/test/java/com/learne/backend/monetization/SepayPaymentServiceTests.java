package com.learne.backend.monetization;

import static org.assertj.core.api.Assertions.assertThat;

import com.learne.backend.monetization.entity.Order;
import com.learne.backend.monetization.enums.OrderStatus;
import com.learne.backend.monetization.enums.PaymentProvider;
import com.learne.backend.monetization.service.SepayPaymentProperties;
import com.learne.backend.monetization.service.SepayPaymentService;
import org.junit.jupiter.api.Test;

class SepayPaymentServiceTests {
    @Test
    void generatedPaymentReferenceUsesConfiguredPrefixAndIsHardToGuess() {
        SepayPaymentService service = service();

        String reference = service.generatePaymentReference();

        assertThat(reference).startsWith("LE");
        assertThat(reference).matches("LE[A-Z0-9]{12,32}");
    }

    @Test
    void buildsVietQrUrlWithEncodedReference() {
        SepayPaymentService service = service();

        var instruction = service.buildInstruction("LEABC123XYZ", 160000L);

        assertThat(instruction.qrUrl()).contains("https://vietqr.app/img?");
        assertThat(instruction.qrUrl()).contains("acc=0010000000355");
        assertThat(instruction.qrUrl()).contains("bank=Vietcombank");
        assertThat(instruction.qrUrl()).contains("amount=160000");
        assertThat(instruction.qrUrl()).contains("des=LEABC123XYZ");
        assertThat(instruction.accountNumber()).isEqualTo("0010000000355");
    }

    @Test
    void disabledByDefault() {
        SepayPaymentProperties properties = new SepayPaymentProperties(
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0);

        assertThat(new SepayPaymentService(properties, null).isEnabled()).isFalse();
    }

    @Test
    void instructionForPendingSepayOrderWithReferenceReturnsInstruction() {
        Order order = order(PaymentProvider.SEPAY, OrderStatus.PENDING_PAYMENT, "LEABC123XYZ");

        var instruction = service().instructionFor(order);

        assertThat(instruction).isNotNull();
        assertThat(instruction.paymentReference()).isEqualTo("LEABC123XYZ");
        assertThat(instruction.amountVnd()).isEqualTo(160000L);
    }

    @Test
    void instructionForInternalOrderReturnsNull() {
        Order order = order(PaymentProvider.INTERNAL, OrderStatus.PENDING_PAYMENT, "LEABC123XYZ");

        assertThat(service().instructionFor(order)).isNull();
    }

    @Test
    void instructionForNonPendingSepayOrderReturnsNull() {
        assertThat(service().instructionFor(order(PaymentProvider.SEPAY, OrderStatus.PAID, "LEABC123XYZ"))).isNull();
        assertThat(service().instructionFor(order(PaymentProvider.SEPAY, OrderStatus.EXPIRED, "LEABC123XYZ"))).isNull();
        assertThat(service().instructionFor(order(PaymentProvider.SEPAY, OrderStatus.CANCELLED, "LEABC123XYZ"))).isNull();
        assertThat(service().instructionFor(order(PaymentProvider.SEPAY, OrderStatus.REFUNDED, "LEABC123XYZ"))).isNull();
    }

    @Test
    void instructionForSepayOrderWithoutReferenceReturnsNull() {
        assertThat(service().instructionFor(order(PaymentProvider.SEPAY, OrderStatus.PENDING_PAYMENT, null))).isNull();
        assertThat(service().instructionFor(order(PaymentProvider.SEPAY, OrderStatus.PENDING_PAYMENT, ""))).isNull();
        assertThat(service().instructionFor(order(PaymentProvider.SEPAY, OrderStatus.PENDING_PAYMENT, "   "))).isNull();
    }

    private SepayPaymentService service() {
        SepayPaymentProperties properties = new SepayPaymentProperties(
                true,
                false,
                SepayPaymentProperties.WebhookAuthMode.HMAC,
                "secret",
                null,
                "Vietcombank",
                "Vietcombank",
                "0010000000355",
                "CONG TY LEARNE",
                "https://vietqr.app/img",
                "LE",
                300);
        return new SepayPaymentService(properties, null);
    }

    private Order order(PaymentProvider paymentProvider, OrderStatus status, String paymentReference) {
        Order order = new Order();
        order.setPaymentProvider(paymentProvider);
        order.setStatus(status);
        order.setPaymentReference(paymentReference);
        order.setFinalAmountVnd(160000L);
        return order;
    }
}
