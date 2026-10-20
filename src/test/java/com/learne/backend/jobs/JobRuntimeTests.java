package com.learne.backend.jobs;

import com.learne.backend.jobs.config.JobProperties;
import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(8)
class JobRuntimeTests {
    private final JobStore store = mock(JobStore.class);
    private final JobHandler handler = mock(JobHandler.class);
    private final JobProperties properties = new JobProperties();
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    private JobRegistry registry() {
        when(handler.type()).thenReturn(JobType.COURSE_ENRICHMENT);
        when(handler.payloadVersion()).thenReturn(1);
        return new JobRegistry(List.of(handler));
    }

    private JobLease lease(int attempt, boolean control) {
        return new JobLease(UUID.randomUUID(), UUID.randomUUID(), now.minusSeconds(1),
                JobType.COURSE_ENRICHMENT, 1, attempt, 3, "{}", null, control);
    }

    private JobDispatcher dispatcher(JobLease lease) {
        when(store.claim(eq(QueueName.AI), anyMap(), any())).thenReturn(Optional.of(lease));
        when(store.isLeaseCurrent(lease)).thenReturn(true);
        when(store.heartbeat(eq(lease), any())).thenReturn(true);
        when(store.retry(eq(lease), any(), any())).thenReturn(true);
        return new JobDispatcher(store, registry(), properties.snapshot(), Clock.fixed(now, ZoneOffset.UTC), () -> 0.0);
    }

    @Test void registryIsExactImmutableAndRejectsDuplicates() {
        var registry = registry();
        assertThat(registry.find(JobType.COURSE_ENRICHMENT, 2)).isEmpty();
        assertThat(registry.supported(QueueName.AI)).containsEntry(JobType.COURSE_ENRICHMENT, Set.of(1));
        assertThatThrownBy(() -> registry.supported(QueueName.AI).clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new JobRegistry(List.of(handler, handler))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new JobRegistry(List.of()).supported(QueueName.AI)).isEmpty();
    }

    @Test void snapshotsValidateAndDoNotRetainBindingObjects() {
        var snapshot = properties.snapshot();
        properties.getLanes().get(QueueName.AI).setMaxAttempts(9);
        assertThat(snapshot.lanes().get(QueueName.AI).maxAttempts()).isEqualTo(3);
        assertThatThrownBy(properties::snapshot).isInstanceOf(IllegalArgumentException.class);
        var invalid = new JobProperties();
        invalid.getLanes().get(QueueName.AI).setHeartbeat(Duration.ofSeconds(180));
        assertThatThrownBy(invalid::snapshot).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void busySlotPreventsAnotherClaimAndNeverRunsOnCaller() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var caller = Thread.currentThread();
        doAnswer(inv -> { assertThat(Thread.currentThread()).isNotSameAs(caller); entered.countDown(); release.await(); return null; })
                .when(handler).execute(any());
        try (var dispatcher = dispatcher(lease(1, false))) {
            assertThat(dispatcher.dispatch(QueueName.AI)).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(dispatcher.dispatch(QueueName.AI)).isFalse();
            verify(store, times(1)).claim(eq(QueueName.AI), anyMap(), any());
            release.countDown();
        } finally { release.countDown(); }
    }

    @Test void controlLeaseOnlyTerminalizes() {
        var lease = lease(3, true);
        try (var dispatcher = dispatcher(lease)) {
            dispatcher.dispatch(QueueName.AI);
            verify(handler, timeout(1000)).failPermanently(lease, "EXHAUSTED_ATTEMPTS");
        }
        verify(handler).failPermanently(lease, "EXHAUSTED_ATTEMPTS");
        verify(handler, never()).execute(any());
    }

    @Test void callbackAfterCompletionDoesNotRouteFailure() {
        var lease = lease(1, false);
        doThrow(new RuntimeException("private provider text")).when(handler).execute(lease);
        try (var dispatcher = dispatcher(lease)) {
            when(store.isLeaseCurrent(lease)).thenReturn(false);
            dispatcher.dispatch(QueueName.AI);
            verify(store, timeout(1000)).isLeaseCurrent(lease);
        }
        verify(handler, never()).failPermanently(any(), any());
        verify(store, never()).retry(any(), any(), any());
    }

