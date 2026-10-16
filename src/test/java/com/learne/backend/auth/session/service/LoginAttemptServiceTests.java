package com.learne.backend.auth.session.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.learne.backend.ratelimit.RateLimitException;
import com.learne.backend.ratelimit.RateLimitProperties;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class LoginAttemptServiceTests {
    @SuppressWarnings("unchecked")
    private final ObjectProvider<RedisLoginAttemptStore> provider = mock(ObjectProvider.class);
    private final RedisLoginAttemptStore store = mock(RedisLoginAttemptStore.class);

    private LoginAttemptService service(boolean enabled) {
        var props = new RateLimitProperties(enabled, null, null, enabled ? null : "", null, null, null);
        return new LoginAttemptService(props, provider);
    }

    @Test
    void enabledDelegatesAllOperationsUsingRootNormalizedEmail() {
        when(provider.getObject()).thenReturn(store);
        var service = service(true);
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            service.assertCanAttempt(" I@Example.COM ");
            service.recordFailure(" I@Example.COM ");
            service.clear(" I@Example.COM ");
        } finally { Locale.setDefault(previous); }
        var order = inOrder(store);
        order.verify(store).assertCanAttempt("i@example.com");
        order.verify(store).recordFailure("i@example.com");
        order.verify(store).clear("i@example.com");
        verifyNoMoreInteractions(store);
    }

    @Test
    void disabledBypassesBeforeProviderOrIdentifierProcessing() {
        var service = service(false);
        for (int i = 0; i < 7; i++) {
            service.assertCanAttempt(null);
            service.recordFailure(null);
            service.clear(null);
        }
        verifyNoInteractions(provider, store);
    }

    @Test
    void propagatesBothRejectionAndUnavailableFromEveryOperation() {
        when(provider.getObject()).thenReturn(store);
        var service = service(true);
        for (var error : new RateLimitException[] {RateLimitException.rejected(1234), RateLimitException.unavailable()}) {
            doThrow(error).when(store).assertCanAttempt("a@example.com");
            doThrow(error).when(store).recordFailure("a@example.com");
            doThrow(error).when(store).clear("a@example.com");
            for (Consumer<String> operation : java.util.List.<Consumer<String>>of(
                    service::assertCanAttempt, service::recordFailure, service::clear)) {
                assertThatThrownBy(() -> operation.accept("a@example.com")).isSameAs(error);
            }
        }
    }

    @Test
    void normalizedEmailLockSerializesCallbacksAndReleasesAfterException() throws Exception {
        var service = service(false);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> service.withEmailLock(" Student@Example.COM ", () -> {
                entered.countDown();
                try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException error) { throw new AssertionError(error); }
                throw new IllegalStateException("callback");
            }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var second = workers.submit(() -> {
                    secondStarted.countDown();
                    return service.withEmailLock("student@example.com", () -> "released");
                });
                assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
                assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("released");
            } finally { release.countDown(); }
        }
        verifyNoInteractions(provider, store);
    }
}
