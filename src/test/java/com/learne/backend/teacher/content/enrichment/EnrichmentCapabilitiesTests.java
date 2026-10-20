package com.learne.backend.teacher.content.enrichment;

import com.learne.backend.jobs.model.QueueName;
import com.learne.backend.student.learning.ai.AiServiceProperties;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(8)
class EnrichmentCapabilitiesTests {
    private final EnrichmentHttpTransport transport = mock(EnrichmentHttpTransport.class);
    private final AiServiceProperties properties = new AiServiceProperties("http://127.0.0.1:1/", 8000, true, "internal-secret");

    private void remote(String body) {
        when(transport.capabilities(any(), any())).thenReturn(new EnrichmentHttpTransport.Response(200,
                HttpHeaders.of(Map.of(), (a, b) -> true), body.getBytes(StandardCharsets.UTF_8)));
    }
    private String pairs(String... versions) {
        var entries = new ArrayList<String>();
        for (int i = 0; i < versions.length; i += 2)
            entries.add("{\"promptVersion\":\"" + versions[i] + "\",\"responseVersion\":\"" + versions[i + 1] + "\"}");
        return "{\"supportedContracts\":[" + String.join(",", entries) + "]}";
    }
    private EnrichmentCapabilityGate gate(java.util.function.Supplier<List<EnrichmentContractSource>> sources) {
        return new EnrichmentCapabilityGate(new EnrichmentCapabilities(properties, transport), true, true, sources);
    }

    @Test void exactPairsNeverFormACrossProductAndForwardToken() {
        remote(pairs("1", "1", "2", "2"));
        var capabilities = new EnrichmentCapabilities(properties, transport).fetch();
        assertThat(capabilities).containsExactlyInAnyOrder(new EnrichmentCapabilities.Tuple("1", "1"), new EnrichmentCapabilities.Tuple("2", "2"));
        assertThat(capabilities).doesNotContain(new EnrichmentCapabilities.Tuple("1", "2"));
        assertThatThrownBy(capabilities::clear).isInstanceOf(UnsupportedOperationException.class);
        verify(transport).capabilities(URI.create("http://127.0.0.1:1/internal/capabilities/course-enrichment"), "internal-secret");
    }

    @Test void noSourcesRequiresNewV1AndStartsPaused() {
        remote(pairs("1", "1"));
        try (var gate = gate(List::of)) {
            assertThat(gate.allows(QueueName.AI)).isFalse();
            verifyNoInteractions(transport);
            gate.refresh();
            assertThat(gate.state()).isEqualTo(EnrichmentCapabilityGate.SafeState.READY);
            assertThat(gate.allows(QueueName.AI)).isTrue();
        }
    }

    @Test void activeAndNewContractsAreBothRequiredAndPolicyMustBeLocal() {
        var contracts = new AtomicReference<>(Set.of(new EnrichmentContract("1", "2", "1")));
        try (var gate = gate(() -> List.of(contracts::get))) {
            remote(pairs("2", "1")); gate.refresh();
            assertThat(gate.allows(QueueName.AI)).isFalse();
            remote(pairs("1", "1", "2", "1")); gate.refresh();
            assertThat(gate.allows(QueueName.AI)).isTrue();
            contracts.set(Set.of(new EnrichmentContract("1", "3", "1"))); gate.refresh();
            assertThat(gate.allows(QueueName.AI)).isFalse();
            assertThat(gate.state()).isEqualTo(EnrichmentCapabilityGate.SafeState.UNSUPPORTED_TUPLE);
            contracts.set(Set.of(new EnrichmentContract("2", "1", "1"))); gate.refresh();
            assertThat(gate.state()).isEqualTo(EnrichmentCapabilityGate.SafeState.UNSUPPORTED_POLICY);
        }
    }

    @Test void remotelyAdvertisedResponseWithoutLocalValidatorBlocksAdmission() {
        remote(pairs("1", "1", "2", "2"));
        try (var gate = gate(() -> List.of(() -> Set.of(new EnrichmentContract("1", "2", "2"))))) {
            gate.refresh();
            assertThat(gate.allows(QueueName.AI)).isFalse();
            assertThat(gate.state()).isEqualTo(EnrichmentCapabilityGate.SafeState.UNSUPPORTED_RESPONSE);
            verifyNoInteractions(transport);
        }
    }

