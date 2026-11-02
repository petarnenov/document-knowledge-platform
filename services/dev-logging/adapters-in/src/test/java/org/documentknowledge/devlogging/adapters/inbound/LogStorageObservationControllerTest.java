package org.documentknowledge.devlogging.adapters.inbound;

import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.http.server.netty.NettyEmbeddedServer;
import io.micronaut.http.server.netty.NettyServerCustomizer;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.documentknowledge.devlogging.adapters.inbound.requests.*;
import org.documentknowledge.devlogging.application.*;
import org.documentknowledge.devlogging.domain.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogStorageObservationControllerTest {
    final String volume="f".repeat(64);
    final AtomicReference<Instant> now=new AtomicReference<>(Instant.now());
    final Clock clock=new Clock(){public java.time.ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(java.time.ZoneId z){return this;}public Instant instant(){return now.get();}};
    final ObservedVolumeAdmission observation=new ObservedVolumeAdmission(volume,clock);
    final AtomicReference<String> thread=new AtomicReference<>();
    class Fixture implements AutoCloseable {
        final DeadlineLogRequests requests=new DeadlineLogRequests();
        final ApplicationContext context;
        final EmbeddedServer server;
        final AtomicReference<EventLoopGroup> listener=new AtomicReference<>(),connection=new AtomicReference<>();
        final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        Fixture() {
            context=ApplicationContext.builder().properties(Map.of("micronaut.server.host","127.0.0.1","micronaut.server.port",-1,
                "micronaut.server.netty.worker.threads",2,"micronaut.server.netty.parent.threads",1)).build();
            context.registerSingleton(LogObserverAuthentication.class,token->{
                thread.set(Thread.currentThread().getName());
                if(token.equals("expired"))throw new LogFailure(LogFailure.Kind.EXPIRED);
                if(token.equals("unavailable"))throw new LogFailure(LogFailure.Kind.UNAVAILABLE);
                if(token.equals("unexpected"))throw new IllegalStateException("private");
                if(!token.equals("observer"))throw new LogFailure(LogFailure.Kind.UNAUTHENTICATED);
            });
            context.registerSingleton(LogVolumeObservation.class,observation);
            context.registerSingleton(DeadlineLogRequests.class,requests);context.start();
            server=context.getBean(EmbeddedServer.class);
            ((NettyEmbeddedServer)server).register(new NettyServerCustomizer() {
                @Override public NettyServerCustomizer specializeForChannel(Channel channel,ChannelRole role) {
                    if(role==ChannelRole.LISTENER)listener.set(channel.eventLoop().parent());
                    if(role==ChannelRole.CONNECTION)connection.set(channel.eventLoop().parent());
                    return this;
                }
            });
            server.start();
        }
        HttpResponse<String> post(byte[] bytes,String suffix,String... headers) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getPort()+"/api/v1/dev-logs/storage-observation"+suffix))
                .timeout(Duration.ofSeconds(4)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
            for(int i=0;i<headers.length;i+=2)request.header(headers[i],headers[i+1]);
            return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> post(String body,String token) throws Exception{return post(body.getBytes(StandardCharsets.UTF_8),"","Authorization","Bearer "+token);}
        public void close() throws Exception {
            var responses=context.getBean(ExecutorService.class,Qualifiers.byName(LogResponseExecutor.NAME));
            client.close();server.close();context.close();requests.close();
            // Framework shutdown is asynchronous; prove owned fixture resources finish before another context.
            assertTrue(responses.awaitTermination(3,TimeUnit.SECONDS));
            assertNotNull(listener.get());assertNotNull(connection.get());assertNotSame(listener.get(),connection.get());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            for(var loop:List.of(listener.get(),connection.get())) {
                long remaining=deadline-System.nanoTime();assertTrue(remaining>0);
                assertTrue(loop.terminationFuture().await(remaining,TimeUnit.NANOSECONDS));
                assertTrue(loop.terminationFuture().isSuccess());assertTrue(loop.isTerminated());
            }
        }
    }
    String body(){return "{\"volumeIdentity\":\""+volume+"\",\"freeBytes\":4294967296,\"totalBytes\":8589934592}";}
    @Test void trustedHttpObservationRecoversAdmissionAndExpiryOrWrongVolumeCannotForgeHealth() throws Exception {
        try(var f=new Fixture()) {
            assertThrows(LogFailure.class,observation::requireWritable);
            var response=f.post(body(),"observer");assertEquals(204,response.statusCode(),response.body());assertTrue(response.body().isEmpty());
            assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());observation.requireWritable();
            assertTrue(thread.get().contains("eventLoopGroup"),thread.get());
            assertEquals(403,f.post(body().replace(volume,"a".repeat(64)),"observer").statusCode());observation.requireWritable();
            assertEquals(403,f.post(body().replace(volume,"a".repeat(64)).replace("4294967296","null"),"observer").statusCode());observation.requireWritable();
            assertEquals(403,f.post(body(),"expired").statusCode());assertEquals(503,f.post(body(),"unavailable").statusCode());
            var safe=f.post(body(),"unexpected");assertEquals(503,safe.statusCode());assertFalse(safe.body().contains("private"));
            now.updateAndGet(v->v.plusSeconds(6));assertThrows(LogFailure.class,observation::requireWritable);
            assertEquals(204,f.post(body(),"observer").statusCode());observation.requireWritable();
            assertEquals(204,f.post(body().replace("4294967296","1"),"observer").statusCode());assertThrows(LogFailure.class,observation::requireWritable);
        }
    }
    @Test void strictHttpSchemaNumbersUtf8BoundsAndAuthentication() throws Exception {
        try(var f=new Fixture()) {
            for(String bad:List.of("reader","producer","registrar"))assertEquals(401,f.post("invalid",bad).statusCode());
            assertEquals(401,f.post(body().getBytes(StandardCharsets.UTF_8),"").statusCode());
            assertEquals(401,f.post(body().getBytes(StandardCharsets.UTF_8),"","Authorization","Basic observer").statusCode());
            assertEquals(401,f.post(body().getBytes(StandardCharsets.UTF_8),"","Authorization","Bearer observer","Authorization","Bearer observer").statusCode());
            for(String bad:List.of("null","[]","{}",body()+"{}",body().replace("\"freeBytes\":", "\"freeBytes\":0,\"freeBytes\":"),body().replace("4294967296","null"),
                    body().replace("4294967296","1.0"),body().replace("4294967296","1e3"),body().replace("4294967296","\"private\""),body().replace("4294967296","true"),
                    body().replace("4294967296","-1"),body().replace("8589934592","0"),body().replace("8589934592","9007199254740992"),
                    body().replace("8589934592","1"),body().replace(volume,"bad"),body().replace("\"volumeIdentity\":\""+volume+"\"","\"volumeIdentity\":7"),
                    body().replace("}",",\"timestamp\":\"2026-11-02T00:00:00Z\"}"))) {
                assertEquals(204,f.post(body(),"observer").statusCode());assertEquals(400,f.post(bad,"observer").statusCode(),bad);
            }
            for(var charset:List.of(StandardCharsets.UTF_16BE,StandardCharsets.UTF_16LE))
                assertEquals(400,f.post(body().getBytes(charset),"","Authorization","Bearer observer").statusCode());
            assertEquals(400,f.post(new byte[]{(byte)0xff},"","Authorization","Bearer observer").statusCode());
            assertEquals(413,f.post(" ".repeat(1025),"observer").statusCode());
            assertEquals(400,f.post(body().getBytes(StandardCharsets.UTF_8),"?volume=private","Authorization","Bearer observer").statusCode());
            assertEquals(400,f.post(body().getBytes(StandardCharsets.UTF_8),"","Authorization","Bearer observer","Content-Encoding","identity").statusCode());
        }
    }
    @Test void observerHttpProgressesWhileBothBlockingLoggingPoolsAreFull() throws Exception {
        try(var f=new Fixture()) {
            var entered=new CountDownLatch(64);var release=new CountDownLatch(1);
            Runnable hold=()->{entered.countDown();try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}};
            var responses=f.context.getBean(ExecutorService.class,Qualifiers.byName(LogResponseExecutor.NAME));
            try {
                for(int i=0;i<32;i++){responses.execute(hold);f.requests.submit(deadline->{hold.run();return null;});}
                assertTrue(entered.await(2,TimeUnit.SECONDS));
                assertEquals(204,f.post(body(),"observer").statusCode());observation.requireWritable();
                assertTrue(thread.get().contains("eventLoopGroup"),thread.get());
            } finally {release.countDown();}
        }
    }
    @Test void controlHasTwoOwnedSlotsAndFinallyReleasesAfterRejection() throws Exception {
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        LogObserverAuthentication blocking=token->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}};
        var controller=new LogStorageObservationController(blocking,observation);
        java.util.function.Supplier<io.micronaut.http.HttpRequest<?>> request=()->{
            var r=io.micronaut.http.HttpRequest.POST("/api/v1/dev-logs/storage-observation",body()).contentType("application/json").bearerAuth("observer");
            new LogRouteDeadline().admit(r);return r;
        };
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=workers.submit(()->controller.update(request.get(),body().getBytes(StandardCharsets.UTF_8)));
            var b=workers.submit(()->controller.update(request.get(),body().getBytes(StandardCharsets.UTF_8)));
            try {assertTrue(entered.await(2,TimeUnit.SECONDS));assertEquals(429,controller.update(request.get(),body().getBytes(StandardCharsets.UTF_8)).code());}
            finally {release.countDown();}
            assertEquals(204,a.get(2,TimeUnit.SECONDS).code());assertEquals(204,b.get(2,TimeUnit.SECONDS).code());
            assertEquals(204,controller.update(request.get(),body().getBytes(StandardCharsets.UTF_8)).code());
        }
    }
}
