package com.learne.backend.teacher.content.audio;

import static org.assertj.core.api.Assertions.*;
import com.learne.backend.teacher.content.audio.service.*;
import com.learne.backend.storage.config.CloudinaryProperties;
import com.learne.backend.jobs.service.RetryableJobException;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;

@Timeout(15)
class MediaDeleteAdapterTests {
    @Test void providerTransientAndRedirectStatusesAreSafeFailures() throws Exception {
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{ requests.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/redirected");
            int status=Integer.parseInt(exchange.getRequestURI().getPath().substring(1));
            var bytes="{\"result\":\"not found\"}".getBytes(); exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try {
            for(int status:new int[]{429,500,503,302,404}) {
                var adapter=new CloudinaryMediaDeleteAdapter(properties(),new ObjectMapper(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"+status),Duration.ofSeconds(2));
                assertThatThrownBy(()->adapter.delete("learne/course-audio/a")).isInstanceOf(RetryableJobException.class);
            }
            assertThat(requests).hasValue(5);
        } finally { server.stop(0); }
    }
    @Test void bodyStallAndDripCloseSocketsAndConcurrentAdmissionIsBounded() throws Exception {
        for(boolean drip:new boolean[]{false,true}) {
            try(var server=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1")); var peers=Executors.newFixedThreadPool(2); var callers=Executors.newFixedThreadPool(2)) {
                var adapter=new CloudinaryMediaDeleteAdapter(properties(),new ObjectMapper(),URI.create("http://127.0.0.1:"+server.getLocalPort()+"/"),Duration.ofMillis(1000),Duration.ofMillis(200));
                var accepted=new CountDownLatch(2); var finish=new CountDownLatch(2);
                var observations=new java.util.ArrayList<Future<Boolean>>();
                for(int i=0;i<2;i++) observations.add(peers.submit(()->{
                    try(var socket=server.accept()) {
                        socket.setSoTimeout(2500);
                        var input=socket.getInputStream(); var request=new java.io.ByteArrayOutputStream(); int b;
                        while((b=input.read())!=-1) { request.write(b); if(request.toString().contains("\r\n\r\n")) break; }
                        // Drain the known request body before observing EOF.
                        var headers=request.toString(); int length=Integer.parseInt(headers.split("(?i)Content-Length: ")[1].split("\r\n")[0]); input.readNBytes(length);
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 10000\r\n\r\n{".getBytes()); socket.getOutputStream().flush(); accepted.countDown();
                        if(drip) {
                            while(true) { socket.getOutputStream().write(' '); socket.getOutputStream().flush(); Thread.sleep(40); }
                        }
                        return input.read()==-1;
                    } catch(SocketTimeoutException stillOpen) { throw new AssertionError("Body socket stayed open",stillOpen); }
                    catch(java.io.IOException closed) { return true; }
                    finally { finish.countDown(); }
                }));
                long started=System.nanoTime();
                var calls=new java.util.ArrayList<Future<?>>();
                for(int i=0;i<2;i++) calls.add(callers.submit(()->assertThatThrownBy(()->adapter.delete("learne/course-audio/a")).isInstanceOf(RetryableJobException.class)));
                assertThat(accepted.await(2,TimeUnit.SECONDS)).isTrue(); assertThat(adapter.availableSlots()).isZero();
                for(int i=0;i<20;i++) assertThatThrownBy(()->adapter.delete("learne/course-audio/a")).isInstanceOf(RetryableJobException.class);
                assertThat(Thread.getAllStackTraces().keySet().stream().filter(t->t.isAlive() && t.getName().startsWith("HttpClient-")).count()).isLessThanOrEqualTo(8);
                for(var call:calls) call.get(3,TimeUnit.SECONDS);
                assertThat(finish.await(3,TimeUnit.SECONDS)).isTrue(); for(var observation:observations) assertThat(observation.get()).isTrue();
                assertThat(adapter.availableSlots()).isEqualTo(2);
                long millis=Duration.ofNanos(System.nanoTime()-started).toMillis();
                assertThat(millis).isLessThan(drip?2500:900); // Idle read timeout differs from absolute deadline.
            }
        }
    }
    @Test void signedRequestMatchesCloudinarySdkAndUnsafeEndpointsAreRejected() throws Exception {
        for(String endpoint:new String[]{"http://api.cloudinary.com/v1_1/test/video/destroy","https://evil.test/","http://localhost/","http://127.0.0.1@evil.test/"})
            assertThatThrownBy(()->new CloudinaryMediaDeleteAdapter(properties(),new ObjectMapper(),URI.create(endpoint),Duration.ofSeconds(2))).isInstanceOf(IllegalArgumentException.class);
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var captured=new CompletableFuture<java.util.Map<String,String>>();
        server.createContext("/",exchange->{
            var fields=new java.util.HashMap<String,String>();
            for(String pair:new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).split("&")) {
                var parts=pair.split("=",2); fields.put(parts[0],URLDecoder.decode(parts[1],java.nio.charset.StandardCharsets.UTF_8));
            }
            captured.complete(fields); var bytes="{\"result\":\"not found\"}".getBytes(); exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try {
            new CloudinaryMediaDeleteAdapter(properties(),new ObjectMapper(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),Duration.ofSeconds(2)).delete("learne/course-audio/a");
            var fields=captured.get(2,TimeUnit.SECONDS);
            var sdk=new com.cloudinary.Cloudinary(java.util.Map.of("cloud_name","test","api_key","synthetic","api_secret","synthetic"));
            assertThat(fields.get("signature")).isEqualTo(sdk.apiSignRequest(java.util.Map.of("public_id",fields.get("public_id"),"timestamp",fields.get("timestamp")),"synthetic"));
            assertThat(fields).containsEntry("api_key","synthetic").containsEntry("public_id","learne/course-audio/a");
        } finally { server.stop(0); }
    }
    CloudinaryProperties properties() {
        var p=new CloudinaryProperties(); p.setCloudName("test"); p.setApiKey("synthetic"); p.setApiSecret("synthetic"); return p;
    }
    @Test void documentedResultsSucceedAndMalformedOrTransientResultsFail() throws Exception {
        for(String body:new String[]{"{\"result\":\"ok\"}","{\"result\":\"not found\"}","{}","{\"result\":\"unsupported\"}","{\"result\":null}","{\"result\":42}","not-json","{\"result\":\"ok\"} {}"," ".repeat(17000)}) {
            var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{ exchange.getRequestBody().readAllBytes(); byte[] bytes=body.getBytes(); exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); }); server.start();
            try {
                var adapter=new CloudinaryMediaDeleteAdapter(properties(),new ObjectMapper(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),Duration.ofSeconds(2));
                if(body.equals("{\"result\":\"ok\"}") || body.equals("{\"result\":\"not found\"}")) adapter.delete("learne/course-audio/a");
                else assertThatThrownBy(()->adapter.delete("learne/course-audio/a")).isInstanceOf(RetryableJobException.class);
            } finally { server.stop(0); }
        }
    }
    @Test void absoluteDeadlineClosesRealSocketAndReleasesSlotsAcrossRepeatedStalls() throws Exception {
        try(var server=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1")); var peer=Executors.newSingleThreadExecutor()) {
            var adapter=new CloudinaryMediaDeleteAdapter(properties(),new ObjectMapper(),URI.create("http://127.0.0.1:"+server.getLocalPort()+"/"),Duration.ofMillis(300));
            for(int attempt=0;attempt<4;attempt++) {
                var closed=peer.submit(()->{
                    try(var socket=server.accept()) {
                        socket.setSoTimeout(2000); var input=socket.getInputStream();
                        while(input.read()!=-1) { /* Client sends request then must close stalled connection. */ }
                        return true;
                    } catch(SocketTimeoutException stillOpen) { throw new AssertionError("Client socket stayed open",stillOpen); }
                    catch(java.io.IOException reset) { return true; }
                });
                long start=System.nanoTime();
                assertThatThrownBy(()->adapter.delete("learne/course-audio/a")).isInstanceOf(RetryableJobException.class);
                assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));
                assertThat(closed.get(3,TimeUnit.SECONDS)).isTrue();
                assertThat(adapter.availableSlots()).isEqualTo(2);
            }
            assertThat(Thread.getAllStackTraces().keySet().stream().filter(t->t.isAlive() && t.getName().startsWith("HttpClient-")).count()).isLessThanOrEqualTo(4);
        }
    }
}
