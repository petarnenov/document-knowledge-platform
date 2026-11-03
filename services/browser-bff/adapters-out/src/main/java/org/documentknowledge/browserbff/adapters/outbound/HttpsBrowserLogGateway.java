package org.documentknowledge.browserbff.adapters.outbound;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import org.documentknowledge.browserbff.application.*;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Server-only source bridge. One owned HTTPS client; no business outbound pool or ambient credentials. */
public final class HttpsBrowserLogGateway implements BrowserLogRecoveryGateway, AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(32768).maxNestingDepth(16)
            .maxStringLength(512).maxNumberLength(16).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final URI endpoint;
    private final UUID parent;
    private final String delegator;
    private final Clock clock;
    private final BrowserLogScopeCheckpoint.Binding recovery;
    private final HttpClient client;
    private final ThreadPoolExecutor callbacks;
    private final AtomicBoolean closed = new AtomicBoolean();

    private final class Credential implements Scope {
        final String token;
        final Instant replayUntil;
        final BrowserLogScopeCheckpoint.Recovered checkpoint;
        Credential(BrowserLogScopeCheckpoint.Recovered checkpoint) {
            this.checkpoint = checkpoint; token = checkpoint.token(); replayUntil = checkpoint.replayUntil();
        }
        HttpsBrowserLogGateway owner() { return HttpsBrowserLogGateway.this; }
        @Override public String toString() { return "BrowserLogScope[REDACTED]"; }
    }

    public HttpsBrowserLogGateway(URI endpoint, UUID parent, String delegator, SSLContext tls, Clock clock) {
        this(endpoint, parent, delegator, tls, clock, null, Map.of());
    }
    public HttpsBrowserLogGateway(URI endpoint, UUID parent, String delegator, SSLContext tls, Clock clock,
                                  String caSha256, Map<UUID, BrowserLogContexts.Artifact> artifacts) {
        Objects.requireNonNull(endpoint); Objects.requireNonNull(tls); this.clock = Objects.requireNonNull(clock);
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || !"/api/v1/dev-logs".equals(endpoint.getRawPath()) || parent == null || parent.equals(new UUID(0, 0))
                || !token(delegator)) throw new IllegalArgumentException("INVALID_BROWSER_LOG_CONFIGURATION");
        this.endpoint = endpoint; this.parent = parent; this.delegator = delegator;
        recovery = caSha256 == null ? null : new BrowserLogScopeCheckpoint.Binding(endpoint, parent, caSha256, artifacts);
        callbacks = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
            Thread.ofPlatform().daemon().name("bff-log-https-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).sslContext(tls)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(1)).executor(callbacks)
            .proxy(new DirectProxy()).build();
    }

    /** A connection failure never consults an ambient fallback proxy. */
    static final class DirectProxy extends ProxySelector {
        @Override public List<Proxy> select(URI ignored) { return List.of(Proxy.NO_PROXY); }
        @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
    }

    @Override public Scope delegate(Grant grant, BrowserLogDeadline deadline) {
        try {
            var artifact = grant.artifact(); var authority = grant.authority();
            if (!clock.instant().isBefore(grant.captureUntil())) throw unavailable();
            var binding = Map.of("buildId", artifact.buildId().toString(), "runId", grant.runId().toString(),
                "component", artifact.component(), "gitSha", artifact.gitSha(), "sourceFingerprint", artifact.sourceFingerprint(),
                "manifestSha256", artifact.manifestSha256(), "vocabularySha256", artifact.vocabularySha256());
            var scope = new HashMap<String, Object>();
            scope.put("scopeKind", authority.tenantId() == null ? "AUTHENTICATED_UNSELECTED" : "TENANT");
            scope.put("issuer", authority.issuer()); scope.put("subject", authority.subject());
            if (authority.tenantId() != null) scope.put("tenantId", authority.tenantId());
            byte[] body = JSON.writeValueAsBytes(Map.of("parentProducerId", parent.toString(), "binding", binding,
                "scope", scope, "contextEpoch", grant.contextEpoch().toString(), "captureUntil", grant.captureUntil().toString()));
            byte[] raw = post("delegations", delegator, body, 201, deadline);
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
            var reply = JSON.readValue(text, Map.class);
            if (!reply.keySet().equals(Set.of("producerId", "scopeId", "captureUntil", "replayUntil", "producerToken"))
                    || !parent.toString().equals(reply.get("producerId"))) throw unavailable();
            id(reply.get("scopeId"));
            Instant capture = Instant.parse((String) reply.get("captureUntil"));
            Instant replay = Instant.parse((String) reply.get("replayUntil"));
            if (!capture.equals(grant.captureUntil().truncatedTo(java.time.temporal.ChronoUnit.MICROS)) || replay.isBefore(capture) || !clock.instant().isBefore(replay)
                    || !token(reply.get("producerToken"))) throw unavailable();
            deadline.requireTime();
            return new Credential(new BrowserLogScopeCheckpoint.Recovered(grant, UUID.fromString((String) reply.get("scopeId")),
                (String) reply.get("producerToken"), replay));
        } catch (Exception invalid) { throw unavailable(); }
    }

    @Override public byte[] ingest(Scope scope, byte[] sanitizedBatch, BrowserLogDeadline deadline) {
        try { return forward(scope, sanitizedBatch, deadline); } catch (Exception failure) { throw unavailable(); }
    }
    @Override public byte[] receipts(Scope scope, byte[] eventIds, BrowserLogDeadline deadline) {
        try { return post("receipts", credential(scope).token, eventIds, 200, deadline); }
        catch (Exception failure) { throw unavailable(); }
    }
    @Override public byte[] checkpoint(Scope scope) {
        if (recovery == null) throw unavailable();
        return BrowserLogScopeCheckpoint.encode(recovery, credential(scope).checkpoint);
    }
    @Override public Scope restore(byte[] checkpoint) {
        if (recovery == null) throw unavailable();
        return new Credential(BrowserLogScopeCheckpoint.decode(recovery, checkpoint));
    }
    @Override public boolean expired(Scope scope) {
        if (!(scope instanceof HttpsBrowserLogGateway.Credential value) || value.owner() != this) throw unavailable();
        return !clock.instant().isBefore(value.replayUntil);
    }
    @Override public byte[] forward(Scope scope, byte[] sanitizedBatch, BrowserLogDeadline deadline) {
        return post("batches", credential(scope).token, sanitizedBatch, 200, deadline);
    }
    private Credential credential(Scope scope) {
        if (!(scope instanceof HttpsBrowserLogGateway.Credential credential) || credential.owner() != this
                || !clock.instant().isBefore(credential.replayUntil)) throw new DeliveryFailure(true);
        return credential;
    }
    private byte[] post(String route, String credential, byte[] body, int expected, BrowserLogDeadline deadline) {
        CompletableFuture<HttpResponse<byte[]>> exchange = null;
        try {
            deadline.requireTime();
            if (closed.get() || body == null || body.length == 0 || body.length > 32768) throw unavailable();
            var request = HttpRequest.newBuilder(URI.create(endpoint.toASCIIString() + "/" + route))
                .timeout(Duration.ofNanos(deadline.remainingNanos())).header("Content-Type", "application/json")
                .header("Accept", "application/json").header("Authorization", "Bearer " + credential)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.clone())).build();
            exchange = client.sendAsync(request, info -> new LimitedBody());
            var response = exchange.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
            deadline.requireTime();
            if (response.statusCode() != expected) {
                int status = response.statusCode();
                throw new DeliveryFailure(status >= 400 && status < 500 && status != 429);
            }
            if (response.statusCode() != expected || !response.headers().allValues("Content-Encoding").isEmpty()
                    || response.headers().allValues("Content-Type").size() != 1
                    || !response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim().equalsIgnoreCase("application/json"))
                throw unavailable();
            return response.body();
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new DeliveryFailure(false); }
        catch (DeliveryFailure failure) { throw failure; }
        catch (Exception failure) { throw new DeliveryFailure(false); }
        finally { if (exchange != null && !exchange.isDone()) exchange.cancel(true); }
    }
    private static void id(Object value) {
        if (!(value instanceof String text) || !text.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                || UUID.fromString(text).equals(new UUID(0, 0))) throw unavailable();
    }
    private static boolean token(Object value) { return value instanceof String text && text.matches("[A-Za-z0-9_-]{32,512}"); }
    private static BffFailure unavailable() { return new BffFailure(503, "LOG_STORE_UNAVAILABLE"); }
    @Override public void close() {
        if (closed.compareAndSet(false, true)) { client.shutdownNow(); callbacks.shutdownNow(); }
    }
    public boolean awaitClosed(Duration timeout) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        if (!client.awaitTermination(timeout)) return false;
        return callbacks.awaitTermination(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(2048);
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) {
            if (subscription != null || result.isDone()) { value.cancel(); return; }
            subscription = value; value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (var buffer : buffers) {
                int size = buffer.remaining();
                if (size > 32768 - bytes.size()) { subscription.cancel(); result.completeExceptionally(unavailable()); return; }
                byte[] chunk = new byte[size]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable ignored) { result.completeExceptionally(unavailable()); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
