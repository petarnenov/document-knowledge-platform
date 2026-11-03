package org.documentknowledge.loggingclient;

import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.*;
import java.util.*;
import javax.net.ssl.*;
import org.documentknowledge.observability.*;
import tools.jackson.databind.json.JsonMapper;

/** Disposable real-API fixture using an explicitly installed fixture identity, not application provenance. */
public final class RealSdkAcceptanceMain {
    private static String stage="INPUT";
    private static volatile int lastHttpStatus;
    private static volatile RouteName firstRoute;
    private enum RouteName { BATCHES, RECEIPTS }
    private static volatile String transportFailure="NONE";
    public static void main(String[] args) {
        try {run(args);}catch(Exception failed) {System.err.println("{\"result\":\"FAIL\",\"code\":\"SDK_ACCEPTANCE_FAILED\",\"stage\":\""+stage+"\",\"lastHttpStatus\":"+lastHttpStatus+",\"transportFailure\":\""+transportFailure+"\"}");System.exit(1);}
    }
    private static void run(String[] args) throws Exception {
        if(args.length!=4 || !Set.of("OFFLINE","REPLAY").contains(args[3]))throw new IllegalArgumentException();
        String phase=args[3];
        var path=Path.of(args[0]);if(Files.isSymbolicLink(path) || Files.size(path)>4096)throw new IllegalArgumentException();
        Map<?,?> config=JsonMapper.builder().build().readValue(Files.readAllBytes(path),Map.class);
        stage="IDENTITY";
        var build=new BuildIdentity(UUID.fromString(text(config,"buildId")),text(config,"component"),text(config,"gitSha"),text(config,"sourceFingerprint"));
        var execution=new ExecutionIdentity(build,UUID.fromString(text(config,"runId")),UUID.fromString(text(config,"instanceId")));
        UUID id=UUID.fromString(text(config,"eventId"));
        var metadata=new EventMetadata(id,execution,Instant.now(),Severity.DEBUG,EventCode.OPERATION_STARTED,
            UUID.fromString(text(config,"correlationId")),new TraceContext(null,null));
        stage="TLS";
        var trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);
        try(var input=Files.newInputStream(Path.of(args[1]))) {trust.setCertificateEntry("fixture-root",CertificateFactory.getInstance("X.509").generateCertificate(input));}
        var manager=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());manager.init(trust);
        var tls=SSLContext.getInstance("TLS");tls.init(null,manager.getTrustManagers(),null);
        stage="TRANSPORT";
        var http=new JdkLogTransport(java.net.URI.create(text(config,"endpoint")),text(config,"producerToken"),tls);
        var transport=new LogTransport() {
            @Override public Response post(Route route,byte[] body) throws java.io.IOException,InterruptedException {
                if(firstRoute==null)firstRoute=RouteName.valueOf(route.name());
                try {var reply=http.post(route,body);lastHttpStatus=reply.status();return reply;}
                catch(java.io.IOException unavailable) {transportFailure="IO";throw unavailable;}
            }
            @Override public Optional<String> credentialFingerprint() {return http.credentialFingerprint();}
            @Override public void close() {http.close();}
        };
        stage="RECOVERY";
        var spool=new FileLogSpool(Path.of(args[2]),new LogSpool.Binding(execution,http.credentialFingerprint().orElseThrow()));
        var retained=spool.load();
        if((phase.equals("OFFLINE") && !retained.isEmpty()) || (phase.equals("REPLAY")
                && (retained.size()!=1 || !retained.getFirst().id().equals(id)))) {
            spool.close();http.close();throw new IllegalStateException();
        }
        var producer=new LogProducer(execution,new LogVocabulary(Set.of(EventCode.OPERATION_STARTED),Set.of("STARTUP")),
            transport,spool);
        try {
            if(phase.equals("OFFLINE")) {
                stage="ENQUEUE";
                if(producer.emit(new LifecycleEvent(metadata,"STARTUP",LifecycleEvent.Outcome.STARTED,0,0,1,false))!=LogProducer.Admission.ACCEPTED)
                    throw new IllegalStateException();
                stage="SPOOL_DURING_REAL_OUTAGE";
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while((producer.status().spooledEvents()!=1 || producer.status().unknownEvents()!=1) && System.nanoTime()<deadline)Thread.sleep(10);
                if(producer.status().spooledEvents()!=1 || producer.status().unknownEvents()!=1 || producer.status().committed()!=0
                        || producer.status().unspooledEvents()!=0 || firstRoute!=RouteName.BATCHES)throw new IllegalStateException();
            } else {
                stage="DELIVERY_AFTER_PROCESS_RESTART";
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                while(producer.status().committed()!=1 && System.nanoTime()<deadline)Thread.sleep(10);
                if(producer.status().committed()!=1 || producer.status().pendingEvents()!=0 || firstRoute!=RouteName.RECEIPTS)
                    throw new IllegalStateException();
            }
        } finally {producer.close();if(!producer.awaitClosed(Duration.ofSeconds(2)))throw new IllegalStateException();}
        System.out.println("{\"result\":\"PASS\",\"eventId\":\""+id+"\",\"buildId\":\""+build.buildId()+"\",\"applicationBuildProvenance\":\"FIXTURE_ONLY\",\"phase\":\""+phase+"\"}");
    }
    private static String text(Map<?,?> config,String key) {return (String)Objects.requireNonNull(config.get(key));}
}
