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
    public InspectorServer(InetSocketAddress address,SSLContext tls,byte[] serviceKey,StructureInspector inspector) throws IOException {
        this(address,tls,serviceKey,inspector,Duration.ofSeconds(60),()->Runtime.getRuntime().halt(70));
    }
    InspectorServer(InetSocketAddress address,SSLContext tls,byte[] serviceKey,StructureInspector inspector,Duration deadline,Runnable expired) throws IOException {
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
        boolean acquired=false;ScheduledFuture<?> timer=null;
        try {
            String route=exchange.getRequestURI().getRawPath();
            if(exchange.getRequestURI().getRawQuery()!=null) {error(exchange,400,"INVALID_REQUEST");return;}
            if(Set.of("/health/liveness","/health/readiness").contains(route) && exchange.getRequestMethod().equals("GET")) {respond(exchange,200,"{\"status\":\"UP\"}".getBytes(StandardCharsets.US_ASCII));return;}
            if(!route.equals("/internal/v1/inspect") || !exchange.getRequestMethod().equals("POST")) {error(exchange,404,"NOT_FOUND");return;}
            String auth=one(exchange,"Authorization");
            if(auth==null || !MessageDigest.isEqual(authorization,auth.getBytes(StandardCharsets.US_ASCII))) {error(exchange,401,"UNAUTHENTICATED");return;}
            if(!"application/octet-stream".equals(one(exchange,"Content-Type"))) {error(exchange,415,"UNSUPPORTED_MEDIA_TYPE");return;}
            String family=one(exchange,"X-DKP-Format");Format format;
            try {format=Format.valueOf(family==null?"":family);}catch(IllegalArgumentException invalid) {error(exchange,400,"INVALID_REQUEST");return;}
            for(String header:exchange.getRequestHeaders().keySet())if(header.toLowerCase(Locale.ROOT).startsWith("x-dkp-") && !header.equalsIgnoreCase("X-DKP-Format")) {error(exchange,400,"INVALID_REQUEST");return;}
            String length=one(exchange,"Content-Length");
            if(length!=null) {try {long n=Long.parseLong(length);if(n<1)throw new NumberFormatException();if(n>StructureInspector.MAX_BYTES){error(exchange,413,"PAYLOAD_TOO_LARGE");return;}}catch(NumberFormatException invalid){error(exchange,400,"INVALID_REQUEST");return;}}
            if(!permit.tryAcquire()) {error(exchange,503,"UNAVAILABLE");return;}acquired=true;
            timer=watchdog.schedule(expired,deadline.toMillis(),TimeUnit.MILLISECONDS);
            try(InputStream body=exchange.getRequestBody()) {
                var result=inspector.inspect(body,format);
                if(length!=null && result.sizeBytes()!=Long.parseLong(length))throw new InspectionFailure("INVALID_FILE");
                respond(exchange,200,JSON.writeValueAsBytes(result));
            }
        } catch(InspectionFailure invalid) {error(exchange,invalid.code().equals("UNAVAILABLE")?503:invalid.code().equals("PAYLOAD_TOO_LARGE")?413:invalid.code().equals("INVALID_REQUEST")?400:422,invalid.code());}
        catch(Exception unavailable) {error(exchange,503,"UNAVAILABLE");}
        finally {if(timer!=null)timer.cancel(false);if(acquired)permit.release();exchange.close();}
    }
    private static String one(HttpExchange exchange,String name) {List<String> values=exchange.getRequestHeaders().get(name);if(values==null)return null;if(values.size()!=1)throw new InspectionFailure("INVALID_REQUEST");return values.getFirst();}
    private static void error(HttpExchange exchange,int status,String code) throws IOException {respond(exchange,status,JSON.writeValueAsBytes(Map.of("code",code)));}
    private static void respond(HttpExchange exchange,int status,byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type","application/json");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
    }
    @Override public void close() {server.stop(0);executor.shutdownNow();watchdog.shutdownNow();}
}