    @Test void failedRefreshNeverRetainsAllowAndClaimReadsDoNotCallSources() {
        remote(pairs("1", "1"));
        var source = mock(EnrichmentContractSource.class);
        when(source.activeContracts()).thenReturn(Set.of());
        try (var gate = gate(() -> List.of(source))) {
            gate.refresh();
            for (int i = 0; i < 100; i++) assertThat(gate.allows(QueueName.AI)).isTrue();
            verify(source, times(1)).activeContracts();
            when(source.activeContracts()).thenThrow(new IllegalStateException("private SQL"));
            gate.refresh(); assertThat(gate.allows(QueueName.AI)).isFalse();
            doReturn(Set.of()).when(source).activeContracts(); gate.refresh();
            assertThat(gate.allows(QueueName.AI)).isTrue();
            when(transport.capabilities(any(), any())).thenThrow(new IllegalStateException("private provider"));
            gate.refresh(); assertThat(gate.allows(QueueName.AI)).isFalse();
        }
    }

    @Test void malformedDuplicateNoncanonicalAndProtectedResponsesFailClosed() {
        for (String body : List.of("{}", "not json", pairs(), pairs("01", "1"), pairs("1", "1", "1", "1"),
                pairs("v1", "1"), "{\"supportedContracts\":[{\"promptVersion\":1,\"responseVersion\":\"1\"}]}",
                "{\"supportedContracts\":[],\"supportedContracts\":[]}")) {
            remote(body);
            try (var gate = gate(List::of)) { gate.refresh(); assertThat(gate.allows(QueueName.AI)).isFalse(); }
        }
        for (int status : new int[]{403, 409, 503}) {
            when(transport.capabilities(any(), any())).thenReturn(new EnrichmentHttpTransport.Response(status,
                    HttpHeaders.of(Map.of(), (a, b) -> true), pairs("1", "1").getBytes(StandardCharsets.UTF_8)));
            try (var gate = gate(List::of)) { gate.refresh(); assertThat(gate.allows(QueueName.AI)).isFalse(); }
        }
    }

    @Test void disabledDoesNoIoAndOtherQueuesAlwaysPass() {
        try (var gate = new EnrichmentCapabilityGate(new EnrichmentCapabilities(properties, transport), false, true,
                () -> { throw new AssertionError("source discovery"); })) {
            gate.start(); gate.refresh();
            assertThat(gate.state()).isEqualTo(EnrichmentCapabilityGate.SafeState.DISABLED);
            for (var queue : QueueName.values()) assertThat(gate.allows(queue)).isEqualTo(queue != QueueName.AI);
            verifyNoInteractions(transport);
        }
    }

