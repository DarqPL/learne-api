package com.learne.backend.monetization.service;

import com.learne.backend.jobs.model.JobType;
import com.learne.backend.mail.model.MailPayload;
import com.learne.backend.mail.model.MailTemplate;
import com.learne.backend.mail.service.MailJobProducer;
import com.learne.backend.monetization.entity.Order;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Materializes receipt data while the paid order and its items are still in the settlement transaction. */
@Service
public class PaymentConfirmationMailService {
    private final MailJobProducer producer;

    public PaymentConfirmationMailService(MailJobProducer producer) { this.producer = producer; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Order order) {
        String description = order.getItems().stream().map(item -> item.getItemNameSnapshot()
                + (item.getPlanCode() == null ? "" : " (" + item.getPlanCode().name() + ")")
                + " × " + item.getQuantity()).collect(Collectors.joining("; "));
        var buyer = order.getUser();
        var payload = new MailPayload(buyer.getId(), buyer.getEmail().trim().toLowerCase(Locale.ROOT),
                buyer.getFullName(), MailTemplate.PAYMENT_CONFIRMATION, null, null, null, null, null, null,
                order.getId(), Map.of("amountVnd", Long.toString(order.getFinalAmountVnd()),
                        "itemDescription", description, "paidAt", order.getPaidAt().toString()));
        producer.enqueue(JobType.PAYMENT_CONFIRMATION_MAIL, "payment-confirmation:" + order.getId(), payload, null);
    }
}
