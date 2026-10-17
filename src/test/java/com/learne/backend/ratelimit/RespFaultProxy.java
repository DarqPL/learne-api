package com.learne.backend.ratelimit;

import io.lettuce.core.RedisURI;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Test-only RESP2/RESP3 frame proxy. Faults never stop or reconfigure Redis. */
final class RespFaultProxy implements AutoCloseable {
    enum Action { DROP_BEFORE, DISCONNECT_BEFORE, DROP_REPLY, DISCONNECT_REPLY, DELAY_REPLY }
    record Fault(Action action, long delayMs) {
        Fault(Action action) { this(action, 0); }
    }
    private record Request(String command, Fault fault) { }
    private record Frame(byte[] bytes, Object value) { }
    private final ServerSocket listener;
    private final RedisURI upstream;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, Fault> faults = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> received = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> forwarded = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> replies = new ConcurrentHashMap<>();
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private volatile boolean closed;

    RespFaultProxy(RedisURI upstream) throws IOException {
        this.upstream = upstream;
        listener = new ServerSocket(0, 32, InetAddress.getLoopbackAddress());
        workers.submit(() -> {
            try {
                while (!closed) {
                    Socket client = listener.accept();
                    client.setTcpNoDelay(true);
                    sockets.add(client);
                    connections.incrementAndGet();
                    workers.submit(() -> serve(client));
                }
            } catch (IOException exception) { if (!closed) failure.compareAndSet(null, exception); }
        });
    }

    RedisURI uri() { return RedisURI.Builder.redis(listener.getInetAddress().getHostAddress(), listener.getLocalPort()).build(); }
    void fault(String command, Action action) { faults.put(command, new Fault(action)); }
    void delay(String command, long millis) { faults.put(command, new Fault(Action.DELAY_REPLY, millis)); }
    void recover() { faults.clear(); }
    int received(String command) { return count(received, command); }
    int forwarded(String command) { return count(forwarded, command); }
    int replies(String command) { return count(replies, command); }
    int connections() { return connections.get(); }
    void assertHealthy() { org.assertj.core.api.Assertions.assertThat(failure.get()).isNull(); }

    private static int count(ConcurrentMap<String, AtomicInteger> counts, String command) {
        return counts.getOrDefault(command, new AtomicInteger()).get();
    }
    private static void increment(ConcurrentMap<String, AtomicInteger> counts, String command) {
        counts.computeIfAbsent(command, ignored -> new AtomicInteger()).incrementAndGet();
    }

    private void serve(Socket client) {
        Socket server = new Socket();
        sockets.add(server);
        try {
            server.connect(new InetSocketAddress(upstream.getHost(), upstream.getPort()), 1000);
            server.setTcpNoDelay(true);
            var requests = new LinkedBlockingQueue<Request>();
            workers.submit(() -> respond(client, server, requests));
            InputStream input = new BufferedInputStream(client.getInputStream());
            OutputStream output = server.getOutputStream();
            while (!closed) {
                Frame frame = read(input);
                if (frame == null) break;
                List<?> values = (List<?>) frame.value();
                String command = values.getFirst().toString().toUpperCase(Locale.ROOT);
                increment(received, command);
                Fault fault = faults.get(command);
                if (fault != null && fault.action() == Action.DROP_BEFORE) continue;
                if (fault != null && fault.action() == Action.DISCONNECT_BEFORE) break;
                requests.add(new Request(command, fault));
                output.write(frame.bytes());
                output.flush();
                increment(forwarded, command);
            }
        } catch (IOException ignored) {
            // A reset is an intentional transport fault. Parser/logic failures are recorded below.
        } catch (Throwable exception) { if (!closed) failure.compareAndSet(null, exception); }
        finally { close(client); close(server); }
    }

    private void respond(Socket client, Socket server, BlockingQueue<Request> requests) {
        try {
            InputStream input = new BufferedInputStream(server.getInputStream());
            OutputStream output = client.getOutputStream();
            while (!closed) {
                Frame frame = read(input);
                if (frame == null) break;
                // Push frames do not consume a request/reply slot (HELLO maps are ordinary replies).
                if (frame.bytes()[0] == '>') { output.write(frame.bytes()); output.flush(); continue; }
                Request request = requests.take();
                increment(replies, request.command());
                Fault fault = request.fault();
                if (fault != null) {
                    if (fault.action() == Action.DROP_REPLY) continue;
                    if (fault.action() == Action.DISCONNECT_REPLY) break;
                    if (fault.action() == Action.DELAY_REPLY) Thread.sleep(fault.delayMs());
                }
                output.write(frame.bytes());
                output.flush();
            }
        } catch (IOException ignored) {
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        catch (Throwable exception) { if (!closed) failure.compareAndSet(null, exception); }
        finally { close(client); close(server); }
    }

    private static Frame read(InputStream input) throws IOException {
        int type = input.read();
        if (type == -1) return null;
        var bytes = new ByteArrayOutputStream();
        bytes.write(type);
        String header = line(input, bytes);
        Object value = header;
        switch (type) {
            case '$', '=', '!' -> {
                int size = Integer.parseInt(header);
                if (size >= 0) {
                    if (size > 2_000_000) throw new IllegalStateException("Oversized test RESP frame");
                    byte[] body = input.readNBytes(size + 2);
                    if (body.length != size + 2 || body[size] != '\r' || body[size + 1] != '\n') throw new EOFException();
                    bytes.write(body);
                    value = new String(body, 0, size, StandardCharsets.UTF_8);
                }
            }
            case '*', '%', '~', '>', '|' -> {
                int count = Integer.parseInt(header);
                if (type == '%' || type == '|') count *= 2;
                if (count > 100_000) throw new IllegalStateException("Oversized test RESP aggregate");
                var children = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    Frame child = read(input);
                    if (child == null) throw new EOFException();
                    bytes.write(child.bytes());
                    children.add(child.value());
                }
                value = children;
            }
            case '+', '-', ':', '_', '#', ',', '(' -> { }
            default -> throw new IllegalStateException("Unsupported test RESP frame type " + type);
        }
        return new Frame(bytes.toByteArray(), value);
    }

    private static String line(InputStream input, ByteArrayOutputStream frame) throws IOException {
        var line = new ByteArrayOutputStream();
        while (true) {
            int next = input.read();
            if (next < 0) throw new EOFException();
            frame.write(next);
            if (next == '\r') {
                int lf = input.read();
                if (lf != '\n') throw new EOFException();
                frame.write(lf);
                return line.toString(StandardCharsets.UTF_8);
            }
            line.write(next);
            if (line.size() > 1024) throw new IllegalStateException("Oversized test RESP header");
        }
    }

    private static void close(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }

    @Override
    public void close() throws Exception {
        closed = true;
        listener.close();
        sockets.forEach(RespFaultProxy::close);
        workers.shutdownNow();
        if (!workers.awaitTermination(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("RESP proxy workers did not terminate");
        }
        assertHealthy();
    }
}
