package com.learne.backend.teacher.content.audio.service;

import com.learne.backend.storage.config.CloudinaryProperties;
import com.learne.backend.jobs.service.RetryableJobException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.ObjectMapper;

/** Dedicated VIDEO destroy transport. A cancelled Java 21 HTTP exchange closes its socket.
 * Each operation owns its client; shutdownNow also cancels transport work before releasing a slot.
 * No executor is created per timeout, and admission never queues unbounded requests.
 */
public final class CloudinaryMediaDeleteAdapter implements MediaDeletePort {
    private final CloudinaryProperties properties;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Duration deadline;
    private final Duration readTimeout;
    private static final ScheduledThreadPoolExecutor READ_TIMER=readTimer();
    private final Semaphore slots=new Semaphore(2);
    public CloudinaryMediaDeleteAdapter(CloudinaryProperties properties,ObjectMapper mapper) {
        this(properties,mapper,URI.create("https://api.cloudinary.com/v1_1/"+properties.getCloudName()+"/video/destroy"),Duration.ofSeconds(60));
        if(properties.getCloudName()==null || !properties.getCloudName().matches("[A-Za-z0-9_-]+")
                || properties.getApiKey()==null || properties.getApiKey().isBlank()
                || properties.getApiSecret()==null || properties.getApiSecret().isBlank()) throw new IllegalArgumentException("Media storage configuration required");
    }
    /** Explicit endpoint injection is for isolated loopback transport tests only. */
    public CloudinaryMediaDeleteAdapter(CloudinaryProperties properties,ObjectMapper mapper,URI endpoint,Duration deadline) {
        this(properties,mapper,endpoint,deadline,Duration.ofSeconds(8));
    }
    public CloudinaryMediaDeleteAdapter(CloudinaryProperties properties,ObjectMapper mapper,URI endpoint,Duration deadline,Duration readTimeout) {
        if(deadline.isNegative() || deadline.isZero() || deadline.compareTo(Duration.ofSeconds(60))>0) throw new IllegalArgumentException("Invalid media deadline");
        if(readTimeout.isNegative() || readTimeout.isZero() || readTimeout.compareTo(Duration.ofSeconds(8))>0) throw new IllegalArgumentException("Invalid read timeout");
        boolean production=endpoint.equals(URI.create("https://api.cloudinary.com/v1_1/"+properties.getCloudName()+"/video/destroy"));
        boolean loopback="http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost()) && endpoint.getPort()>0;
        if((!production && !loopback) || endpoint.getRawUserInfo()!=null || endpoint.getRawQuery()!=null || endpoint.getRawFragment()!=null) throw new IllegalArgumentException("Unsafe media endpoint");
        this.properties=properties; this.mapper=mapper; this.endpoint=endpoint; this.deadline=deadline;
        this.readTimeout=readTimeout;
    }
    public int availableSlots() { return slots.availablePermits(); }
    public void delete(String publicId) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Media delete requires no SQL transaction");
        if(publicId==null || !publicId.matches("learne/course-audio/[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid managed audio ID");
        if(!slots.tryAcquire()) throw retry();
        HttpClient client=null; CompletableFuture<?> pending=null; ScheduledFuture<?> readWatch=null;
        long end=System.nanoTime()+deadline.toNanos();
        try {
            if(properties.getApiKey()==null || properties.getApiSecret()==null) throw retry();
            String timestamp=Long.toString(Instant.now().getEpochSecond());
            String signature=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(
                    ("public_id="+publicId+"&timestamp="+timestamp+properties.getApiSecret()).getBytes(StandardCharsets.UTF_8)));
            String form="public_id="+encode(publicId)+"&timestamp="+timestamp+"&api_key="+encode(properties.getApiKey())+"&signature="+signature;
            client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
            var request=HttpRequest.newBuilder(endpoint).timeout(deadline).header("Content-Type","application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            var lastRead=new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
            var response=client.sendAsync(request,info->{ lastRead.set(System.nanoTime()); return new BoundedBody(()->lastRead.set(System.nanoTime())); }); pending=response;
            readWatch=READ_TIMER.scheduleAtFixedRate(()->{
                if(System.nanoTime()-lastRead.get()>=readTimeout.toNanos()) response.cancel(true);
            },readTimeout.toNanos(),Math.min(readTimeout.toNanos(),TimeUnit.MILLISECONDS.toNanos(25)),TimeUnit.NANOSECONDS);
            var result=response.get(Math.max(1,end-System.nanoTime()),TimeUnit.NANOSECONDS);
            if(result.statusCode()!=200) throw retry();
            var json=mapper.reader().with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(result.body());
            String outcome=json.path("result").asString();
            if(!json.isObject() || !("ok".equals(outcome) || "not found".equals(outcome))) throw retry();
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw retry();
        } catch(Exception failure) { throw retry(); }
        finally {
            try {
                if(readWatch!=null) readWatch.cancel(false);
                if(pending!=null && !pending.isDone()) pending.cancel(true);
                if(client!=null) { client.shutdownNow(); client.close(); }
            } finally { slots.release(); }
        }
    }
    private static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
    private static RetryableJobException retry() { return new RetryableJobException("BUSY",null); }
    private static ScheduledThreadPoolExecutor readTimer() {
        var timer=new ScheduledThreadPoolExecutor(1,task->{ var thread=new Thread(task,"media-read-timeout"); thread.setDaemon(true); return thread; });
        timer.setRemoveOnCancelPolicy(true); return timer;
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long bytes;
        private final Runnable read;
        BoundedBody(Runnable read) { this.read=read; }
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription=value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> buffers) {
            read.run();
            for(var buffer:buffers) bytes+=buffer.remaining();
            if(bytes>16384) { subscription.cancel(); delegate.onError(new java.io.IOException("Media response too large")); }
            else delegate.onNext(buffers);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
}