    @Test void blockedRefreshDoesNotBlockReadsOrCloseAndLateResultCannotReopen() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        remote(pairs("1", "1"));
        var response = transport.capabilities(URI.create("http://127.0.0.1"), "");
        when(transport.capabilities(any(), any())).thenAnswer(inv -> {
            entered.countDown();
            while (release.getCount() > 0) { try { release.await(); } catch (InterruptedException ignored) { } }
            return response;
        });
        var gate = gate(List::of);
        try {
            gate.start(); assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(gate.allows(QueueName.AI)).isFalse();
            assertThat(gate.allows(QueueName.MAIL_AUTH)).isTrue();
            assertThat(gate.nextRefreshDelaySeconds()).isEqualTo(5);
            long start = System.nanoTime(); gate.close();
            assertThat(System.nanoTime() - start).isLessThan(TimeUnit.SECONDS.toNanos(1));
            release.countDown();
            assertThat(gate.allows(QueueName.AI)).isFalse();
        } finally { release.countDown(); gate.close(); }
    }

    @Test void backoffIsFiniteAndDisabledWorkerNeverStartsNetwork() {
        when(transport.capabilities(any(), any())).thenThrow(new IllegalStateException());
        try (var gate = new EnrichmentCapabilityGate(new EnrichmentCapabilities(properties, transport), true, false, List::of)) {
            gate.start(); verifyNoInteractions(transport);
            for (int expected : new int[]{5, 10, 30, 60, 60}) {
                gate.refresh(); assertThat(gate.nextRefreshDelaySeconds()).isEqualTo(expected);
            }
        }
    }

    @Test void realLoopbackGetForwardsOnlyInternalAuthenticationAndParsesProtectedEndpoint() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var request = new AtomicReference<com.sun.net.httpserver.HttpExchange>();
        server.createContext("/internal/capabilities/course-enrichment", exchange -> {
            request.set(exchange);
            byte[] body = pairs("1", "1").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (var owned = new EnrichmentHttpTransport(new EnrichmentClientProperties(120000, 5000, 2))) {
            var local = new AiServiceProperties("http://127.0.0.1:" + server.getAddress().getPort(), 8000, true, "internal-secret");
            assertThat(new EnrichmentCapabilities(local, owned).fetch()).containsExactly(new EnrichmentCapabilities.Tuple("1", "1"));
            assertThat(request.get().getRequestMethod()).isEqualTo("GET");
            assertThat(request.get().getRequestHeaders().getFirst("X-Internal-Service-Token")).isEqualTo("internal-secret");
            assertThat(request.get().getRequestHeaders().getFirst("Authorization")).isNull();
        } finally { server.stop(0); }
    }

    @Test void blockedRefreshRejectsOverlapAndMailDispatchStillClaims() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(transport.capabilities(any(), any())).thenAnswer(inv -> {
            entered.countDown(); release.await(); throw new IllegalStateException();
        });
        var store = mock(com.learne.backend.jobs.service.JobStore.class);
        var handler = mock(com.learne.backend.jobs.service.JobHandler.class);
        when(handler.type()).thenReturn(com.learne.backend.jobs.model.JobType.REGISTRATION_OTP_MAIL);
        when(handler.payloadVersion()).thenReturn(1);
        when(store.claim(any(), anyMap(), any())).thenReturn(Optional.empty());
        try (var gate = gate(List::of);
             var dispatcher = new com.learne.backend.jobs.service.JobDispatcher(store,
                     new com.learne.backend.jobs.service.JobRegistry(List.of(handler)),
                     new com.learne.backend.jobs.config.JobProperties().snapshot(), java.time.Clock.systemUTC(),
                     () -> 0, null, List.of(gate))) {
            gate.start(); assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 20; i++) gate.refresh();
            dispatcher.dispatch(QueueName.MAIL_AUTH);
            verify(store).claim(eq(QueueName.MAIL_AUTH), anyMap(), any());
            verify(transport, times(1)).capabilities(any(), any());
        } finally { release.countDown(); }
    }

    @Test void realGatePreventsAiClaimsUntilReadyAndAfterRefreshFailure() {
        remote(pairs("1", "1"));
        var store = mock(com.learne.backend.jobs.service.JobStore.class);
        var handler = mock(com.learne.backend.jobs.service.JobHandler.class);
        when(handler.type()).thenReturn(com.learne.backend.jobs.model.JobType.COURSE_ENRICHMENT);
        when(handler.payloadVersion()).thenReturn(1);
        when(store.claim(any(), anyMap(), any())).thenReturn(Optional.empty());
        try (var gate = gate(List::of);
             var dispatcher = new com.learne.backend.jobs.service.JobDispatcher(store,
                     new com.learne.backend.jobs.service.JobRegistry(List.of(handler)),
                     new com.learne.backend.jobs.config.JobProperties().snapshot(), java.time.Clock.systemUTC(),
                     () -> 0, null, List.of(gate))) {
            assertThat(dispatcher.dispatch(QueueName.AI)).isFalse();
            verifyNoInteractions(store, transport);
            gate.refresh();
            assertThat(gate.nextRefreshDelaySeconds()).isEqualTo(15);
            dispatcher.dispatch(QueueName.AI);
            verify(store).claim(eq(QueueName.AI), anyMap(), any());
            when(transport.capabilities(any(), any())).thenThrow(new IllegalStateException());
            gate.refresh();
            assertThat(dispatcher.dispatch(QueueName.AI)).isFalse();
            verify(store, times(1)).claim(any(), anyMap(), any());
        }
    }
}
