package org.documentknowledge.browserbff.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class IngressBackpressureTest {
    static volatile Map<String,Long> lastCredit = Map.of(), lastPipeline = Map.of();
    @Test void actualPendingQueueStopsAtInitialCreditAndSingleReceiveBurstWithIndependentBridgePieces()throws Exception {
        try(var server=ApplicationContext.run(EmbeddedServer.class,Map.of("micronaut.server.port",-1,"micronaut.server.host","127.0.0.1","dkp338.body-witness","true","dkp338.channel-observer","true"));var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            var witness=server.getApplicationContext().getBean(IngressBodyWitness.class);
            int size=1_000_000;
            var pending=client.sendAsync(HttpRequest.newBuilder(server.getURI().resolve("/dkp338/body")).header("Content-Type","application/octet-stream").POST(HttpRequest.BodyPublishers.ofByteArray(new byte[size])).build(),HttpResponse.BodyHandlers.ofString());
            assertTrue(witness.saturated.await(5,TimeUnit.SECONDS));assertNull(witness.witnessFailure);
            assertTrue(witness.saturatedCredit<=0);assertTrue(witness.saturatedQueue>=65535);
            assertTrue(witness.saturatedQueue<=65535+8192,"Measured pending queue="+witness.saturatedQueue);
            assertFalse(pending.isDone());assertEquals(1,witness.registered.get());
            var channel=witness.channels.poll(1,TimeUnit.SECONDS);assertNotNull(channel);
            witness.begin.countDown();assertTrue(witness.currentHeld.await(3,TimeUnit.SECONDS));
            var bridge=witness.bridge();assertTrue(bridge[0]>0);assertTrue(bridge[0]<=8192);assertTrue(bridge[1]<=8192);
            channel.eventLoop().submit(()->witness.sample(channel)).sync();
            assertNull(witness.witnessFailure);assertTrue(witness.queueHigh.get()<=131072);
            assertTrue(witness.queueHigh.get()+bridge[0]+bridge[1]<=147456);
            witness.resume.countDown();var result=pending.get(8,TimeUnit.SECONDS);assertEquals(200,result.statusCode());assertEquals(Integer.toString(size),result.body());
            assertTrue(witness.rawHigh.get()<=8192);
            lastCredit=Map.of("pausedQueueBytes",witness.saturatedQueue,"readCompleteQueueHigh",witness.queueHigh.get(),"creditAtPause",witness.saturatedCredit,"currentBridgeBytes",bridge[0],"swapBridgeBytes",bridge[1],"observedReceiveHigh",witness.rawHigh.get());
        }
    }

    @Test void legalCoalescedPipelineRetainsTwoIndependentRequestBodiesWithoutGlobalCapOrDeadlock()throws Exception {
        try(var server=ApplicationContext.run(EmbeddedServer.class,Map.of("micronaut.server.port",-1,"micronaut.server.host","127.0.0.1","dkp338.body-witness","true","dkp338.channel-observer","true"));
            var socket=new Socket("127.0.0.1",server.getPort());var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            socket.setSoTimeout(5000);var witness=server.getApplicationContext().getBean(IngressBodyWitness.class);
            int size=1_000_000;
            var bytes=new ByteArrayOutputStream();
            bytes.write(("POST /dkp338/body HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/octet-stream\r\nContent-Length: 1\r\n\r\nx"+
                "POST /dkp338/body HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/octet-stream\r\nContent-Length: "+size+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            bytes.write(new byte[size]);
            var writer=executor.submit(()->{socket.getOutputStream().write(bytes.toByteArray());socket.getOutputStream().flush();return true;});
            assertNotNull(witness.bodies.poll(3,TimeUnit.SECONDS));
            assertTrue(witness.pipelineHeld.await(3,TimeUnit.SECONDS));assertNull(witness.witnessFailure);
            assertEquals(2,witness.requestsSeen.get());assertEquals(2,witness.frameworkRetainedHigh.get(),"Count both framework-retained requests while first response is stalled");
            assertTrue(witness.availableRetainedBytes.get()<=8192);
            var channel=witness.channels.poll(1,TimeUnit.SECONDS);assertNotNull(channel);
            channel.eventLoop().submit(()->witness.sample(channel)).sync();assertNull(witness.witnessFailure);
            assertTrue(witness.queueHigh.get()<=131072);
            for(var high:witness.retained.values())assertTrue(high.get()<=131072);
            assertTrue(witness.queueHigh.get()+witness.availableRetainedBytes.get()<=2L*147456,
                "Two simultaneously retained request bodies each own a separate allowance; this is no connection/global cap");
            witness.begin.countDown();assertTrue(witness.currentHeld.await(3,TimeUnit.SECONDS));witness.resume.countDown();
            var input=new BufferedInputStream(socket.getInputStream());
            assertEquals("1",response(input));assertEquals(Integer.toString(size),response(input));
            assertEquals(2,witness.registered.get());assertTrue(writer.get(5,TimeUnit.SECONDS));assertTrue(witness.rawHigh.get()<=8192);assertNull(witness.witnessFailure);
            lastPipeline=Map.of("retainedRequests",(long)witness.frameworkRetainedHigh.get(),"perRequestQueueHigh",witness.queueHigh.get(),"availableFirstBodyBytes",witness.availableRetainedBytes.get(),"observedReceiveHigh",witness.rawHigh.get());
        }
    }
    static String response(InputStream input)throws Exception {
        String status=line(input);assertTrue(status.startsWith("HTTP/1.1 200"),status);int length=-1;String header;
        while(!(header=line(input)).isEmpty())if(header.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).strip());
        assertTrue(length>=0);return new String(input.readNBytes(length),StandardCharsets.US_ASCII);
    }
    static String line(InputStream input)throws IOException {
        var bytes=new ByteArrayOutputStream();int value;
        while((value=input.read())!=-1){if(value=='\n')return bytes.toString(StandardCharsets.US_ASCII).stripTrailing();bytes.write(value);}
        throw new EOFException("Expected finite HTTP response line");
    }
}
