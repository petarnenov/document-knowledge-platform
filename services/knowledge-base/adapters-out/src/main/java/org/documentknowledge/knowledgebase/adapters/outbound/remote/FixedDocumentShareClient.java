package org.documentknowledge.knowledgebase.adapters.outbound.remote;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import javax.net.ssl.*;
import org.documentknowledge.authorization.TrustedAuthorityFiles;
import org.documentknowledge.knowledgebase.application.*;
import org.documentknowledge.knowledgebase.application.DocumentShareBatchAuthorityPort.*;
import org.documentknowledge.knowledgebase.application.DocumentShareEligibilityPort.ShareEligibilityResult;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.documentknowledge.knowledgebase.domain.KbModels.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Two fixed, metadata-only HTTPS calls. Original authenticated bearer and selected tenant only. */
public final class FixedDocumentShareClient implements DocumentShareBatchAuthorityPort,DocumentShareEligibilityPort,AutoCloseable {
    private final URI base;
    private final HttpClient client;
    private final Context verified;
    private final String authorization;
    private final JsonMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    /** authorization is the exact server-verified Authorization header, including Bearer prefix. */
    public FixedDocumentShareClient(URI base,SSLContext tls,Context verified,String authorization) {
        try {
            if(!"https".equals(base.getScheme())||base.getHost()==null||base.getRawUserInfo()!=null||base.getRawQuery()!=null||base.getRawFragment()!=null||!(base.getRawPath().isEmpty()||base.getRawPath().equals("/"))) throw KbFailure.unavailable();
            if(authorization==null||!authorization.matches("(?i:Bearer) [A-Za-z0-9._~+/-]+=*")||authorization.length()>16384) throw KbFailure.unavailable();
            Objects.requireNonNull(verified); Objects.requireNonNull(tls);
            this.base=base; this.verified=verified; this.authorization=authorization;
            SSLParameters parameters=new SSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS");
            client=HttpClient.newBuilder().sslContext(tls).sslParameters(parameters).followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(1500)).version(HttpClient.Version.HTTP_1_1).build();
        } catch(Exception failure) { KnowledgeDiagnostics.failure(KnowledgeDiagnostics.Stage.SHARE_CONFIG,failure); throw KbFailure.unavailable(); }
    }
    public static FixedDocumentShareClient connect(URI base,Path ca,Context verified,String authorization) {
        try {
            SSLContext tls=ScopedDmTls.load(ca);
            return new FixedDocumentShareClient(base,tls,verified,authorization);
        } catch(Exception unavailable) { KnowledgeDiagnostics.failure(KnowledgeDiagnostics.Stage.SHARE_CONFIG,unavailable); throw KbFailure.unavailable(); }
    }
    @Override public ShareBatchAssessment assess(Context context,List<Expectation> documents,Deadline deadline) {
        try {
        List<UUID> ids=documents.stream().map(Expectation::documentId).toList(); validate(context,ids);
        List<Map<String,Object>> values=new ArrayList<>();
        for(Expectation expectation:documents) {
            Map<String,Object> row=new LinkedHashMap<>(); row.put("documentId",expectation.documentId().toString());
            if(expectation.expectedDocumentRevision()!=null) { if(expectation.expectedDocumentRevision()<0||expectation.expectedDocumentRevision()>org.documentknowledge.knowledgebase.domain.KbModels.MAX_REVISION) throw KbFailure.unavailable(); row.put("expectedDocumentRevision",expectation.expectedDocumentRevision()); }
            values.add(row);
        }
        Map<?,?> response=request("knowledge-share-assessments",Map.of("documents",values),deadline);
        Map<?,?> root=shape(response,Set.of("actor","tenant","action","assessments"));
        Context bound=context(root); List<Assessment> rows=rows(root.get("assessments"));
        var result=new ShareBatchAssessment(bound,string(root.get("action")),rows); BatchEvidence.strict(context,documents,result); deadline.check(); return result;
        } catch(KbFailure safe) { KnowledgeDiagnostics.failure(KnowledgeDiagnostics.Stage.SHARE_ASSESS,safe); throw safe; } catch(Exception unavailable) { KnowledgeDiagnostics.failure(KnowledgeDiagnostics.Stage.SHARE_ASSESS,unavailable); throw KbFailure.unavailable(); }
    }
    @Override public ShareEligibilityResult discover(Context context,List<UUID> ids,Deadline deadline) {
        try {
        validate(context,ids);
        Map<?,?> root=shape(request("knowledge-share-eligibility",Map.of("documentIds",ids.stream().map(UUID::toString).toList()),deadline),Set.of("actor","tenant","action","requestedSetDigest","eligible"));
        var result=new ShareEligibilityResult(context(root),string(root.get("action")),string(root.get("requestedSetDigest")),rows(root.get("eligible")));
        BatchEvidence.eligibility(context,ids,result); deadline.check(); return result;
        } catch(KbFailure safe) { KnowledgeDiagnostics.failure(KnowledgeDiagnostics.Stage.SHARE_ELIGIBILITY,safe); throw safe; } catch(Exception unavailable) { KnowledgeDiagnostics.failure(KnowledgeDiagnostics.Stage.SHARE_ELIGIBILITY,unavailable); throw KbFailure.unavailable(); }
    }
    private void validate(Context context,List<UUID> ids) {
        if(!verified.equals(context)||ids.isEmpty()||ids.size()>100||ids.stream().anyMatch(Objects::isNull)||new HashSet<>(ids).size()!=ids.size()) throw KbFailure.unavailable();
    }
    private Map<?,?> request(String operation,Object body,Deadline deadline) {
        deadline.check(); CompletableFuture<HttpResponse<byte[]>> future=null;
        try {
            byte[] bytes=json.writeValueAsBytes(body); if(bytes.length>16384) throw KbFailure.unavailable();
            long remaining=Math.min(deadline.remainingNanos(),Duration.ofSeconds(2).toNanos());
            HttpRequest request=HttpRequest.newBuilder(base.resolve("/api/v1/documents/"+operation)).timeout(Duration.ofNanos(remaining))
                .header("Authorization",authorization).header("X-DKP-Tenant",verified.tenant()).header("Content-Type","application/json").header("Accept","application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
            future=client.sendAsync(request,info->new BoundedBody());
            HttpResponse<byte[]> response=future.get(remaining,TimeUnit.NANOSECONDS); deadline.check();
            KnowledgeDiagnostics.shareResponse(response.statusCode());
            if(response.statusCode()==403||response.statusCode()==404) throw KbFailure.hidden();
            if(response.statusCode()==409) throw KbFailure.stale();
            if(response.statusCode()!=200||response.headers().firstValue("Content-Encoding").isPresent()
                ||!response.headers().firstValue("Content-Type").orElse("").matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?")) throw KbFailure.unavailable();
            Object decoded=json.readValue(response.body(),Object.class); if(!(decoded instanceof Map<?,?> map)) throw KbFailure.unavailable(); return map;
        } catch(KbFailure safe) { KnowledgeDiagnostics.failure(KnowledgeDiagnostics.Stage.SHARE_HTTP,safe); throw safe; }
        catch(InterruptedException interrupted) { KnowledgeDiagnostics.failure(KnowledgeDiagnostics.Stage.SHARE_HTTP,interrupted); Thread.currentThread().interrupt(); throw KbFailure.unavailable(); }
        catch(Exception unavailable) { KnowledgeDiagnostics.failure(KnowledgeDiagnostics.Stage.SHARE_HTTP,unavailable); throw KbFailure.unavailable(); }
        finally { if(future!=null&&!future.isDone()) future.cancel(true); }
    }
    private static Context context(Map<?,?> root) {
        Map<?,?> actor=shape(root.get("actor"),Set.of("issuer","subject"));
        try { return new Context(new Principal(string(actor.get("issuer")),string(actor.get("subject"))),string(root.get("tenant"))); }
        catch(Exception invalid) { throw KbFailure.unavailable(); }
    }
    private static List<Assessment> rows(Object value) {
        if(!(value instanceof List<?> list)||list.size()>100) throw KbFailure.unavailable();
        List<Assessment> result=new ArrayList<>();
        for(Object item:(List<?>)value) {
            Map<?,?> row=shape(item,Set.of("documentId","documentRevision","classification","classificationPolicyRevision"));
            String id=string(row.get("documentId")); UUID uuid=UUID.fromString(id); if(!uuid.toString().equals(id)) throw KbFailure.unavailable();
            result.add(new Assessment(uuid,integer(row.get("documentRevision")),string(row.get("classification")),integer(row.get("classificationPolicyRevision"))));
        }
        return List.copyOf(result);
    }
    private static long integer(Object value) { if(!(value instanceof Integer||value instanceof Long)) throw KbFailure.unavailable(); return ((Number)value).longValue(); }
    private static String string(Object value) { if(!(value instanceof String)) throw KbFailure.unavailable(); return (String)value; }
    private static Map<?,?> shape(Object value,Set<String> keys) { if(!(value instanceof Map<?,?> map)||!map.keySet().equals(keys)) throw KbFailure.unavailable(); return (Map<?,?>)value; }
    @Override public void close() { client.shutdownNow(); }
    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum;
        BoundedBody() { this(32768); }
        BoundedBody(int maximum) { this.maximum=maximum; }
        private final CompletableFuture<byte[]> body=new CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream(); private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription value) { subscription=value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for(ByteBuffer buffer:buffers) {
                if(buffer.remaining()>maximum-bytes.size()) { subscription.cancel(); body.completeExceptionally(KbFailure.unavailable()); return; }
                byte[] piece=new byte[buffer.remaining()]; buffer.get(piece); bytes.writeBytes(piece);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