    @Test void retryRespectsRetryAfterAndBackoff() {
        var lease = lease(1, false);
        doThrow(new RetryableJobException("THROTTLED", Duration.ofSeconds(90))).when(handler).execute(lease);
        try (var dispatcher = dispatcher(lease)) {
            dispatcher.dispatch(QueueName.AI);
            verify(store, timeout(1000)).retry(lease, now.plusSeconds(90), "THROTTLED");
        }
        verify(store).retry(lease, now.plusSeconds(90), "THROTTLED");
    }

    @Test void exhaustedAndPermanentErrorsDelegateDomainFailure() {
        for (int attempt : new int[]{1, 3}) {
            reset(handler, store);
            var lease = lease(attempt, false);
            doThrow(attempt == 1 ? new PermanentJobException("INVALID_INPUT") : new RetryableJobException("BUSY", null))
                    .when(handler).execute(lease);
            try (var dispatcher = dispatcher(lease)) {
                dispatcher.dispatch(QueueName.AI);
                verify(handler, timeout(1000)).failPermanently(lease, attempt == 1 ? "INVALID_INPUT" : "EXHAUSTED_ATTEMPTS");
            }
            verify(handler).failPermanently(lease, attempt == 1 ? "INVALID_INPUT" : "EXHAUSTED_ATTEMPTS");
        }
    }

