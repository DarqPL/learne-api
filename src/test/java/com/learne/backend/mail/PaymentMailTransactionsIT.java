package com.learne.backend.mail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.*;
import com.learne.backend.mail.service.*;
import com.learne.backend.monetization.service.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Import(MailDeliveryIT.TransportFixture.class)
@TestPropertySource(properties = {
        "app.job-crypto.active-key-id=mail-test",
        "app.job-crypto.keys-json={\"mail-test\":\"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=\"}",
        "app.mail.frontend-base-url=https://learne.test", "app.payments.sepay.webhook-auth-mode=NONE",
        "app.payments.sepay.mock-enabled=true"})
class PaymentMailTransactionsIT extends MailPostgresTestSupport {
    @Autowired OrderPaymentSettlementService settlement;
    @Autowired SepayPaymentService sepay;
    @Autowired CheckoutService checkout;
    @Autowired AdminOrderService adminOrders;
    @Autowired PaymentConfirmationMailService paymentMail;
    @Autowired com.learne.backend.domain.auth.UserRepository users;
    @Autowired MailPayloadCodec codec;
    @Autowired JobRegistry registry;
    @Autowired MailDeliveryIT.FakeTransport transport;
    @MockitoSpyBean JobStore jobStore;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @MockitoSpyBean com.learne.backend.cache.CacheInvalidationService caches;
    final Instant paidAt = Instant.parse("2026-10-19T00:00:00Z");

