package org.documentknowledge.inspector;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.SSLContext;
import org.documentknowledge.inspector.diagnostics.DiagnosticHeaders;
import org.documentknowledge.inspector.diagnostics.InspectorDiagnostics;
import org.documentknowledge.inspector.diagnostics.InspectorDiagnostics.*;

/** Private TLS-only fixed protocol; sandbox deployment supplies the network/OS resource boundary. */
public final class InspectorServer implements AutoCloseable {
    private final HttpsServer server;
    private final ExecutorService executor;
    private final ScheduledExecutorService watchdog;
    private final Semaphore permit=new Semaphore(1);
    private final StructureInspector inspector;
    private final byte[] authorization;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final Runnable expired;
    private final Duration deadline;
    private final InspectorDiagnostics diagnostics;
    public InspectorServer(InetSocketAddress address,SSLContext tls,byte[] serviceKey,StructureInspector inspector) throws IOException {
        this(address,tls,serviceKey,inspector,InspectorDiagnostics.NOOP);
    }
    public InspectorServer(InetSocketAddress address,SSLContext tls,byte[] serviceKey,StructureInspector inspector,InspectorDiagnostics diagnostics) throws IOException {
        this(address,tls,serviceKey,inspector,Duration.ofSeconds(60),()->Runtime.getRuntime().halt(70),diagnostics);
    }
    InspectorServer(InetSocketAddress address,SSLContext tls,byte[] serviceKey,StructureInspector inspector,Duration deadline,Runnable expired) throws IOException {
        this(address,tls,serviceKey,inspector,deadline,expired,InspectorDiagnostics.NOOP);
    }
    InspectorServer(InetSocketAddress address,SSLContext tls,byte[] serviceKey,StructureInspector inspector,Duration deadline,Runnable expired,InspectorDiagnostics diagnostics) throws IOException {
        this.diagnostics=InspectorDiagnostics.safe(diagnostics);
        if(serviceKey.length!=32)throw new IllegalArgumentException("service key");
        this.inspector=inspector;this.deadline=deadline;this.expired=expired;
        authorization=("Bearer "+Base64.getEncoder().encodeToString(serviceKey)).getBytes(StandardCharsets.US_ASCII);
        server=HttpsServer.create(address,8);server.setHttpsConfigurator(new HttpsConfigurator(tls));
        executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),Thread.ofPlatform().daemon().factory(),new ThreadPoolExecutor.AbortPolicy());
        watchdog=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());
        server.setExecutor(executor);server.createContext("/",this::handle);
    }
    public void start() {server.start();}
    public int port() {return server.getAddress().getPort();}
    private void handle(HttpExchange exchange) throws IOException {
        boolean acquired=false,failed=false,capacity=false;ScheduledFuture<?> timer=null;var response=new Response(exchange);
        String route=exchange.getRequestURI().getRawPath();
        boolean health=exchange.getRequestURI().getRawQuery()==null && Set.of("/health/liveness","/health/readiness").contains(route) && exchange.getRequestMethod().equals("GET");
        var context=health?Context.fresh():DiagnosticHeaders.read(exchange.getRequestHeaders().get("X-Correlation-Id"),exchange.getRequestHeaders().get("traceparent"),diagnostics);
        var request=health?InspectorDiagnostics.NONE:diagnostics.started(context,Phase.REQUEST);
        try {
            if(exchange.getRequestURI().getRawQuery()!=null) {response.error(400,"INVALID_REQUEST");return;}
            if(Set.of("/health/liveness","/health/readiness").contains(route) && exchange.getRequestMethod().equals("GET")) {response.respond(200,"{\"status\":\"UP\"}".getBytes(StandardCharsets.US_ASCII));return;}
            if(!route.equals("/internal/v1/inspect") || !exchange.getRequestMethod().equals("POST")) {response.error(404,"NOT_FOUND");return;}
            String auth=one(exchange,"Authorization");
            if(auth==null || !MessageDigest.isEqual(authorization,auth.getBytes(StandardCharsets.US_ASCII))) {response.error(401,"UNAUTHENTICATED");return;}
            if(!"application/octet-stream".equals(one(exchange,"Content-Type"))) {response.error(415,"UNSUPPORTED_MEDIA_TYPE");return;}
            String family=one(exchange,"X-DKP-Format");Format format;
            try {format=Format.valueOf(family==null?"":family);}catch(IllegalArgumentException invalid) {response.error(400,"INVALID_REQUEST");return;}
            for(String header:exchange.getRequestHeaders().keySet())if(header.toLowerCase(Locale.ROOT).startsWith("x-dkp-") && !header.equalsIgnoreCase("X-DKP-Format")) {response.error(400,"INVALID_REQUEST");return;}
            String length=one(exchange,"Content-Length");
            if(length!=null) {try {long n=Long.parseLong(length);if(n<1)throw new NumberFormatException();if(n>StructureInspector.MAX_BYTES){response.error(413,"PAYLOAD_TOO_LARGE");return;}}catch(NumberFormatException invalid){response.error(400,"INVALID_REQUEST");return;}}
            if(!permit.tryAcquire()) {capacity=true;response.error(503,"UNAVAILABLE");return;}acquired=true;
            timer=watchdog.schedule(()->{
                var timeout=diagnostics.started(context,Phase.DEADLINE);
                timeout.completed(Outcome.FAILED,new Facts(null,0L,0,false,InspectorDiagnostics.Error.DEADLINE_EXCEEDED));expired.run();
            },deadline.toMillis(),TimeUnit.MILLISECONDS);
            try(InputStream body=exchange.getRequestBody()) {
                var result=inspector.inspect(body,format,context);
                if(length!=null && result.sizeBytes()!=Long.parseLong(length))throw new InspectionFailure("INVALID_FILE");
                response.respond(200,JSON.writeValueAsBytes(result));
            }
        } catch(InspectionFailure invalid) {failed=true;request.failure(invalid);response.error(invalid.code().equals("UNAVAILABLE")?503:invalid.code().equals("PAYLOAD_TOO_LARGE")?413:invalid.code().equals("INVALID_REQUEST")?400:422,invalid.code());}
        catch(Exception unavailable) {failed=true;request.failure(unavailable);response.error(503,"UNAVAILABLE");}
        finally {
            if(timer!=null)timer.cancel(false);if(acquired)permit.release();exchange.close();
            boolean success=!failed && response.status!=null && response.status<400;
            request.completed(success?Outcome.SUCCEEDED:Outcome.FAILED,new Facts(response.status,null,0,false,success?null:capacity?InspectorDiagnostics.Error.CAPACITY:errorCode(response.status)));
        }
    }
    private static String one(HttpExchange exchange,String name) {List<String> values=exchange.getRequestHeaders().get(name);if(values==null)return null;if(values.size()!=1)throw new InspectionFailure("INVALID_REQUEST");return values.getFirst();}
    private static InspectorDiagnostics.Error errorCode(Integer status) {
        if(status==null)return InspectorDiagnostics.Error.OPERATION_FAILED;
        return switch(status) {
            case 401->InspectorDiagnostics.Error.UNAUTHENTICATED;
            case 404->InspectorDiagnostics.Error.NOT_FOUND;
            case 400,413,415,422->InspectorDiagnostics.Error.INVALID_INPUT;
            default->InspectorDiagnostics.Error.OPERATION_FAILED;
        };
    }
    private static final class Response {
        private final HttpExchange exchange;private Integer status;
        private Response(HttpExchange exchange){this.exchange=exchange;}
        void error(int status,String code) throws IOException {respond(status,JSON.writeValueAsBytes(Map.of("code",code)));}
        void respond(int status,byte[] bytes) throws IOException {
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
            exchange.sendResponseHeaders(status,bytes.length);this.status=status;exchange.getResponseBody().write(bytes);
        }
    }
    @Override public void close() {server.stop(0);executor.shutdownNow();watchdog.shutdownNow();}
}
