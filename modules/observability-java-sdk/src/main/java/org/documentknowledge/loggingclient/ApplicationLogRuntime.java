package org.documentknowledge.loggingclient;

import com.sun.security.auth.module.UnixSystem;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.*;
import org.documentknowledge.observability.*;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Bootstrap adapter for an original embedded artifact and an operator-issued producer grant. */
public final class ApplicationLogRuntime implements AutoCloseable {
    public static final String RESOURCE="/META-INF/dkp-logging/manifest.json";
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(1048576)
            .maxNestingDepth(16).maxStringLength(512).maxNumberLength(32).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final ExecutionIdentity execution;
    private final LogProducer producer;
    private final AtomicBoolean started=new AtomicBoolean(),stopped=new AtomicBoolean();
    private final long openedAt=System.nanoTime();

    private ApplicationLogRuntime(ExecutionIdentity execution,LogProducer producer) {
        this.execution=execution;this.producer=producer;
    }

    /** All filesystem/bootstrap work happens here, never on an event-emitting business call. */
    public static ApplicationLogRuntime open(String component,InputStream embeddedManifest,Path configuration) throws IOException {
        JdkLogTransport transport=null;FileLogSpool spool=null;
        try(InputStream manifestInput=Objects.requireNonNull(embeddedManifest)) {
            var config=JSON.readValue(utf8(privateBytes(configuration,8192)),Map.class);
            require(config.keySet().equals(Set.of("schemaVersion","manifestSha256","runId","instanceId",
                "endpoint","producerToken","caFile","spoolDirectory")) && Integer.valueOf(1).equals(config.get("schemaVersion")));
            byte[] raw=manifestInput.readNBytes(1048577);require(raw.length>0 && raw.length<=1048576);
            require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)).equals(config.get("manifestSha256")));
            var manifest=JSON.readValue(utf8(raw),Map.class);
            require(manifest.keySet().equals(Set.of("schemaVersion","buildId","component","gitSha","sourceFingerprint",
                "compiledAt","toolchainFingerprint","dependencyFingerprint","vocabularySha256","vocabulary","sourceFingerprintAlgorithm","dirty"))
                && Integer.valueOf(1).equals(manifest.get("schemaVersion")) && component.equals(manifest.get("component"))
                && "dkp-public-inputs-v1".equals(manifest.get("sourceFingerprintAlgorithm")) && manifest.get("dirty") instanceof Boolean);
            Instant.parse((String)manifest.get("compiledAt"));
            for(String field:List.of("toolchainFingerprint","dependencyFingerprint","vocabularySha256"))
                require(((String)manifest.get(field)).matches("[0-9a-f]{64}"));
            var build=new BuildIdentity(id((String)manifest.get("buildId")),component,
                (String)manifest.get("gitSha"),(String)manifest.get("sourceFingerprint"));
            var execution=new ExecutionIdentity(build,id((String)config.get("runId")),id((String)config.get("instanceId")));
            var catalog=(Map<?,?>)manifest.get("vocabulary");
            require(catalog.keySet().equals(Set.of("events","phases","errorCodes","dependencies","exceptionClasses","frames")));
            var codes=new HashSet<EventCode>();
            for(Object name:(List<?>)catalog.get("events"))
                require(codes.add(Arrays.stream(EventCode.values()).filter(code->code.wireName().equals(name)).findFirst().orElseThrow()));
            var phases=new HashSet<String>();
            for(Object phase:(List<?>)catalog.get("phases"))require(phases.add((String)phase));
            require(codes.containsAll(List.of(EventCode.PROCESS_STARTED,EventCode.PROCESS_STOPPED))
                && phases.containsAll(List.of("STARTUP","SHUTDOWN")));
            var vocabulary=new LogVocabulary(codes,phases);
            var trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);
            try(var bytes=new ByteArrayInputStream(privateBytes(Path.of((String)config.get("caFile")),16384))) {
                trust.setCertificateEntry("logging-root",CertificateFactory.getInstance("X.509").generateCertificate(bytes));
            }
            var manager=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());manager.init(trust);
            var tls=SSLContext.getInstance("TLS");tls.init(null,manager.getTrustManagers(),null);
            transport=new JdkLogTransport(URI.create((String)config.get("endpoint")),(String)config.get("producerToken"),tls);
            spool=new FileLogSpool(Path.of((String)config.get("spoolDirectory")),
                new LogSpool.Binding(execution,transport.credentialFingerprint().orElseThrow()));
            return new ApplicationLogRuntime(execution,new LogProducer(execution,vocabulary,transport,spool));
        } catch(Exception invalid) {
            try {if(spool!=null)spool.close();}catch(RuntimeException ignored) { /* Preserve fixed refusal. */ }
            try {if(transport!=null)transport.close();}catch(RuntimeException ignored) { /* Preserve fixed refusal. */ }
            throw new IOException("LOG_APPLICATION_BOOTSTRAP_INVALID");
        }
    }

    private static void require(boolean value) {if(!value)throw new IllegalArgumentException();}
    private static UUID id(String text) {var id=UUID.fromString(text);require(id.toString().equals(text));return id;}
    private static String utf8(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static byte[] privateBytes(Path path,int maximum) throws IOException {
        require(path.isAbsolute() && path.toRealPath().equals(path) && !Files.isSymbolicLink(path)
            && Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)
            && Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rw-------"))
            && ((Number)Files.getAttribute(path,"unix:uid",LinkOption.NOFOLLOW_LINKS)).longValue()==new UnixSystem().getUid());
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(maximum+1);require(bytes.length>0 && bytes.length<=maximum);return bytes;
        }
    }

    public ExecutionIdentity execution() {return execution;}
    public LogProducer.Status status() {return producer.status();}
    public LogProducer.Admission started() {
        return started.compareAndSet(false,true)?lifecycle(EventCode.PROCESS_STARTED,"STARTUP",LifecycleEvent.Outcome.STARTED):LogProducer.Admission.DUPLICATE;
    }
    public LogProducer.Admission stopping() {
        return stopped.compareAndSet(false,true)?lifecycle(EventCode.PROCESS_STOPPED,"SHUTDOWN",LifecycleEvent.Outcome.SUCCEEDED):LogProducer.Admission.DUPLICATE;
    }
    private LogProducer.Admission lifecycle(EventCode code,String phase,LifecycleEvent.Outcome outcome) {
        var metadata=new EventMetadata(UUID.randomUUID(),execution,Instant.now(),Severity.DEBUG,code,UUID.randomUUID(),new TraceContext(null,null));
        return producer.emit(new LifecycleEvent(metadata,phase,outcome,Math.max(0,(System.nanoTime()-openedAt)/1000000),0,1,false));
    }
    /** Nonflushing close: a stopped enqueue alone never establishes durable delivery. */
    @Override public void close() {producer.close();}
    public boolean awaitClosed(Duration timeout) throws InterruptedException {return producer.awaitClosed(timeout);}
}
