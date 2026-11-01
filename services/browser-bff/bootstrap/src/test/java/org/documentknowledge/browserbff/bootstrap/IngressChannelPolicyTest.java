package org.documentknowledge.browserbff.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.server.netty.configuration.NettyHttpServerConfiguration;
import io.micronaut.runtime.server.EmbeddedServer;
import io.netty.channel.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.documentknowledge.browserbff.application.Readiness;
import org.junit.jupiter.api.Test;

class IngressChannelPolicyTest {
    static EmbeddedServer start() { return ApplicationContext.run(EmbeddedServer.class, Map.of("micronaut.server.port",-1,"micronaut.server.host","127.0.0.1","dkp338.channel-observer","true")); }
    static String firstLine(Socket socket) throws IOException {
        var b = new ByteArrayOutputStream(); int value;
        while ((value=socket.getInputStream().read())!=-1) { b.write(value); if(value=='\n') break; }
        return b.toString(StandardCharsets.US_ASCII);
    }
    static void send(Socket socket,String request) throws IOException { socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush(); }
    @Test void acceptedRealConnectionHasSelectedAllocatorAndAutoReadBeforeAnyBody() throws Exception {
        try(var server=start();var socket=new Socket("127.0.0.1",server.getPort())) {
            socket.setSoTimeout(3000);send(socket,"GET /health/liveness HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            assertTrue(firstLine(socket).contains("200"));
            var observer=server.getApplicationContext().getBean(IngressChannelObserver.class);
            assertNotNull(observer.channels.poll(3,TimeUnit.SECONDS));
            assertFalse(observer.initialAutoRead);assertEquals(8192,observer.initialGuess);assertEquals(1,observer.initialMaxMessages);
        }
    }
    @Test void laterAllocatorReadOrGlobalPolicyMutationClosesBeforeProtectedDispatchAndLatchesReadiness() throws Exception {
        for(String mutation:List.of("allocator","messages","autoread","decoder","decompression","queue","codec","rawguard","decodedguard","aggregator","inflater")) {
            try(var server=start();var socket=new Socket("127.0.0.1",server.getPort())) {
                socket.setSoTimeout(3000);
                var context=server.getApplicationContext();var observer=context.getBean(IngressChannelObserver.class);
                var channel=observer.channels.poll(3,TimeUnit.SECONDS);assertNotNull(channel);
                var config=context.getBean(NettyHttpServerConfiguration.class);
                channel.eventLoop().submit(()->{
                    switch(mutation) {
                        case "allocator" -> channel.config().setRecvByteBufAllocator(new FixedRecvByteBufAllocator(16384));
                        case "messages" -> ((MaxMessagesRecvByteBufAllocator)channel.config().getRecvByteBufAllocator()).maxMessagesPerRead(2);
                        case "autoread" -> channel.config().setAutoRead(true);
                        case "decoder" -> config.setMaxChunkSize(16384);
                        case "decompression" -> config.setRequestDecompressionEnabled(true);
                        case "queue" -> config.setMaxRequestBufferSize(262144);
                        case "codec" -> channel.pipeline().remove(io.netty.handler.codec.http.HttpServerCodec.class);
                        case "rawguard" -> channel.pipeline().remove("dkp-ingress-receive-policy");
                        case "decodedguard" -> channel.pipeline().remove("dkp-ingress-decoded-policy");
                        case "aggregator" -> channel.pipeline().addAfter("dkp-ingress-decoded-policy","unselected-aggregation",new io.netty.handler.codec.http.HttpObjectAggregator(100000000));
                        case "inflater" -> channel.pipeline().addAfter("dkp-ingress-decoded-policy","unselected-decompression",new io.netty.handler.codec.http.HttpContentDecompressor());
                    }
                }).sync();
                send(socket,"GET /api/v1/session HTTP/1.1\r\nHost: localhost\r\n\r\n");
                assertEquals("",firstLine(socket),mutation);
                assertFalse(context.getBean(Readiness.class).isAcceptingWork(),mutation);
                config.setMaxChunkSize(8192);config.setRequestDecompressionEnabled(false);config.setMaxRequestBufferSize(131072);
                try(var client=HttpClient.newHttpClient()) {
                    assertEquals(503,client.send(HttpRequest.newBuilder(server.getURI().resolve("/health/readiness")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
                    assertEquals(200,client.send(HttpRequest.newBuilder(server.getURI().resolve("/health/liveness")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
                }
            }
        }
    }
    @Test void sameActualMicronautHandlerPolicyMutationClosesAndLatchesBeforeAnyProtectedHandoff() throws Exception {
        for (String mutation : List.of("hidden-buffer", "hidden-body", "hidden-decompression", "hidden-null")) {
            try (var server = start(); var socket = new Socket("127.0.0.1", server.getPort())) {
                socket.setSoTimeout(3000);
                var context = server.getApplicationContext();
                var observer = context.getBean(IngressChannelObserver.class);
                var channel = observer.channels.poll(3, TimeUnit.SECONDS);
                assertNotNull(channel);
                channel.eventLoop().submit(() -> {
                    var handler = channel.pipeline().get(io.micronaut.http.server.netty.handler.PipeliningServerHandler.class);
                    assertNotNull(handler);
                    switch (mutation) {
                        case "hidden-buffer" -> handler.setBodySizeLimits(new io.micronaut.http.body.stream.BodySizeLimits(100000000, 262144));
                        case "hidden-body" -> handler.setBodySizeLimits(new io.micronaut.http.body.stream.BodySizeLimits(100000001, 131072));
                        case "hidden-decompression" -> handler.setRequestDecompressionEnabled(true);
                        case "hidden-null" -> handler.setBodySizeLimits(null);
                    }
                }).sync();
                send(socket, "GET /api/v1/session HTTP/1.1\r\nHost: localhost\r\n\r\n");
                assertEquals("", firstLine(socket), mutation);
                assertFalse(context.getBean(Readiness.class).isAcceptingWork(), mutation);
            }
        }
    }
    @Test void optionalH2cOfferRemainsPlainHttp11AndNeverSwitchesProtocol() throws Exception {
        try(var server=start();var socket=new Socket("127.0.0.1",server.getPort())) {
            socket.setSoTimeout(3000);
            send(socket,"GET /api/v1/session HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade, HTTP2-Settings\r\nUpgrade: h2c\r\nHTTP2-Settings: AAMAAABk\r\n\r\n");
            assertTrue(firstLine(socket).contains("401"));
            assertTrue(server.getApplicationContext().getBean(Readiness.class).isAcceptingWork());
        }
    }
    @Test void priorKnowledgeAndHttp10NeverDispatchProtectedOperations() throws Exception {
        for(String input:List.of(
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n",
            "GET /api/v1/session HTTP/1.0\r\n\r\n")) {
            try(var server=start();var socket=new Socket("127.0.0.1",server.getPort())) {
                socket.setSoTimeout(3000);send(socket,input);assertEquals("",firstLine(socket));
                assertFalse(server.getApplicationContext().getBean(Readiness.class).isAcceptingWork());
            }
        }
    }
}
