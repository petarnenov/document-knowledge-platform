package org.documentknowledge.knowledgebase.adapters.outbound.remote;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import javax.net.ssl.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
/** An operational dependency check, deliberately unrelated to actor authority. */
public final class FixedDocumentShareReadiness {
    private final URI base; private final Path ca; private final SSLContext testTls;
    private final JsonMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private FixedDocumentShareReadiness(URI base,Path ca,SSLContext testTls) { this.base=base;this.ca=ca;this.testTls=testTls; }
    public static FixedDocumentShareReadiness connect(URI base,Path ca) { return new FixedDocumentShareReadiness(base,ca,null); }
    /** Java-only TLS transport test seam; runtime wiring uses fresh scoped CA files. */
    FixedDocumentShareReadiness(URI base,SSLContext tls) { this(base,null,tls); }
    public boolean ready() {
        long end=System.nanoTime()+Duration.ofMillis(500).toNanos();
        HttpClient client=null; CompletableFuture<HttpResponse<byte[]>> future=null;
        try {
            if(base==null||!"https".equals(base.getScheme())||base.getHost()==null||base.getRawUserInfo()!=null||base.getRawQuery()!=null||base.getRawFragment()!=null||!(base.getRawPath().isEmpty()||base.getRawPath().equals("/"))) return false;
            SSLContext tls=testTls==null?ScopedDmTls.load(ca):testTls;
            SSLParameters parameters=new SSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");
            client=HttpClient.newBuilder().sslContext(tls).sslParameters(parameters).followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(500)).version(HttpClient.Version.HTTP_1_1).build();
            long remaining=end-System.nanoTime();if(remaining<=0)return false;
            HttpRequest request=HttpRequest.newBuilder(base.resolve("/health/readiness")).timeout(Duration.ofNanos(remaining))
                .header("Accept","application/json").GET().build();
            future=client.sendAsync(request,info->new FixedDocumentShareClient.BoundedBody(2048));
            HttpResponse<byte[]> response=future.get(remaining,TimeUnit.NANOSECONDS);
            if(response.statusCode()!=200||response.headers().firstValue("Content-Encoding").isPresent()
                ||!response.headers().firstValue("Content-Type").orElse("").matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?")) return false;
            Object body=json.readValue(response.body(),Object.class);
            return Map.of("status","UP").equals(body)&&end-System.nanoTime()>0;
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt();return false; }
        catch(Exception unavailable) { return false; }
        finally { if(future!=null&&!future.isDone())future.cancel(true);if(client!=null)client.shutdownNow(); }
    }
}