    @Test void safeExceptionsRejectProviderMessagesAndHaveNoCause() {
        assertThatThrownBy(() -> new PermanentJobException("provider secret: x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new RetryableJobException("BUSY", null).getCause()).isNull();
    }

    @Test void heartbeatLossInterruptsButKeepsSlotUntilActualExit() throws Exception {
        properties.getLanes().get(QueueName.AI).setHeartbeat(Duration.ofMillis(10));
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(inv -> {
            entered.countDown();
            while (release.getCount() > 0) {
                try { release.await(); } catch (InterruptedException e) { interrupted.countDown(); }
            }
            throw new RetryableJobException("BUSY", null);
        }).when(handler).execute(any());
        var lease = lease(1, false);
        try (var dispatcher = dispatcher(lease)) {
            when(store.heartbeat(eq(lease), any())).thenReturn(false);
            dispatcher.dispatch(QueueName.AI);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(dispatcher.dispatch(QueueName.AI)).isFalse();
            release.countDown();
        } finally { release.countDown(); }
        verify(store, never()).retry(any(), any(), any());
    }

    @Test void shutdownStopsClaimsAndTimers() {
        var dispatcher = new JobDispatcher(store, registry(), properties.snapshot(), Clock.systemUTC(), () -> 0);
        dispatcher.close();
        assertThat(dispatcher.dispatch(QueueName.AI)).isFalse();
        verifyNoInteractions(store);
    }

    @Test void terminalizationCallbackFailureIsNotInvokedTwice() {
        var lease = lease(3, true);
        doThrow(new RuntimeException("private callback detail")).when(handler).failPermanently(any(), any());
        try (var dispatcher = dispatcher(lease)) {
            dispatcher.dispatch(QueueName.AI);
            verify(handler, timeout(1000)).failPermanently(any(), any());
        }
        verify(handler, times(1)).failPermanently(any(), any());
    }

    @Test void unexpectedFailureUsesOnlySafeCode() {
        var lease = lease(1, false);
        doThrow(new IllegalStateException("provider secret")).when(handler).execute(lease);
        try (var dispatcher = dispatcher(lease)) {
            dispatcher.dispatch(QueueName.AI);
            verify(handler, timeout(1000)).failPermanently(lease, "HANDLER_ERROR");
        }
        verify(handler).failPermanently(lease, "HANDLER_ERROR");
        verify(store, never()).complete(any(), any(), any());
    }

    @Test void retryJitterNeverExceedsTenPercentAndUsesAttemptSpecificBackoff() {
        var lease = lease(2, false);
        doThrow(new RetryableJobException("BUSY", null)).when(handler).execute(lease);
        when(store.claim(eq(QueueName.AI), anyMap(), any())).thenReturn(Optional.of(lease));
        when(store.isLeaseCurrent(lease)).thenReturn(true);
        try (var dispatcher = new JobDispatcher(store, registry(), properties.snapshot(),
                Clock.fixed(now, ZoneOffset.UTC), () -> 1)) {
            dispatcher.dispatch(QueueName.AI);
            verify(store, timeout(1000)).retry(lease, now.plusSeconds(132), "BUSY");
        }
        verify(store).retry(lease, now.plusSeconds(132), "BUSY");
    }

    @Test void everyLanePolicyIsValidatedAndCollectionsAreCopied() {
        for (var queue : QueueName.values()) {
            var p = new JobProperties();
            assertThat(p.snapshot().lanes().get(queue).slots()).isOne();
            p.getLanes().get(queue).setSlots(0);
            assertThatThrownBy(p::snapshot).isInstanceOf(IllegalArgumentException.class);
            p = new JobProperties();
            p.getLanes().get(queue).setDeadline(p.getLanes().get(queue).getLease());
            assertThatThrownBy(p::snapshot).isInstanceOf(IllegalArgumentException.class);
        }
        properties.setJitter(0.10001);
        assertThatThrownBy(properties::snapshot).isInstanceOf(IllegalArgumentException.class);
        properties.setJitter(0);
        var mutable = new ArrayList<>(List.of(Duration.ofSeconds(30), Duration.ofSeconds(120)));
        properties.getLanes().get(QueueName.AI).setBackoff(mutable);
        var snapshot = properties.snapshot();
        mutable.clear();
        assertThat(snapshot.lanes().get(QueueName.AI).backoff()).hasSize(2);
        assertThatThrownBy(() -> snapshot.lanes().clear()).isInstanceOf(UnsupportedOperationException.class);
        properties.getLanes().remove(QueueName.MEDIA);
        assertThatThrownBy(properties::snapshot).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void claimFailureReturnsReservedCapacity() {
        when(store.claim(any(), anyMap(), any())).thenThrow(new IllegalStateException("DB unavailable"))
                .thenReturn(Optional.empty());
        try (var dispatcher = new JobDispatcher(store, registry(), properties.snapshot(), Clock.systemUTC(), () -> 0)) {
            assertThatThrownBy(() -> dispatcher.dispatch(QueueName.AI)).isInstanceOf(IllegalStateException.class);
            assertThat(dispatcher.dispatch(QueueName.AI)).isFalse();
        }
        verify(store, times(2)).claim(any(), anyMap(), any());
    }

    @Test void producerTransactionCannotClaim() {
        try (var dispatcher = new JobDispatcher(store, registry(), properties.snapshot(), Clock.systemUTC(), () -> 0)) {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThatThrownBy(() -> dispatcher.dispatch(QueueName.AI)).isInstanceOf(IllegalStateException.class);
            } finally {
                org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        }
        verifyNoInteractions(store);
    }

    @Test void slotIsReservedEvenWhileClaimIsBlocked() throws Exception {
        var claiming = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(store.claim(any(), anyMap(), any())).thenAnswer(inv -> {
            claiming.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            return Optional.empty();
        });
        var callers = Executors.newFixedThreadPool(2);
        try (var dispatcher = new JobDispatcher(store, registry(), properties.snapshot(), Clock.systemUTC(), () -> 0)) {
            var first = callers.submit(() -> dispatcher.dispatch(QueueName.AI));
            assertThat(claiming.await(2, TimeUnit.SECONDS)).isTrue();
            var second = callers.submit(() -> dispatcher.dispatch(QueueName.AI));
            assertThat(second.get(500, TimeUnit.MILLISECONDS)).isFalse();
            verify(store, times(1)).claim(any(), anyMap(), any());
            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS)).isFalse();
        } finally { release.countDown(); callers.shutdownNow(); }
    }

    @Test void renewalExceptionInterruptsAndSuppressesFailureRouting() throws Exception {
        properties.getLanes().get(QueueName.AI).setHeartbeat(Duration.ofMillis(10));
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        doAnswer(inv -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { interrupted.countDown(); }
            throw new PermanentJobException("INVALID_INPUT");
        }).when(handler).execute(any());
        var lease = lease(1, false);
        try (var dispatcher = dispatcher(lease)) {
            when(store.heartbeat(eq(lease), any())).thenThrow(new IllegalStateException("DB unavailable"));
            dispatcher.dispatch(QueueName.AI);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        }
        verify(handler, never()).failPermanently(any(), any());
    }

