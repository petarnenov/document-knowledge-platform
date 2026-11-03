package org.documentknowledge.documentmanagement.bootstrap;

import io.micronaut.context.env.Environment;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Clock;
import java.util.*;
import javax.net.ssl.*;
import org.documentknowledge.authorization.TrustedAuthorityFiles;
import org.documentknowledge.documentmanagement.adapters.outbound.storage.S3UploadStorageAdapter;
import org.documentknowledge.documentmanagement.adapters.outbound.verification.*;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.application.DiagnosticContext;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;

/** Runtime-only technical adapters. No ambient credentials, fallback authority or provider simulation. */
final class UploadRuntime implements UploadStoragePort,MalwareScanPort,StructureInspectionPort,AutoCloseable {
    private final Environment environment;
    private final Clock clock;
    interface AuthorityFiles {byte[] read(Path path,int maximum,boolean privateRead)throws IOException;}
    record Storage(UploadStoragePort port,java.util.function.BooleanSupplier ready,Runnable close) {}
    record Cleanup(CleanupStoragePort port,java.util.function.BooleanSupplier ready,Runnable close) {}
    record Scanner(MalwareScanPort port,java.util.function.BooleanSupplier ready) {}
    record Inspector(StructureInspectionPort port,Runnable close) {}
    interface Connections {
        Storage storage(S3UploadStorageAdapter.Configuration configuration);
        default Storage storage(S3UploadStorageAdapter.Configuration configuration,VerificationWorkspacePort workspaces){return storage(configuration);}
        default Storage storage(S3UploadStorageAdapter.Configuration configuration,VerificationWorkspacePort workspaces,UploadDiagnosticsPort.FailureObserver observer){return storage(configuration,workspaces);}
        default VerificationWorkspacePort verification(Path root){return VerificationWorkspacePort.unavailable();}
        default GenericTempPort generic(Path root){return GenericTempPort.unavailable();}
        Cleanup cleanup(S3UploadStorageAdapter.Configuration configuration);
        default Cleanup cleanup(S3UploadStorageAdapter.Configuration configuration,UploadDiagnosticsPort.FailureObserver observer){return cleanup(configuration);}
        Scanner scanner(InetSocketAddress endpoint,StandardDefinitions definitions,Clock clock);
        Inspector inspector(URI endpoint,SSLContext tls,byte[] key,String profile,String image,Clock clock);
        boolean health(URI endpoint,SSLContext tls)throws Exception;
    }
    private final AuthorityFiles files;
    private final Connections connections;
    private Storage storage;
    private Cleanup cleanup;
    private Scanner scanner;
    private Inspector inspector;
    private VerificationWorkspacePort verificationManager;
    private GenericTempPort genericManager;
    private volatile boolean stopped;
    private final VerificationWorkspacePort verificationPort=new VerificationWorkspacePort(){
        public Workspace reserve(Identity identity,CompleteBudget.Phase phase){phase.check();return verificationManager().reserve(identity,phase);}
        public void check(Workspace workspace,CompleteBudget.Phase phase){phase.check();verificationManager().check(workspace,phase);}
        public boolean ready(){try{return verificationManager().ready();}catch(RuntimeException failure){return false;}}
        public void close(){UploadRuntime.this.close();}
    };
    private final GenericTempPort genericPort=new GenericTempPort(){
        public Workspace reserve(Role role,Identity identity,GenericTempBudget budget){budget.check();var manager=genericManager();budget.check();return manager.reserve(role,identity,budget);}
        public boolean ready(){try{return genericManager().ready();}catch(RuntimeException failure){return false;}}
        public void close(){UploadRuntime.this.close();}
    };
    UploadRuntime(Environment environment,Clock clock){this(environment,clock,TrustedAuthorityFiles::read,new RealConnections());}
    // Java-only technical connection seam. Runtime configuration cannot select another implementation.
    UploadRuntime(Environment environment,Clock clock,AuthorityFiles files,Connections connections){this.environment=Objects.requireNonNull(environment);this.clock=Objects.requireNonNull(clock);this.files=Objects.requireNonNull(files);this.connections=Objects.requireNonNull(connections);}
    Path spool(){return path("dkp.dm.upload.private-spool-path","/var/lib/dkp/uploads");}
    VerificationWorkspacePort verification(){return verificationPort;}
    GenericTempPort generic(){return genericPort;}
    private synchronized GenericTempPort genericManager(){enabledOrThrow();if(genericManager==null)genericManager=Objects.requireNonNull(connections.generic(spool()));return genericManager;}
    private synchronized VerificationWorkspacePort verificationManager(){enabledOrThrow();if(stopped)throw unavailable();if(verificationManager==null)verificationManager=Objects.requireNonNull(connections.verification(spool()));return verificationManager;}
    boolean enabled(){return environment.getProperty("dkp.dm.upload.enabled",Boolean.class).orElse(false);}
    private void enabledOrThrow(){if(stopped||!enabled())throw unavailable();}
    private String required(String name){return environment.getProperty(name,String.class).filter(s->!s.isBlank()).orElseThrow(UploadRuntime::unavailable);}
    private Path path(String key,String fallback){try{return Path.of(environment.getProperty(key,String.class).orElse(fallback));}catch(RuntimeException failure){throw unavailable();}}
    private Storage storage(){return storage(UploadDiagnosticsPort.NONE);}
    private synchronized Storage storage(UploadDiagnosticsPort.FailureObserver observer){enabledOrThrow();cleanupConnection(observer);if(storage==null){try{storage=connections.storage(new S3UploadStorageAdapter.Configuration(URI.create(required("dkp.dm.storage.endpoint")),required("dkp.dm.storage.region"),path("dkp.dm.storage.access-key-path","/run/dkp/storage/access.key"),path("dkp.dm.storage.secret-key-path","/run/dkp/storage/secret.key"),path("dkp.dm.storage.ca-path","/run/dkp/storage/ca.crt"),spool()),verificationPort,observer);}catch(RuntimeException failure){UploadDiagnosticsPort.note(observer,failure);throw unavailable();}}return storage;}
    private Cleanup cleanupConnection(){return cleanupConnection(UploadDiagnosticsPort.NONE);}
    private synchronized Cleanup cleanupConnection(UploadDiagnosticsPort.FailureObserver observer){enabledOrThrow();if(cleanup==null){requireSeparateCredentials(observer);}if(cleanup==null){try{cleanup=connections.cleanup(new S3UploadStorageAdapter.Configuration(URI.create(required("dkp.dm.storage.endpoint")),required("dkp.dm.storage.region"),path("dkp.dm.cleanup.access-key-path","/run/dkp/cleanup/access.key"),path("dkp.dm.cleanup.secret-key-path","/run/dkp/cleanup/secret.key"),path("dkp.dm.storage.ca-path","/run/dkp/storage/ca.crt"),spool()),observer);}catch(RuntimeException failure){UploadDiagnosticsPort.note(observer,failure);throw unavailable();}}return cleanup;}
    private void requireSeparateCredentials(UploadDiagnosticsPort.FailureObserver observer){
        try {
            var values=new HashSet<String>();
            for(Path key:List.of(path("dkp.dm.storage.access-key-path","/run/dkp/storage/access.key"),path("dkp.dm.storage.secret-key-path","/run/dkp/storage/secret.key"),path("dkp.dm.cleanup.access-key-path","/run/dkp/cleanup/access.key"),path("dkp.dm.cleanup.secret-key-path","/run/dkp/cleanup/secret.key"))){
                String value=new String(files.read(key,257,true),StandardCharsets.US_ASCII).strip();
                if(value.isEmpty()||!values.add(value))throw unavailable();
            }
        }catch(Exception failure){UploadDiagnosticsPort.note(observer,failure);throw unavailable();}
    }
    CleanupStoragePort cleanup(){return new CleanupStoragePort(){
        public List<ObjectIdentity> versions(ObjectKey key){return cleanupConnection().port().versions(key);}
        public DeletionResult deleteOnce(DeletionFence fence){return cleanupConnection().port().deleteOnce(fence);}
        public List<ObjectIdentity> cleanupVersions(ObjectKey key,UploadDiagnosticsPort.FailureObserver observer){return cleanupConnection(observer).port().cleanupVersions(key,observer);}
        public DeletionResult deleteOnce(DeletionFence fence,UploadDiagnosticsPort.FailureObserver observer){return cleanupConnection(observer).port().deleteOnce(fence,observer);}
    };}
    private synchronized Scanner scanner(){enabledOrThrow();if(scanner==null){String host=required("dkp.dm.scanner.host");int port=environment.getProperty("dkp.dm.scanner.port",Integer.class).orElse(3310);if(!host.matches("[A-Za-z0-9.-]{1,253}")||port<1||port>65535)throw unavailable();scanner=connections.scanner(new InetSocketAddress(host,port),new StandardDefinitions(path("dkp.dm.scanner.definitions-path","/var/lib/clamav"),clock),clock);}return scanner;}
    private Inspector inspector(){return inspector(UploadDiagnosticsPort.NONE);}
    private synchronized Inspector inspector(UploadDiagnosticsPort.FailureObserver observer){enabledOrThrow();if(inspector==null){try{byte[] encoded=files.read(path("dkp.dm.inspector.key-path","/run/dkp/inspector/service.key"),128,true);byte[] key=Base64.getDecoder().decode(new String(encoded,StandardCharsets.US_ASCII).strip());inspector=connections.inspector(URI.create(required("dkp.dm.inspector.endpoint")),tls(path("dkp.dm.inspector.ca-path","/run/dkp/inspector/ca.crt")),key,required("dkp.dm.inspector.profile-digest"),required("dkp.dm.inspector.image-digest"),clock);Arrays.fill(key,(byte)0);}catch(Exception failure){UploadDiagnosticsPort.note(observer,failure);throw unavailable();}}return inspector;}
    private SSLContext tls(Path ca)throws Exception{var certificates=CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(files.read(ca,65536,false)));if(certificates.isEmpty())throw unavailable();var store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);int count=0;for(var certificate:certificates){if(!(certificate instanceof java.security.cert.X509Certificate x509)||x509.getBasicConstraints()<0)throw unavailable();x509.checkValidity();store.setCertificateEntry("inspector-ca-"+count++,certificate);}var factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(store);var tls=SSLContext.getInstance("TLS");tls.init(null,factory.getTrustManagers(),null);return tls;}
    @Override public ObjectIdentity put(ObjectKey key,Path source,long size,String hash){return storage().port().put(key,source,size,hash);}
    @Override public ObjectIdentity put(ObjectKey key,Path source,long size,String hash,GenericTempBudget budget){budget.check();var port=storage().port();budget.check();return port.put(key,source,size,hash,budget);}
    @Override public InputStream open(ObjectIdentity object,GenericTempBudget budget){budget.check();var port=storage().port();budget.check();return port.open(object,budget);}
    @Override public InputStream open(ObjectIdentity object){return storage().port().open(object);}
    @Override public InputStream open(ObjectIdentity object,CompleteBudget.Phase phase){phase.check();var port=storage().port();phase.check();return port.open(object,phase);}
    @Override public ObjectIdentity copy(ObjectIdentity source,ObjectKey target){return storage().port().copy(source,target);}
    @Override public ObjectIdentity copy(ObjectIdentity source,ObjectKey target,CompleteBudget.Phase phase){phase.check();var port=storage().port();phase.check();return port.copy(source,target,phase);}
    @Override public ObjectIdentity copy(ObjectIdentity source,ObjectKey target,CompleteBudget.Phase phase,VerificationWorkspacePort.Workspace workspace){phase.check();verificationPort.check(workspace,phase);var port=storage().port();phase.check();return port.copy(source,target,phase,workspace);}
    @Override public List<ObjectIdentity> versions(ObjectKey key){return storage().port().versions(key);}
    @Override public ScanEvidence scan(ArtifactSource source){return scanner().port().scan(source);}
    @Override public ScanEvidence scan(ArtifactSource source,CompleteBudget.Phase phase){phase.check();var port=scanner().port();phase.check();return port.scan(source,phase);}
    @Override public InspectionEvidence inspect(ArtifactSource source){return inspector().port().inspect(source);}
    @Override public InspectionEvidence inspect(ArtifactSource source,CompleteBudget.Phase phase){phase.check();var port=inspector().port();phase.check();return port.inspect(source,phase);}
    @Override public ObjectIdentity put(ObjectKey key,Path source,long size,String hash,GenericTempBudget budget,UploadDiagnosticsPort.FailureObserver observer){budget.check();var port=storage(observer).port();budget.check();return port.put(key,source,size,hash,budget,observer);}
    @Override public InputStream open(ObjectIdentity object,UploadDiagnosticsPort.FailureObserver observer){return storage(observer).port().open(object,observer);}
    @Override public InputStream open(ObjectIdentity object,GenericTempBudget budget,UploadDiagnosticsPort.FailureObserver observer){budget.check();var port=storage(observer).port();budget.check();return port.open(object,budget,observer);}
    @Override public InputStream open(ObjectIdentity object,CompleteBudget.Phase phase,UploadDiagnosticsPort.FailureObserver observer){phase.check();var port=storage(observer).port();phase.check();return port.open(object,phase,observer);}
    @Override public ObjectIdentity copy(ObjectIdentity source,ObjectKey target,CompleteBudget.Phase phase,VerificationWorkspacePort.Workspace workspace,UploadDiagnosticsPort.FailureObserver observer){phase.check();verificationPort.check(workspace,phase);var port=storage(observer).port();phase.check();return port.copy(source,target,phase,workspace,observer);}
    @Override public List<ObjectIdentity> versions(ObjectKey key,UploadDiagnosticsPort.FailureObserver observer){return storage(observer).port().versions(key,observer);}
    @Override public ScanEvidence scan(ArtifactSource source,CompleteBudget.Phase phase,UploadDiagnosticsPort.FailureObserver observer){phase.check();var port=scanner().port();phase.check();return port.scan(source,phase,observer);}
    @Override public InspectionEvidence inspect(ArtifactSource source,CompleteBudget.Phase phase,UploadDiagnosticsPort.FailureObserver observer){phase.check();var port=inspector(observer).port();phase.check();return port.inspect(source,phase,observer);}
    @Override public InspectionEvidence inspect(ArtifactSource source,CompleteBudget.Phase phase,UploadDiagnosticsPort.FailureObserver observer,DiagnosticContext context){Objects.requireNonNull(context);phase.check();var port=inspector(observer).port();phase.check();return port.inspect(source,phase,observer,context);}
    boolean ready(){if(stopped)return false;if(!enabled())return true;try{return verificationPort.ready() && genericPort.ready() && storage().ready().getAsBoolean() && cleanupConnection().ready().getAsBoolean() && scanner().ready().getAsBoolean() && inspectorReady();}catch(Exception unavailable){return false;}}
    private boolean inspectorReady()throws Exception{
        inspector();URI origin=URI.create(required("dkp.dm.inspector.endpoint"));
        return connections.health(origin.resolve("/health/readiness"),tls(path("dkp.dm.inspector.ca-path","/run/dkp/inspector/ca.crt")));
    }
    static final class RealConnections implements Connections {
        public VerificationWorkspacePort verification(Path root){return new org.documentknowledge.documentmanagement.adapters.outbound.verificationtemp.FileVerificationWorkspace(root);}
        public GenericTempPort generic(Path root){return new org.documentknowledge.documentmanagement.adapters.outbound.generictemp.FileGenericTemp(root);}
        public Storage storage(S3UploadStorageAdapter.Configuration configuration,VerificationWorkspacePort workspaces){var adapter=S3UploadStorageAdapter.connect(configuration,workspaces);return new Storage(adapter,adapter::ready,adapter::close);}
        public Storage storage(S3UploadStorageAdapter.Configuration configuration,VerificationWorkspacePort workspaces,UploadDiagnosticsPort.FailureObserver observer){var adapter=S3UploadStorageAdapter.connect(configuration,workspaces,observer);return new Storage(adapter,adapter::ready,adapter::close);}
        public Storage storage(S3UploadStorageAdapter.Configuration configuration){var adapter=S3UploadStorageAdapter.connect(configuration);return new Storage(adapter,adapter::ready,adapter::close);}
        public Cleanup cleanup(S3UploadStorageAdapter.Configuration configuration){var adapter=org.documentknowledge.documentmanagement.adapters.outbound.storage.S3CleanupStorageAdapter.connect(configuration);return new Cleanup(adapter,adapter::ready,adapter::close);}
        public Cleanup cleanup(S3UploadStorageAdapter.Configuration configuration,UploadDiagnosticsPort.FailureObserver observer){var adapter=org.documentknowledge.documentmanagement.adapters.outbound.storage.S3CleanupStorageAdapter.connect(configuration,observer);return new Cleanup(adapter,adapter::ready,adapter::close);}
        public Scanner scanner(InetSocketAddress endpoint,StandardDefinitions definitions,Clock clock){var adapter=new ClamAvScanAdapter(endpoint,definitions,clock);return new Scanner(adapter,adapter::ready);}
        public Inspector inspector(URI endpoint,SSLContext tls,byte[] key,String profile,String image,Clock clock){var adapter=new IsolatedInspectionAdapter(endpoint,tls,key,profile,image,clock);return new Inspector(adapter,adapter::close);}
        public boolean health(URI endpoint,SSLContext tls)throws Exception{
        try(var client=HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build()){
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2)).GET().build();
            // Bound the complete exchange, including a server that stalls after headers.
            var pending=client.sendAsync(request,ignored->new BoundedHealthBody());
            try{var response=pending.get(2,java.util.concurrent.TimeUnit.SECONDS);return response.statusCode()==200 && response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].equals("application/json") && Arrays.equals(response.body(),"{\"status\":\"UP\"}".getBytes(StandardCharsets.US_ASCII));}
            finally{if(!pending.isDone())pending.cancel(true);}
        }
    }
    }
    @Override public synchronized void close(){if(stopped)return;stopped=true;try{if(genericManager!=null)genericManager.close();}finally{try{if(verificationManager!=null)verificationManager.close();}finally{try{if(storage!=null)storage.close().run();}finally{try{if(cleanup!=null)cleanup.close().run();}finally{if(inspector!=null)inspector.close().run();}}}}}
    private static DmFailure unavailable(){return new DmFailure(503,"UNAVAILABLE");}
}
