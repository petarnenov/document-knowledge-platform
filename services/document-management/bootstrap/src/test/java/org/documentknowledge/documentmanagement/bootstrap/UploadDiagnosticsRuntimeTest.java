package org.documentknowledge.documentmanagement.bootstrap;

import io.micronaut.context.ApplicationContext;
import java.io.*;
import java.lang.reflect.Proxy;
import java.net.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import javax.net.ssl.SSLContext;
import org.documentknowledge.documentmanagement.adapters.outbound.storage.S3UploadStorageAdapter;
import org.documentknowledge.documentmanagement.adapters.outbound.verification.StandardDefinitions;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.application.DiagnosticContext;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Composition units only; actual provider exchanges are verified by adapter/integration suites. */
class UploadDiagnosticsRuntimeTest {
    private static final class Observer implements UploadDiagnosticsPort.FailureObserver {
        final List<Throwable> failures=new ArrayList<>();
        public void failure(Throwable failure){failures.add(failure);}
    }
    private static Map<String,Object> configuration() {
        return Map.of("dkp.dm.upload.enabled",true,"dkp.dm.storage.endpoint","https://storage.example:9000/",
            "dkp.dm.storage.region","us-east-1","dkp.dm.scanner.host","localhost",
            "dkp.dm.inspector.endpoint","https://localhost:8443/internal/v1/inspect",
            "dkp.dm.inspector.profile-digest","a".repeat(64),"dkp.dm.inspector.image-digest","sha256:"+"b".repeat(64));
    }
    private static final class Connections implements UploadRuntime.Connections {
        final VerificationRuntimeBindingTest.Manager workspace=new VerificationRuntimeBindingTest.Manager();
        final List<UploadDiagnosticsPort.FailureObserver> dispatched=new ArrayList<>();
        final List<UploadDiagnosticsPort.FailureObserver> factories=new ArrayList<>();
        final List<DiagnosticContext> contexts=new ArrayList<>();
        int storageCreated,cleanupCreated,inspectorCreated;
        RuntimeException connectionFailure;
        @SuppressWarnings("unchecked") private <T> T port(Class<T> type) {
            return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
                int observer=args.length-1;
                if(args[observer] instanceof DiagnosticContext diagnostics){contexts.add(diagnostics);observer--;}
                assertInstanceOf(UploadDiagnosticsPort.FailureObserver.class,args[observer]);
                dispatched.add((UploadDiagnosticsPort.FailureObserver)args[observer]);
                if(method.getReturnType()==InputStream.class)return new ByteArrayInputStream(new byte[]{7});
                if(method.getReturnType()==List.class)return List.of();
                return null;
            });
        }
        public VerificationWorkspacePort verification(Path path){return workspace;}
        public UploadRuntime.Storage storage(S3UploadStorageAdapter.Configuration c){throw new AssertionError("Original observer-aware factory required");}
        public UploadRuntime.Storage storage(S3UploadStorageAdapter.Configuration c,VerificationWorkspacePort w,UploadDiagnosticsPort.FailureObserver observer){
            storageCreated++;factories.add(observer);if(connectionFailure!=null)throw connectionFailure;
            return new UploadRuntime.Storage(port(UploadStoragePort.class),()->true,()->{});
        }
        public UploadRuntime.Cleanup cleanup(S3UploadStorageAdapter.Configuration c){throw new AssertionError("Original observer-aware factory required");}
        public UploadRuntime.Cleanup cleanup(S3UploadStorageAdapter.Configuration c,UploadDiagnosticsPort.FailureObserver observer){
            cleanupCreated++;factories.add(observer);return new UploadRuntime.Cleanup(port(CleanupStoragePort.class),()->true,()->{});
        }
        public UploadRuntime.Scanner scanner(InetSocketAddress endpoint,StandardDefinitions definitions,Clock clock){return new UploadRuntime.Scanner(port(MalwareScanPort.class),()->true);}
        public UploadRuntime.Inspector inspector(URI endpoint,SSLContext tls,byte[] key,String profile,String image,Clock clock){
            inspectorCreated++;return new UploadRuntime.Inspector(port(StructureInspectionPort.class),()->{});
        }
        public boolean health(URI endpoint,SSLContext tls){return true;}
    }
    private static byte[] publicCa()throws Exception {
        var keys=java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
        try(var input=Files.newInputStream(Path.of(System.getProperty("java.home"),"lib/security/cacerts"))){keys.load(input,"changeit".toCharArray());}
        for(var alias:Collections.list(keys.aliases())) {
            var certificate=(java.security.cert.X509Certificate)keys.getCertificate(alias);
            try{certificate.checkValidity();if(certificate.getBasicConstraints()>=0)return certificate.getEncoded();}
            catch(java.security.cert.CertificateException expired){ }
        }
        throw new AssertionError("No valid JDK public CA");
    }
    private static UploadRuntime.AuthorityFiles authority()throws Exception {
        byte[] ca=publicCa();
        return (path,maximum,privateRead)->!privateRead?ca:maximum==128?
            Base64.getEncoder().encode(new byte[32]):path.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
    @Test void originalPerInvocationObserversBudgetsAndWorkspaceReachEveryLazyProvider()throws Exception {
        var connections=new Connections();var first=new Observer();var second=new Observer();
        try(var context=ApplicationContext.run(configuration());var runtime=new UploadRuntime(context.getEnvironment(),Clock.systemUTC(),authority(),connections)) {
            var generic=new GenericTempBudget(Clock.systemUTC(),GenericTempPort.Role.TRANSFER);
            runtime.put(null,null,1,"",generic,first);
            try(var input=runtime.open(null,second)){assertEquals(7,input.read());}
            try(var input=runtime.open(null,generic,first)){assertEquals(7,input.read());}
            try(var phase=new CompleteBudget(Clock.systemUTC()).phase(CompleteBudget.Kind.STORAGE)) {
                try(var input=runtime.open(null,phase,second)){assertEquals(7,input.read());}
                runtime.copy(null,null,phase,connections.workspace.workspace,first);
            }
            runtime.versions(null,second);runtime.cleanup().cleanupVersions(null,first);runtime.cleanup().deleteOnce(null,second);
            try(var phase=new CompleteBudget(Clock.systemUTC()).phase(CompleteBudget.Kind.SCAN)){runtime.scan(null,phase,first);}
            try(var phase=new CompleteBudget(Clock.systemUTC()).phase(CompleteBudget.Kind.INSPECT)){runtime.inspect(null,phase,second);}
            assertEquals(List.of(first,second,first,second,first,second,first,second,first,second),connections.dispatched);
            assertEquals(List.of(first,first),connections.factories);assertEquals(1,connections.storageCreated);
            assertEquals(1,connections.cleanupCreated);assertEquals(1,connections.inspectorCreated);assertEquals(1,connections.workspace.checks);
            assertTrue(first.failures.isEmpty());assertTrue(second.failures.isEmpty());
        }
    }
    @Test void lazyFactoryFailureKeepsOriginalCauseBeforeMappingAndIsNeverCached()throws Exception {
        var connections=new Connections();var original=new IllegalStateException("synthetic factory failure");connections.connectionFailure=original;
        try(var context=ApplicationContext.run(configuration());var runtime=new UploadRuntime(context.getEnvironment(),Clock.systemUTC(),authority(),connections)) {
            for(int i=0;i<2;i++){var observer=new Observer();assertEquals(503,assertThrows(DmFailure.class,()->runtime.open(null,observer)).status());assertEquals(List.of(original),observer.failures);}
            assertEquals(2,connections.storageCreated);assertEquals(1,connections.cleanupCreated);assertTrue(connections.dispatched.isEmpty());
        }
    }
    @Test void inspectorCompositionPreservesEachRequestContextAndCancellation()throws Exception {
        var connections=new Connections();var observer=new Observer();
        var traced=new DiagnosticContext(UUID.randomUUID(),"a".repeat(32),"b".repeat(16));
        var untraced=DiagnosticContext.fresh();
        try(var context=ApplicationContext.run(configuration());var runtime=new UploadRuntime(context.getEnvironment(),Clock.systemUTC(),authority(),connections)) {
            for(var diagnostics:List.of(traced,untraced)) {
                var budget=new CompleteBudget(Clock.systemUTC());
                try(var phase=budget.phase(CompleteBudget.Kind.INSPECT)) {
                    runtime.inspect(null,phase,observer,diagnostics);
                    budget.cancel();
                    assertThrows(DmFailure.class,()->runtime.inspect(null,phase,observer,diagnostics));
                }
            }
            assertEquals(List.of(traced,untraced),connections.contexts);
            assertEquals(List.of(observer,observer),connections.dispatched);
            assertEquals(1,connections.inspectorCreated);
            try(var phase=new CompleteBudget(Clock.systemUTC()).phase(CompleteBudget.Kind.INSPECT)) {
                assertThrows(NullPointerException.class,()->runtime.inspect(null,phase,observer,null));
            }
            assertEquals(2,connections.dispatched.size());
        }
    }
    @Test void actualAuthorityReadFailureIsObservedWithoutWeakeningCredentialSeparation()throws Exception {
        var connections=new Connections();var original=new IOException("synthetic authority unavailable");var observer=new Observer();
        try(var context=ApplicationContext.run(configuration());var runtime=new UploadRuntime(context.getEnvironment(),Clock.systemUTC(),
            (path,maximum,privateRead)->{throw original;},connections)) {
            assertEquals(503,assertThrows(DmFailure.class,()->runtime.open(null,observer)).status());
            assertEquals(List.of(original),observer.failures);assertEquals(0,connections.storageCreated);assertEquals(0,connections.cleanupCreated);
            assertTrue(connections.dispatched.isEmpty());
        }
    }
}
