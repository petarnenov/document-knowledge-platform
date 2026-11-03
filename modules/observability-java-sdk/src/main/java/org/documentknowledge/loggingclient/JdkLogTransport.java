package org.documentknowledge.loggingclient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;

/** Fixed two-route HTTPS adapter. No ambient proxy, redirect, credential lookup or unbounded response. */
public final class JdkLogTransport implements LogTransport {
    private final URI endpoint;
    private final String token;
    private final HttpClient client;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean closed=new AtomicBoolean();

    public JdkLogTransport(URI endpoint,String producerToken,SSLContext tls) {
        Objects.requireNonNull(endpoint);Objects.requireNonNull(tls);
        if(!"https".equals(endpoint.getScheme()) || endpoint.getHost()==null || endpoint.getUserInfo()!=null
                || endpoint.getRawQuery()!=null || endpoint.getRawFragment()!=null
                || !"/api/v1/dev-logs".equals(endpoint.getRawPath())
                || producerToken==null || !producerToken.matches("[A-Za-z0-9_-]{32,512}"))
            throw new IllegalArgumentException("INVALID_LOG_TRANSPORT_CONFIG");
        this.endpoint=endpoint;token=producerToken;
        executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),
            Thread.ofPlatform().daemon().name("dev-log-http-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
        client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).sslContext(tls)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(1)).executor(executor)
            .proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI ignored) {return List.of(Proxy.NO_PROXY);}
                @Override public void connectFailed(URI uri,SocketAddress address,IOException failure) {}
            }).build();
    }

    @Override public Response post(Route route,byte[] body) throws IOException,InterruptedException {
        Objects.requireNonNull(route);
        if(closed.get() || body==null || body.length==0 || body.length>32768)throw new IOException("LOG_HTTP_UNAVAILABLE");
        String path=route==Route.BATCHES?"batches":"receipts";
        var request=HttpRequest.newBuilder(URI.create(endpoint.toASCIIString()+"/"+path)).timeout(Duration.ofSeconds(5))
            .header("Content-Type","application/json").header("Accept","application/json")
            .header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.ofByteArray(body.clone())).build();
        CompletableFuture<HttpResponse<byte[]>> future=null;
        try {
            future=client.sendAsync(request,info->new LimitedBody());
            var response=future.get(5,TimeUnit.SECONDS);
            if(!response.headers().allValues("Content-Encoding").isEmpty()
                    || (response.statusCode()==200 && !response.headers().firstValue("Content-Type").orElse("")
                        .split(";",2)[0].trim().equalsIgnoreCase("application/json")))throw new IOException("LOG_HTTP_UNAVAILABLE");
            return new Response(response.statusCode(),response.body());
        } catch(ExecutionException|TimeoutException|RuntimeException unavailable) {
            throw new IOException("LOG_HTTP_UNAVAILABLE");
        } finally {if(future!=null && !future.isDone())future.cancel(true);}
    }

    @Override public void close() {
        if(closed.compareAndSet(false,true)) {client.shutdownNow();executor.shutdownNow();}
    }

    @Override public java.util.Optional<String> credentialFingerprint() {
        try {
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(
                ("dkp-log-producer-credential-v1:"+token).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return java.util.Optional.of(java.util.HexFormat.of().formatHex(digest));
        } catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException("LOG_DIGEST_UNAVAILABLE");}
    }

    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream(2048);
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() {return result;}
        @Override public void onSubscribe(Flow.Subscription value) {
            if(subscription!=null || result.isDone()) {value.cancel();return;}
            subscription=value;value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if(result.isDone())return;
            for(var buffer:buffers) {
                int size=buffer.remaining();
                if(size>32768-bytes.size()) {
                    subscription.cancel();result.completeExceptionally(new IOException("LOG_HTTP_RESPONSE_TOO_LARGE"));return;
                }
                byte[] chunk=new byte[size];buffer.get(chunk);bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable ignored) {result.completeExceptionally(new IOException("LOG_HTTP_UNAVAILABLE"));}
        @Override public void onComplete() {result.complete(bytes.toByteArray());}
    }
}
