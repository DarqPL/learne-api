package com.learne.backend.teacher.content.enrichment;

import static org.assertj.core.api.Assertions.*;
import com.learne.backend.jobs.service.RetryableJobException;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Raw loopback only: does not need PostgreSQL, an AI provider, or external DNS. */
class EnrichmentDeadlineIT {
    @Test void invalidCapabilitiesConfigurationIsSanitized() {
        try (var transport = transport(500)) {
            assertThatThrownBy(() -> transport.capabilities(URI.create("ftp://localhost/capabilities"), "secret"))
                    .isInstanceOf(com.learne.backend.jobs.service.PermanentJobException.class)
                    .hasMessage("ENRICHMENT_CONFIGURATION_UNAVAILABLE").hasNoCause();
            assertThatThrownBy(() -> transport.capabilities(URI.create("http://localhost/capabilities"), "secret\r\ninjected"))
                    .isInstanceOf(com.learne.backend.jobs.service.PermanentJobException.class)
                    .hasMessage("ENRICHMENT_CONFIGURATION_UNAVAILABLE").hasNoCause();
        }
    }
    @ParameterizedTest @ValueSource(strings={"headers", "body", "drip"})
    void totalDeadlineCancelsWire(String mode) throws Exception {
        try (Peer peer = new Peer(mode); var transport = transport(300)) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> transport.exchange(peer.request(), Duration.ofMillis(300)))
                    .isInstanceOf(RetryableJobException.class).hasMessage("ENRICHMENT_DEADLINE_EXCEEDED").hasNoCause();
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));
            assertThat(peer.disconnected.await(3, TimeUnit.SECONDS)).isTrue();
        }
    }
    @ParameterizedTest @ValueSource(strings={"length", "stream"})
    void boundsAdvertisedAndStreamingBodies(String mode) throws Exception {
        try (Peer peer = new Peer(mode); var transport = transport(500)) {
            assertThatThrownBy(() -> transport.exchange(peer.request(), Duration.ofMillis(500)))
                    .isInstanceOf(RetryableJobException.class).hasMessage("ENRICHMENT_RESPONSE_TOO_LARGE");
            assertThat(peer.disconnected.await(3, TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test void tinyBudgetSuccessAndDefensiveOwnership() throws Exception {
        try (Peer peer = new Peer("ok"); var transport = transport(500)) {
            var response = transport.exchange(peer.request(), Duration.ofMillis(500));
            assertThat(response.status()).isEqualTo(200);
            byte[] body = response.body();
            body[0] = 0;
            assertThat(new String(response.body(), StandardCharsets.US_ASCII)).isEqualTo("{}");
            assertThatThrownBy(() -> response.headers().map().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
    }
    @Test void capabilitiesGetAndNoRedirectOrTokenForwarding() throws Exception {
        try (Peer peer = new Peer("redirect"); var transport = transport(500)) {
            var result = transport.capabilities(peer.request().uri(), "secret");
            assertThat(result.status()).isEqualTo(302);
        }
    }
    @Test void networkFailureHasKnownCodeAndNoCause() throws Exception {
        try (Peer peer = new Peer("disconnect"); var transport = transport(500)) {
            assertThatThrownBy(() -> transport.exchange(peer.request(),Duration.ofMillis(500)))
                    .isInstanceOf(RetryableJobException.class).hasMessage("ENRICHMENT_UNAVAILABLE").hasNoCause();
        }
    }
    @Test void saturationFastBusyAndCloseCancelsActiveWithoutHanging() throws Exception {
        try (Peer peer = new Peer("headers"); var transport = transport(500)) {
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<?> pending = caller.submit(() -> transport.exchange(peer.request(),Duration.ofMillis(500)));
                assertThat(peer.accepted.await(2,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> transport.exchange(peer.request(),Duration.ofMillis(500))).hasMessage("BUSY");
                long threads = Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().equals("enrichment-http-io")).count();
                assertThat(threads).isLessThanOrEqualTo(4);
                long start = System.nanoTime();
                transport.close();
                assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(1));
                assertThatThrownBy(() -> pending.get(2,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
                assertThat(peer.disconnected.await(2,TimeUnit.SECONDS)).isTrue();
            } finally { caller.shutdownNow(); }
        }
    }
    private EnrichmentHttpTransport transport(int timeout) {
        return new EnrichmentHttpTransport(new EnrichmentClientProperties(timeout,100,1));
    }
    private static final class Peer implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0,1,InetAddress.getLoopbackAddress());
        final CountDownLatch accepted = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        volatile Socket socket;
        Peer(String mode) throws IOException {
            worker.submit(() -> {
                try (Socket connection = server.accept()) {
                    socket = connection;
                    connection.setSoTimeout(3000);
                    InputStream in = connection.getInputStream();
                    int state = 0;
                    while (state < 4) {
                        int c = in.read(); if (c < 0) return;
                        state = (state == 0 || state == 2) && c == '\r' ? state+1 :
                                (state == 1 || state == 3) && c == '\n' ? state+1 : 0;
                    }
                    accepted.countDown();
                    if (mode.equals("disconnect")) { server.close(); return; }
                    OutputStream out = connection.getOutputStream();
                    if (mode.equals("redirect")) {
                        out.write("HTTP/1.1 302 Found\r\nContent-Length: 0\r\nLocation: http://127.0.0.1:1/secret\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        return;
                    }
                    if (!mode.equals("headers")) {
                        String framing = switch(mode) {
                            case "ok" -> "Content-Length: 2\r\n";
                            case "length" -> "Content-Length: 1048577\r\n";
                            case "stream" -> "Transfer-Encoding: chunked\r\n";
                            default -> "Content-Length: 10000\r\n";
                        };
                        out.write(("HTTP/1.1 200 OK\r\n"+framing+"\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
                    }
                    if (mode.equals("ok")) { out.write("{}".getBytes(StandardCharsets.US_ASCII)); out.flush(); }
                    if (mode.equals("stream")) {
                        byte[] block = new byte[8192];
                        for (int i=0;i<129;i++) { out.write("2000\r\n".getBytes(StandardCharsets.US_ASCII)); out.write(block); out.write("\r\n".getBytes(StandardCharsets.US_ASCII)); out.flush(); }
                    }
                    if (mode.equals("drip")) {
                        for (int i=0;i<50;i++) { out.write(' '); out.flush(); Thread.sleep(30); }
                    }
                    while (in.read() != -1) { }
                    disconnected.countDown();
                } catch (SocketException error) { disconnected.countDown(); }
                catch (Exception error) { /* Timeout does not count as observed cancellation. */ }
            });
        }
        HttpRequest request() { return HttpRequest.newBuilder(URI.create("http://localhost:"+server.getLocalPort()+"/capabilities")).GET().build(); }
        @Override public void close() throws IOException {
            server.close(); if (socket != null) socket.close(); worker.shutdownNow();
            try { worker.awaitTermination(1,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
