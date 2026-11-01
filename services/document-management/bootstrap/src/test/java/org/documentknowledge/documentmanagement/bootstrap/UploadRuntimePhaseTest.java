package org.documentknowledge.documentmanagement.bootstrap;

import io.micronaut.context.ApplicationContext;
import java.io.InputStream;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.documentknowledge.documentmanagement.adapters.outbound.storage.S3UploadStorageAdapter;
import org.documentknowledge.documentmanagement.adapters.outbound.verification.StandardDefinitions;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual runtime wrapper; connection ports are Java-only observation seams. */
class UploadRuntimePhaseTest {
    enum Call { OPEN,COPY,SCAN,INSPECT }
    static final class Time extends Clock {
        Instant now=Instant.parse("2026-11-01T00:00:00Z");
        public Instant instant(){return now;}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        void expire(Call call){now=now.plusSeconds(call==Call.SCAN?120:60);}
    }
    static CompleteBudget.Kind kind(Call call){return switch(call){case OPEN,COPY->CompleteBudget.Kind.STORAGE;case SCAN->CompleteBudget.Kind.SCAN;case INSPECT->CompleteBudget.Kind.INSPECT;};}
    static Object invoke(UploadRuntime runtime,Call call,CompleteBudget.Phase phase){return switch(call){case OPEN->runtime.open(null,phase);case COPY->runtime.copy(null,null,phase);case SCAN->runtime.scan(null,phase);case INSPECT->runtime.inspect(null,phase);};}
    static final class Fixture implements AutoCloseable,UploadRuntime.Connections {
        final Time time=new Time();final ApplicationContext context;final UploadRuntime runtime;
        int dispatched,connections,closed;boolean expireOnConnect,fail;CompleteBudget.Phase seen;
        final CountDownLatch cancelled=new CountDownLatch(1);
        final DmFailure adapterFailure=new DmFailure(503,"UNAVAILABLE");
        final InputStream stream=InputStream.nullInputStream();
        Fixture(boolean enabled)throws Exception{
            var settings=new HashMap<String,Object>();settings.put("dkp.dm.upload.enabled",enabled);
            settings.put("dkp.dm.storage.endpoint","https://storage.example:9000/");settings.put("dkp.dm.storage.region","us-east-1");settings.put("dkp.dm.scanner.host","scanner.example");
            settings.put("dkp.dm.inspector.endpoint","https://inspector.example:8443/internal/v1/inspect");settings.put("dkp.dm.inspector.profile-digest","f".repeat(64));settings.put("dkp.dm.inspector.image-digest","sha256:"+"a".repeat(64));
            context=ApplicationContext.run(settings);byte[] ca=publicCa();
            UploadRuntime.AuthorityFiles files=(path,max,privateRead)->privateRead?(max==257?(path+"-distinct-synthetic").getBytes(java.nio.charset.StandardCharsets.US_ASCII):Base64.getEncoder().encode(new byte[32])):ca;
            runtime=new UploadRuntime(context.getEnvironment(),time,files,this);
        }
        void connected(Call call){connections++;if(expireOnConnect)time.expire(call);}
        void observed(CompleteBudget.Phase phase){seen=phase;dispatched++;phase.check();phase.onCancel(cancelled::countDown);if(fail)throw adapterFailure;}
        public UploadRuntime.Storage storage(S3UploadStorageAdapter.Configuration ignored){
            connected(Call.OPEN);
            return new UploadRuntime.Storage(new UploadStoragePort(){
                public ObjectIdentity put(ObjectKey key,Path path,long size,String hash){throw new AssertionError("Unexpected write");}
                public InputStream open(ObjectIdentity object){throw new AssertionError("Legacy open loses phase");}
                public ObjectIdentity copy(ObjectIdentity object,ObjectKey key){throw new AssertionError("Legacy copy loses phase");}
                public InputStream open(ObjectIdentity object,CompleteBudget.Phase phase){observed(phase);return stream;}
                public ObjectIdentity copy(ObjectIdentity object,ObjectKey key,CompleteBudget.Phase phase){observed(phase);return null;}
                public List<ObjectIdentity> versions(ObjectKey key){throw new AssertionError("Unexpected recovery");}
            },()->true,()->closed++);
        }
        public UploadRuntime.Cleanup cleanup(S3UploadStorageAdapter.Configuration ignored){return new UploadRuntime.Cleanup(new CleanupStoragePort(){public List<ObjectIdentity> versions(ObjectKey key){throw new AssertionError();}public UploadStoragePort.DeletionResult deleteOnce(DeletionFence fence){throw new AssertionError();}},()->true,()->closed++);}
        public UploadRuntime.Scanner scanner(java.net.InetSocketAddress endpoint,StandardDefinitions definitions,Clock clock){connected(Call.SCAN);return new UploadRuntime.Scanner(new MalwareScanPort(){public ScanEvidence scan(ArtifactSource source){throw new AssertionError("Legacy scan loses phase");}public ScanEvidence scan(ArtifactSource source,CompleteBudget.Phase phase){observed(phase);return null;}},()->true);}
        public UploadRuntime.Inspector inspector(java.net.URI endpoint,SSLContext tls,byte[] key,String profile,String image,Clock clock){connected(Call.INSPECT);return new UploadRuntime.Inspector(new StructureInspectionPort(){public InspectionEvidence inspect(ArtifactSource source){throw new AssertionError("Legacy inspect loses phase");}public InspectionEvidence inspect(ArtifactSource source,CompleteBudget.Phase phase){observed(phase);return null;}},()->closed++);}
        public boolean health(java.net.URI endpoint,SSLContext tls){throw new AssertionError("No readiness needed");}
        public void close(){runtime.close();context.close();}
    }
    @TestFactory Stream<DynamicTest> exactCapabilityAndInvocationCancellationReachAllFourConnectionPortsWithoutSingletonClose(){
        return Arrays.stream(Call.values()).map(call->DynamicTest.dynamicTest(call.name(),()->{try(var fixture=new Fixture(true)){
            var budget=new CompleteBudget(fixture.time);try(var phase=budget.phase(kind(call))){
                Object result=invoke(fixture.runtime,call,phase);assertSame(phase,fixture.seen);assertEquals(1,fixture.dispatched);
                if(call==Call.OPEN)assertSame(fixture.stream,result);
                fixture.time.now=fixture.time.now.plusSeconds(1);assertTrue(fixture.seen.remaining().compareTo(Duration.ofSeconds(call==Call.SCAN?120:60))<0);
                budget.cancel();assertTrue(fixture.cancelled.await(2,TimeUnit.SECONDS));assertEquals(0,fixture.closed);
                assertThrows(DmFailure.class,()->invoke(fixture.runtime,call,phase));assertEquals(1,fixture.dispatched);
            }
        }}));
    }
    @TestFactory Stream<DynamicTest> exactExpiredBoundaryRejectsBeforeAnyLazyConnectionOrDispatch(){
        return Arrays.stream(Call.values()).map(call->DynamicTest.dynamicTest(call.name(),()->{try(var fixture=new Fixture(true)){
            var budget=new CompleteBudget(fixture.time);try(var phase=budget.phase(kind(call))){
                fixture.time.expire(call);var failure=assertThrows(DmFailure.class,()->invoke(fixture.runtime,call,phase));assertEquals(503,failure.status());assertEquals(0,fixture.connections);assertEquals(0,fixture.dispatched);
            }
        }}));
    }
    @TestFactory Stream<DynamicTest> lazyConnectionCannotDispatchAfterItConsumesRemainingPhaseBudget(){
        return Arrays.stream(Call.values()).map(call->DynamicTest.dynamicTest(call.name(),()->{try(var fixture=new Fixture(true)){
            fixture.expireOnConnect=true;var budget=new CompleteBudget(fixture.time);try(var phase=budget.phase(kind(call))){
                assertThrows(DmFailure.class,()->invoke(fixture.runtime,call,phase));assertEquals(1,fixture.connections);assertEquals(0,fixture.dispatched);
            }
        }}));
    }
    @TestFactory Stream<DynamicTest> adapterFailureKeepsSameCapabilityAndReusableConnectionWithoutResetOrClose(){
        return Arrays.stream(Call.values()).map(call->DynamicTest.dynamicTest(call.name(),()->{try(var fixture=new Fixture(true)){
            fixture.fail=true;var budget=new CompleteBudget(fixture.time);try(var phase=budget.phase(kind(call))){
                assertSame(fixture.adapterFailure,assertThrows(DmFailure.class,()->invoke(fixture.runtime,call,phase)));assertSame(phase,fixture.seen);assertEquals(0,fixture.closed);
                fixture.fail=false;invoke(fixture.runtime,call,phase);assertSame(phase,fixture.seen);assertEquals(1,fixture.connections);assertEquals(2,fixture.dispatched);
            }
        }}));
    }
    @TestFactory Stream<DynamicTest> disabledModeRejectsEveryPhaseAwareOperationWithoutConnection(){
        return Arrays.stream(Call.values()).map(call->DynamicTest.dynamicTest(call.name(),()->{try(var fixture=new Fixture(false)){
            var budget=new CompleteBudget(fixture.time);try(var phase=budget.phase(kind(call))){assertEquals(503,assertThrows(DmFailure.class,()->invoke(fixture.runtime,call,phase)).status());assertEquals(0,fixture.connections);assertEquals(0,fixture.dispatched);}
        }}));
    }
    static byte[] publicCa()throws Exception{
        var certificates=java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());try(var input=Files.newInputStream(Path.of(System.getProperty("java.home"),"lib/security/cacerts"))){certificates.load(input,"changeit".toCharArray());}
        for(var alias:Collections.list(certificates.aliases())){var certificate=(java.security.cert.X509Certificate)certificates.getCertificate(alias);try{certificate.checkValidity();if(certificate.getBasicConstraints()>=0)return certificate.getEncoded();}catch(java.security.cert.CertificateException expired){}}
        throw new AssertionError("JDK has no valid public CA");
    }
}
