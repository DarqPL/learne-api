package com.learne.backend.mail;

import static org.assertj.core.api.Assertions.*;
import com.learne.backend.mail.model.*;
import com.learne.backend.mail.service.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PaymentConfirmationMailServiceTests {
    @Test void facadeRequiresTransactionAndOnlyPassesScalarSnapshotWithoutSecret() throws Exception {
        var method = com.learne.backend.monetization.service.PaymentConfirmationMailService.class
                .getMethod("enqueue", com.learne.backend.monetization.entity.Order.class);
        assertThat(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class).propagation())
                .isEqualTo(org.springframework.transaction.annotation.Propagation.MANDATORY);
        var producer = org.mockito.Mockito.mock(MailJobProducer.class);
        var facade = new com.learne.backend.monetization.service.PaymentConfirmationMailService(producer);
        var order = new com.learne.backend.monetization.entity.Order();
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", 13L);
        var buyer = new com.learne.backend.domain.auth.User();
        org.springframework.test.util.ReflectionTestUtils.setField(buyer, "id", 7L);
        buyer.setEmail(" Student@Example.Test "); buyer.setFullName("Buyer");
        order.setUser(buyer); order.setFinalAmountVnd(0L); order.setPaidAt(java.time.Instant.parse("2026-10-19T00:00:00Z"));
        var item = new com.learne.backend.monetization.entity.OrderItem();
        item.setItemNameSnapshot("N".repeat(255)); order.addItem(item);
        facade.enqueue(order);
        var captor = org.mockito.ArgumentCaptor.forClass(MailPayload.class);
        org.mockito.Mockito.verify(producer).enqueue(org.mockito.ArgumentMatchers.eq(MailTemplate.PAYMENT_CONFIRMATION.jobType()),
                org.mockito.ArgumentMatchers.eq("payment-confirmation:13"), captor.capture(), org.mockito.ArgumentMatchers.isNull());
        assertThat(captor.getValue().recipientEmail()).isEqualTo("student@example.test");
        assertThat(captor.getValue().values().get("itemDescription")).contains("N".repeat(255));
        assertThat(captor.getValue().generation()).isNull(); assertThat(captor.getValue().expiresAt()).isNull();
        org.mockito.Mockito.verifyNoMoreInteractions(producer);
    }
    @Test void canonicalNonnegativeLongAmountsRoundTripAndRender() {
        var codec = new MailPayloadCodec(JsonMapper.builder().build());
        for (String amount : new String[]{"0", "1000000000000000", Long.toString(Long.MAX_VALUE)}) {
            var p = payload(amount);
            assertThat(codec.decode(p.template().jobType(), codec.encode(codec.canonicalize(p.template().jobType(), p)))).isEqualTo(p);
        }
        for (String amount : new String[]{"-1", "00", "01", "+1", "9223372036854775808"}) {
            assertThatThrownBy(() -> codec.canonicalize(MailTemplate.PAYMENT_CONFIRMATION.jobType(), payload(amount))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> codec.decode(MailTemplate.PAYMENT_CONFIRMATION.jobType(), codec.encode(payload(amount))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
    static MailPayload payload(String amount) {
        return new MailPayload(7, "student@example.test", "Buyer", MailTemplate.PAYMENT_CONFIRMATION,
                null, null, null, null, null, null, 13L,
                Map.of("amountVnd", amount, "itemDescription", "Historical title", "paidAt", "2026-10-19T00:00:00Z"));
    }
}