    @Test void scheduledPollingIsBoundedAndShutdownCancelsPendingPolls() throws Exception {
        properties.getLanes().get(QueueName.AI).setPoll(Duration.ofMillis(10));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(inv -> { entered.countDown(); release.await(); return null; }).when(handler).execute(any());
        var dispatcher = dispatcher(lease(1, false));
        try {
            dispatcher.start();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            verify(store, after(60).times(1)).claim(eq(QueueName.AI), anyMap(), any());
            release.countDown();
        } finally { release.countDown(); dispatcher.close(); }
        clearInvocations(store);
        verify(store, after(60).never()).claim(any(), anyMap(), any());
        verify(store, never()).recoverExpired(anyInt());
    }

    @Test void forcedShutdownRevokesRoutingBeforeInterruptingWorker() throws Exception {
        var entered = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        doAnswer(inv -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { throw new RetryableJobException("BUSY", null); }
            finally { exited.countDown(); }
            return null;
        }).when(handler).execute(any());
        var dispatcher = dispatcher(lease(1, false));
        dispatcher.dispatch(QueueName.AI);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        dispatcher.close();
        assertThat(exited.await(2, TimeUnit.SECONDS)).isTrue();
        verify(store, after(50).never()).retry(any(), any(), any());
    }

    @Test void retryBudgetRejectionDelegatesExpiryForBackoffAndRetryAfter() {
        for (Duration retryAfter : new Duration[]{null, Duration.ofSeconds(90)}) {
            reset(store, handler);
            var lease = lease(1, false);
            doThrow(new RetryableJobException("BUSY", retryAfter)).when(handler).execute(lease);
            try (var dispatcher = dispatcher(lease)) {
                when(store.retry(eq(lease), any(), any())).thenReturn(false);
                dispatcher.dispatch(QueueName.AI);
                verify(handler, timeout(1000)).failPermanently(lease, "JOB_EXPIRED");
            }
            verify(store).retry(lease, now.plusSeconds(retryAfter == null ? 30 : 90), "BUSY");
            verify(handler).failPermanently(lease, "JOB_EXPIRED");
            verify(store, never()).complete(any(), any(), any());
        }
    }

    @Test void rejectedRetryDoesNotTerminalizeWhenOwnershipWasLost() {
        var lease = lease(1, false);
        doThrow(new RetryableJobException("BUSY", null)).when(handler).execute(lease);
        try (var dispatcher = dispatcher(lease)) {
            when(store.retry(eq(lease), any(), any())).thenReturn(false);
            when(store.isLeaseCurrent(lease)).thenReturn(true, false);
            dispatcher.dispatch(QueueName.AI);
            verify(store, timeout(1000).times(2)).isLeaseCurrent(lease);
        }
        verify(store, times(2)).isLeaseCurrent(lease);
        verify(handler, never()).failPermanently(any(), any());
    }

    @Test void syntacticallyValidUnknownCodesAreRejectedWithoutLeakingInputOrCause() {
        for (String code : new String[]{"SECRET_TOKEN_ABC123", "UNREGISTERED_PROVIDER_CODE", "", null}) {
            assertThatThrownBy(() -> new PermanentJobException(code))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid job safe code").hasNoCause();
            assertThatThrownBy(() -> new RetryableJobException(code, null))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid job safe code").hasNoCause();
        }
        assertThat(new PermanentJobException("JOB_EXPIRED").getCode()).isEqualTo("JOB_EXPIRED");
        assertThat(new RetryableJobException("THROTTLED", null).getCode()).isEqualTo("THROTTLED");
    }

    @Test void shutdownDoesNotWaitForBlockedClaimOrStartItsLateLease() throws Exception {
        var claiming = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var lease = lease(1, false);
        var dispatcher = dispatcher(lease);
        when(store.claim(eq(QueueName.AI), anyMap(), any())).thenAnswer(inv -> {
            claiming.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return Optional.of(lease);
        });
        var callers = Executors.newFixedThreadPool(2);
        try {
            var claim = callers.submit(() -> dispatcher.dispatch(QueueName.AI));
            assertThat(claiming.await(2, TimeUnit.SECONDS)).isTrue();
            var close = callers.submit(dispatcher::close);
            close.get(1, TimeUnit.SECONDS);
            assertThat(claim.isDone()).isFalse();
            assertThat(dispatcher.dispatch(QueueName.AI)).isFalse();
            verify(store, times(1)).claim(any(), anyMap(), any());
            release.countDown();
            assertThat(claim.get(2, TimeUnit.SECONDS)).isFalse();
            verify(handler, never()).execute(any());
            verify(handler, never()).failPermanently(any(), any());
        } finally {
            release.countDown();
            callers.shutdownNow();
            assertThat(callers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            dispatcher.close();
        }
    }

    @Test void blockedRenewalsCannotStarveDeadlineRevocation() throws Exception {
        var entered = new CountDownLatch(4);
        var renewing = new CountDownLatch(4);
        var revoked = new CountDownLatch(4);
        var releaseWork = new CountDownLatch(1);
        var releaseIo = new CountDownLatch(1);
        var handlers = new ArrayList<JobHandler>();
        JobType[] types = {JobType.COURSE_ENRICHMENT, JobType.REGISTRATION_OTP_MAIL,
                JobType.PAYMENT_CONFIRMATION_MAIL, JobType.MEDIA_CLEANUP};
        for (var type : types) {
            var h = mock(JobHandler.class);
            when(h.type()).thenReturn(type);
            when(h.payloadVersion()).thenReturn(1);
            doAnswer(inv -> {
                entered.countDown();
                awaitIgnoringInterrupts(releaseWork, revoked);
                throw new RetryableJobException("BUSY", null);
            }).when(h).execute(any());
            handlers.add(h);
            var queue = JobRegistry.queueFor(type);
            properties.getLanes().get(queue).setHeartbeat(Duration.ofMillis(10));
            properties.getLanes().get(queue).setDeadline(Duration.ofMillis(250));
            var lease = new JobLease(UUID.randomUUID(), UUID.randomUUID(), now, type, 1, 1,
                    queue == QueueName.AI ? 3 : 5, "{}", null, false);
            when(store.claim(eq(queue), anyMap(), any())).thenReturn(Optional.of(lease));
        }
        when(store.heartbeat(any(), any())).thenAnswer(inv -> {
            assertThat(Thread.currentThread().getName()).startsWith("jobs-renewal-io");
            renewing.countDown();
            awaitIgnoringInterrupts(releaseIo, new CountDownLatch(0));
            return true;
        });
        var dispatcher = new JobDispatcher(store, new JobRegistry(handlers), properties.snapshot(), Clock.systemUTC(), () -> 0);
        try {
            for (var queue : QueueName.values()) assertThat(dispatcher.dispatch(queue)).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(renewing.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(revoked.await(1, TimeUnit.SECONDS)).isTrue();
            for (var queue : QueueName.values()) assertThat(dispatcher.dispatch(queue)).isFalse();
            verify(store, times(4)).heartbeat(any(), any());
        } finally { releaseIo.countDown(); releaseWork.countDown(); dispatcher.close(); }
        verify(store, never()).retry(any(), any(), any());
    }

    @Test void automaticPollingFillsTwoSlotsButNeverClaimsThirdWhileBothBusy() throws Exception {
        properties.getLanes().get(QueueName.AI).setSlots(2);
        properties.getLanes().get(QueueName.AI).setPoll(Duration.ofMillis(10));
        properties.getLanes().get(QueueName.AI).setHeartbeat(Duration.ofMillis(5));
        var entered = new CountDownLatch(2);
        var firstRevoked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = lease(1, false);
        var second = lease(1, false);
        doAnswer(inv -> {
            entered.countDown();
            awaitIgnoringInterrupts(release, inv.getArgument(0) == first ? firstRevoked : new CountDownLatch(0));
            return null;
        }).when(handler).execute(any());
        var dispatcher = dispatcher(first);
        var claims = new java.util.concurrent.atomic.AtomicInteger();
        when(store.claim(eq(QueueName.AI), anyMap(), any())).thenAnswer(inv -> {
            assertThat(Thread.currentThread().getName()).startsWith("jobs-poll-io");
            if (claims.incrementAndGet() == 1) return Optional.of(first);
            assertThat(firstRevoked.await(1, TimeUnit.SECONDS)).isTrue();
            return Optional.of(second);
        });
        when(store.heartbeat(eq(first), any())).thenReturn(false);
        when(store.heartbeat(eq(second), any())).thenReturn(true);
        try {
            dispatcher.start();
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(firstRevoked.getCount()).isZero();
            verify(store, after(60).times(2)).claim(eq(QueueName.AI), anyMap(), any());
            assertThat(dispatcher.dispatch(QueueName.AI)).isFalse();
        } finally { release.countDown(); dispatcher.close(); }
    }

    @Test void deadlineRevokedBeforeAdmissionPreventsNormalAndControlEntry() throws Exception {
        assertRevokedBeforeEntry(false, false);
        assertRevokedBeforeEntry(false, true);
    }

    @Test void shutdownRevokedBeforeAdmissionPreventsNormalAndControlEntry() throws Exception {
        assertRevokedBeforeEntry(true, false);
        assertRevokedBeforeEntry(true, true);
    }

    private void assertRevokedBeforeEntry(boolean shutdown, boolean control) throws Exception {
        reset(store, handler);
        var lease = lease(control ? 3 : 1, control);
        if (!shutdown) properties.getLanes().get(QueueName.AI).setDeadline(Duration.ofMillis(30));
        else properties.getLanes().get(QueueName.AI).setDeadline(Duration.ofSeconds(120));
        var paused = new CountDownLatch(1);
        var revoked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var registry = spy(registry());
        doAnswer(inv -> {
            paused.countDown();
            awaitIgnoringInterrupts(release, revoked);
            return Optional.of(handler);
        }).when(registry).find(lease.type(), 1);
        when(store.claim(any(), anyMap(), any())).thenReturn(Optional.of(lease));
        when(store.heartbeat(any(), any())).thenReturn(true);
        when(store.isLeaseCurrent(any())).thenReturn(true);
        var dispatcher = new JobDispatcher(store, registry, properties.snapshot(), Clock.systemUTC(), () -> 0);
        try {
            dispatcher.dispatch(QueueName.AI);
            assertThat(paused.await(2, TimeUnit.SECONDS)).isTrue();
            if (shutdown) dispatcher.close();
            assertThat(revoked.await(1, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); dispatcher.close(); }
        verify(handler, after(50).never()).execute(any());
        verify(handler, never()).failPermanently(any(), any());
    }

    @Test void admissionsAreImmutableFailClosedBeforeClaimAndLeaveEveryMailTypeIndependent() {
        var admissions = new ArrayList<JobQueueAdmission>();
        admissions.add(queue -> queue != QueueName.AI);
        var handlers = new ArrayList<JobHandler>();
        for (var type : JobType.values()) {
            var h = mock(JobHandler.class);
            when(h.type()).thenReturn(type);
            when(h.payloadVersion()).thenReturn(1);
            handlers.add(h);
        }
        when(store.claim(any(), anyMap(), any())).thenReturn(Optional.empty());
        try (var dispatcher = new JobDispatcher(store, new JobRegistry(handlers), properties.snapshot(),
                Clock.systemUTC(), () -> 0, null, admissions)) {
            admissions.clear();
            assertThat(dispatcher.dispatch(QueueName.AI)).isFalse();
            verifyNoInteractions(store);
            for (var queue : QueueName.values()) if (queue != QueueName.AI) dispatcher.dispatch(queue);
            verify(store).claim(eq(QueueName.MAIL_AUTH), argThat(types -> types.size() == 3), any());
            verify(store).claim(eq(QueueName.MAIL_NOTICE), argThat(types -> types.size() == 3), any());
            verify(store).claim(eq(QueueName.MEDIA), anyMap(), any());
        }
        clearInvocations(store);
        try (var dispatcher = new JobDispatcher(store, registry(), properties.snapshot(), Clock.systemUTC(), () -> 0,
                null, List.of(queue -> { throw new IllegalStateException("private gate detail"); }))) {
            assertThat(dispatcher.dispatch(QueueName.AI)).isFalse();
            assertThat(dispatcher.admissionState(QueueName.AI)).isEqualTo(JobDispatcher.AdmissionState.ERROR);
            verifyNoInteractions(store);
        }
    }

    private static void awaitIgnoringInterrupts(CountDownLatch release, CountDownLatch interrupted) {
        boolean sawInterrupt = false;
        while (release.getCount() > 0) {
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Fixture was not released");
            } catch (InterruptedException expected) { sawInterrupt = true; interrupted.countDown(); }
        }
        if (sawInterrupt) Thread.currentThread().interrupt();
    }
}