    void seed(boolean plan, long amount) {
        jdbc.update("insert into users(id,email,password_hash,full_name,role) values(7,'student@example.test','hash','Buyer','STUDENT'),(8,'teacher@example.test','hash','Teacher','TEACHER')");
        jdbc.update("insert into courses(id,teacher_id,name,type,level,status) values(11,8,'Mutable course','COMPREHENSIVE','A1','DRAFT')");
        jdbc.update("insert into orders(id,user_id,order_type,status,original_amount_vnd,final_amount_vnd,payment_provider,payment_reference,expires_at) values(13,7,?,'PENDING_PAYMENT',?,?,'SEPAY','LEARNEM6',?)",
                plan ? "PLAN_SUBSCRIPTION" : "COURSE_PURCHASE", amount, amount, java.sql.Timestamp.from(paidAt.plusSeconds(86400)));
        jdbc.update("insert into order_items(order_id,product_type,course_id,plan_code,item_name_snapshot,unit_amount_vnd,quantity,total_amount_vnd) values(13,?,?,?,?,?,1,?)",
                plan ? "PLAN" : "COURSE", plan ? null : 11L, plan ? "PRO" : null, "Historical title", amount, amount);
        transport.delivered.clear(); transport.nextFailure = null; transport.onSend = null;
    }
    JobLease claim() {
        return store.claim(QueueName.MAIL_NOTICE, Map.of(JobType.PAYMENT_CONFIRMATION_MAIL, Set.of(1)), Duration.ofSeconds(30)).orElseThrow();
    }
    void execute(JobLease lease) { registry.find(lease.type(), 1).orElseThrow().execute(lease); }
    void webhook() {
        sepay.processWebhook("{\"id\":101,\"code\":\"LEARNEM6\",\"transferType\":\"in\",\"transferAmount\":100}", new HttpHeaders());
    }
    long couponCheckout() {
        jdbc.update("insert into users(id,email,password_hash,full_name,role) values(7,'student@example.test','hash','Buyer','STUDENT'),(8,'teacher@example.test','hash','Teacher','TEACHER'),(9,'admin@example.test','hash','Admin','ADMIN')");
        jdbc.update("insert into courses(id,teacher_id,name,type,level,status,published_at,pricing_type,price_status,price_amount_vnd) values(11,8,'Historical checkout title','COMPREHENSIVE','B1','APPROVED',clock_timestamp(),'PAID','APPROVED',100)");
        jdbc.update("insert into coupons(id,code,code_normalized,discount_type,discount_value,minimum_final_amount_vnd,usage_limit_total,usage_limit_per_user,valid_from,valid_until,created_by) values(21,'FREE100','FREE100','FIXED_AMOUNT',100,0,1,1,clock_timestamp()-interval '1 day',clock_timestamp()+interval '1 day',9)");
        jdbc.update("insert into coupon_product_scopes(coupon_id,product_scope) values(21,'COURSE_PURCHASE')");
        jdbc.update("insert into coupon_course_scopes(coupon_id,course_id) values(21,11)");
        jdbc.update("insert into coupon_teacher_scopes(coupon_id,teacher_id) values(21,8)");
        transport.delivered.clear(); transport.nextFailure = null; transport.onSend = null;
        var result = checkout.createCourseOrder(users.findById(7L).orElseThrow(), 11L, "FREE100");
        assertThat(result.originalAmountVnd()).isEqualTo(100);
        assertThat(result.discountAmountVnd()).isEqualTo(100);
        assertThat(result.finalAmountVnd()).isZero();
        assertThat(result.couponId()).isEqualTo(21);
        return result.id();
    }
    void assertCouponPaidOnce(long id) {
        assertThat(jdbc.queryForObject("select status from orders where id=?", String.class, id)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("select count(*) from course_entitlements where order_id=?", Long.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from coupon_redemptions where order_id=? and coupon_id=21 and discount_amount_vnd=100", Long.class, id)).isEqualTo(1);
    }
    @Test void facadeMandatoryRejectsOutsideTransactionBeforeReadingOrder() {
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(paymentMail)).isTrue();
        assertThatThrownBy(() -> paymentMail.enqueue(null)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from background_jobs", Long.class)).isZero();
    }
    @Test void realAdminProxySettlesZeroCouponCheckoutWithExactReceiptEnvelopeAndNoBackfill() {
        long id = couponCheckout();
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(adminOrders)).isTrue();
        var response = adminOrders.markPaid(id);
        assertCouponPaidOnce(id);
        var row = jdbc.queryForMap("select queue,type,priority,expires_at,secret_key_id,secret_nonce,secret_ciphertext,dedupe_key from background_jobs");
        assertThat(row).containsEntry("queue", "MAIL_NOTICE").containsEntry("type", "PAYMENT_CONFIRMATION_MAIL")
                .containsEntry("dedupe_key", "payment-confirmation:" + id);
        assertThat(((Number) row.get("priority")).intValue()).isZero();
        for (String key : List.of("expires_at", "secret_key_id", "secret_nonce", "secret_ciphertext")) assertThat(row.get(key)).isNull();
        var lease = claim(); var p = codec.decode(lease.type(), lease.payloadJson());
        assertThat(p.template()).isEqualTo(com.learne.backend.mail.model.MailTemplate.PAYMENT_CONFIRMATION);
        assertThat(p.recipientUserId()).isEqualTo(7); assertThat(p.recipientEmail()).isEqualTo("student@example.test");
        assertThat(p.recipientName()).isEqualTo("Buyer"); assertThat(p.orderId()).isEqualTo(id);
        assertThat(p.values()).containsEntry("amountVnd", "0").containsEntry("paidAt", response.paidAt().toString());
        jdbc.update("update courses set name='Mutable changed name',price_amount_vnd=999 where id=11");
        execute(lease);
        assertThat(transport.delivered.getFirst().getText()).contains("Historical checkout title", "0 VND").doesNotContain("Mutable changed name");
        jdbc.update("delete from background_jobs");
        adminOrders.markPaid(id); settlement.markPaid(id, Instant.now());
        assertCouponPaidOnce(id);
        assertThat(jdbc.queryForObject("select count(*) from background_jobs", Long.class)).isZero();
    }
    @Test void adminQueueFailureRollsBackFlushedCouponAccessAndOrderThenConcurrentRetryRedeemsOnce() throws Exception {
        long id = couponCheckout();
        JobStore target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(jobStore);
        doAnswer(call -> {
            entityManager.flush();
            assertCouponPaidOnce(id);
            call.callRealMethod();
            assertThat(jdbc.queryForObject("select count(*) from background_jobs", Long.class)).isEqualTo(1);
            throw new JobEnqueueException(null);
        }).when(target).enqueue(any());
        assertThatThrownBy(() -> adminOrders.markPaid(id)).isInstanceOf(JobEnqueueException.class);
        assertThat(jdbc.queryForObject("select status from orders where id=?", String.class, id)).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("select paid_at from orders where id=?", java.sql.Timestamp.class, id)).isNull();
        for (String table : List.of("course_entitlements", "coupon_redemptions", "background_jobs"))
            assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class)).isZero();
        doCallRealMethod().when(target).enqueue(any());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 2; i++) tasks.add(executor.submit(() -> {
                try { assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                adminOrders.markPaid(id);
            }));
            start.countDown();
            for (var task : tasks) task.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertCouponPaidOnce(id);
            assertThat(jdbc.queryForObject("select count(*) from background_jobs", Long.class)).isEqualTo(1);
        } finally { start.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
    }
    @Test void smtpBlocksOnlyAfterCouponPaymentCommitAndRetryDoesNotMutateBusinessState() throws Exception {
        long id = couponCheckout(); adminOrders.markPaid(id);
        var orderBefore = jdbc.queryForMap("select * from orders where id=?", id);
        var accessBefore = jdbc.queryForMap("select * from course_entitlements where order_id=?", id);
        var redemptionBefore = jdbc.queryForMap("select * from coupon_redemptions where order_id=?", id);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        transport.nextFailure = new RetryableJobException("THROTTLED", null);
        transport.onSend = () -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();
            try { assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        };
        var lease = claim();
        try {
            var worker = executor.submit(() -> execute(lease));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(worker.isDone()).isFalse(); assertCouponPaidOnce(id);
            assertThat(jdbc.queryForMap("select * from orders where id=?", id)).isEqualTo(orderBefore);
            assertThat(jdbc.queryForMap("select * from course_entitlements where order_id=?", id)).isEqualTo(accessBefore);
            assertThat(jdbc.queryForMap("select * from coupon_redemptions where order_id=?", id)).isEqualTo(redemptionBefore);
            release.countDown();
            assertThatThrownBy(() -> worker.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class).hasCauseInstanceOf(RetryableJobException.class);
            transport.onSend = null;
            assertThat(store.retry(lease, Instant.now().minusSeconds(1), "THROTTLED")).isTrue();
            execute(claim()); assertThat(transport.delivered).hasSize(1);
            assertThat(jdbc.queryForMap("select * from orders where id=?", id)).isEqualTo(orderBefore);
            assertThat(jdbc.queryForMap("select * from course_entitlements where order_id=?", id)).isEqualTo(accessBefore);
            assertThat(jdbc.queryForMap("select * from coupon_redemptions where order_id=?", id)).isEqualTo(redemptionBefore);
        } finally {
            release.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            transport.onSend = null; transport.nextFailure = null;
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void paidTransitionSnapshotsBothProductsAndNeverBackfillsPurgedHistory(boolean plan) {
        seed(plan, 100);
        settlement.markPaid(13L, paidAt);
        var lease = claim(); var p = codec.decode(lease.type(), lease.payloadJson());
        assertThat(p.recipientUserId()).isEqualTo(7); assertThat(p.orderId()).isEqualTo(13);
        assertThat(p.values()).containsEntry("amountVnd", "100").containsEntry("paidAt", paidAt.toString());
        assertThat(p.values().get("itemDescription")).contains("Historical title").doesNotContain("Mutable course");
        assertThat(lease.secret()).isNull(); assertThat(p.expiresAt()).isNull();
        assertThat(jdbc.queryForObject("select dedupe_key from background_jobs", String.class)).isEqualTo("payment-confirmation:13");
        jdbc.update("delete from background_jobs");
        settlement.markPaid(13L, paidAt); webhook();
        assertThat(jdbc.queryForObject("select count(*) from background_jobs", Long.class)).isZero();
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void flushedAccessAndOuterWebhookEventRollBackOnQueueFailureAndExactRetryWorks(boolean outer) {
        seed(false, 100);
        JobStore target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(jobStore);
        doAnswer(call -> {
            entityManager.flush();
            assertThat(jdbc.queryForObject("select count(*) from course_entitlements", Long.class)).isEqualTo(1);
            throw new JobEnqueueException(null);
        }).when(target).enqueue(any());
        assertThatThrownBy(() -> { if (outer) webhook(); else settlement.markPaid(13L, paidAt); }).isInstanceOf(JobEnqueueException.class);
        assertThat(jdbc.queryForObject("select status from orders where id=13", String.class)).isEqualTo("PENDING_PAYMENT");
        for (String table : List.of("course_entitlements", "sepay_webhook_events", "background_jobs"))
            assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class)).isZero();
        doCallRealMethod().when(target).enqueue(any());
        if (outer) webhook(); else settlement.markPaid(13L, paidAt);
        assertThat(jdbc.queryForObject("select count(*) from background_jobs", Long.class)).isEqualTo(1);
    }
    @Test void zeroAmountReceiptAndMailRetryNeverRegrantAndUseSnapshot() {
        seed(false, 0); settlement.markPaid(13L, paidAt);
        jdbc.update("update courses set name='Changed live title' where id=11");
        jdbc.update("update order_items set item_name_snapshot='Changed item title'");
        var lease = claim(); transport.nextFailure = new RetryableJobException("THROTTLED", null);
        assertThatThrownBy(() -> execute(lease)).isInstanceOf(RetryableJobException.class);
        assertThat(store.retry(lease, Instant.now().minusSeconds(1), "THROTTLED")).isTrue();
        execute(claim());
        assertThat(transport.delivered).hasSize(1);
        assertThat(transport.delivered.getFirst().getText()).contains("Historical title", "0 VND").doesNotContain("Changed");
        assertThat(jdbc.queryForObject("select status from orders where id=13", String.class)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("select count(*) from course_entitlements", Long.class)).isEqualTo(1);
    }
    @Test void partialMutationApiExceptionCannotCommitThroughWebhookCatch() {
        seed(false, 100);
        doThrow(new com.learne.backend.shared.api.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "Injected failure"))
                .when(caches).evictAccessForStudent(7L);
        assertThatThrownBy(this::webhook).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(jdbc.queryForObject("select status from orders where id=13", String.class)).isEqualTo("PENDING_PAYMENT");
        for (String table : List.of("course_entitlements", "background_jobs", "sepay_webhook_events"))
            assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class)).isZero();
    }
    @Test void simulationAndRepeatedProviderEventsGrantOnlyOnce() {
        seed(true, 100);
        sepay.simulatePayment(new com.learne.backend.monetization.dto.SimulateSepayPaymentRequest("LEARNEM6", 100L, 102L));
        webhook(); webhook();
        assertThat(jdbc.queryForObject("select count(*) from subscriptions", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from background_jobs", Long.class)).isEqualTo(1);
    }
    @Test void changedEmailSkipsReceiptWithoutResettling() {
        seed(false, 100); settlement.markPaid(13L, paidAt);
        jdbc.update("update users set email='changed@example.test' where id=7");
        var lease = claim(); execute(lease);
        assertThat(jdbc.queryForObject("select state from background_jobs", String.class)).isEqualTo("SKIPPED");
        assertThat(transport.delivered).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from course_entitlements", Long.class)).isEqualTo(1);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"out,LEARNEM6,100,false", "in,UNKNOWN,100,false", "in,LEARNEM6,99,false", "in,LEARNEM6,100,true"})
    void rejectedEventsNeverEnqueue(String direction, String reference, long amount, boolean expired) {
        seed(false, 100);
        if (expired) jdbc.update("update orders set expires_at=clock_timestamp()-interval '1 day'");
        sepay.processWebhook("{\"id\":101,\"code\":\"" + reference + "\",\"transferType\":\"" + direction + "\",\"transferAmount\":" + amount + "}", new HttpHeaders());
        assertThat(jdbc.queryForObject("select count(*) from background_jobs", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from course_entitlements", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select status from orders where id=13", String.class)).isEqualTo(expired ? "EXPIRED" : "PENDING_PAYMENT");
    }
    @Test void concurrentConfirmationsSerializeAtOrderLock() throws Exception {
        seed(false, 100);
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 2; i++) tasks.add(executor.submit(() -> {
                try { assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                settlement.markPaid(13L, paidAt);
            }));
            start.countDown();
            for (var task : tasks) task.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("select count(*) from background_jobs", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from course_entitlements", Long.class)).isEqualTo(1);
        } finally { start.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
    }
}
