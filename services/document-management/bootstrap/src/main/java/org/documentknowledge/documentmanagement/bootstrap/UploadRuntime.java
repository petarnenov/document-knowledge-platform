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
        Cleanup cleanup(S3UploadStorageAdapter.Configuration configuration);
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
    UploadRuntime(Environment environment,Clock clock){this(environment,clock,TrustedAuthorityFiles::read,new RealConnections());}
    // Java-only technical connection seam. Runtime configuration cannot select another implementation.
    UploadRuntime(Environment environment,Clock clock,AuthorityFiles files,Connections connections){this.environment=Objects.requireNonNull(environment);this.clock=Objects.requireNonNull(clock);this.files=Objects.requireNonNull(files);this.connections=Objects.requireNonNull(connections);}
    Path spool(){return path("dkp.dm.upload.private-spool-path","/var/lib/dkp/uploads");}
    boolean enabled(){return environment.getProperty("dkp.dm.upload.enabled",Boolean.class).orElse(false);}
    private void enabledOrThrow(){if(!enabled())throw unavailable();}
    private String required(String name){return environment.getProperty(name,String.class).filter(s->!s.isBlank()).orElseThrow(UploadRuntime::unavailable);}
    private Path path(String key,String fallback){try{return Path.of(environment.getProperty(key,String.class).orElse(fallback));}catch(RuntimeException failure){throw unavailable();}}
    private synchronized Storage storage(){enabledOrThrow();cleanupConnection();if(storage==null){try{storage=connections.storage(new S3UploadStorageAdapter.Configuration(URI.create(required("dkp.dm.storage.endpoint")),required("dkp.dm.storage.region"),path("dkp.dm.storage.access-key-path","/run/dkp/storage/access.key"),path("dkp.dm.storage.secret-key-path","/run/dkp/storage/secret.key"),path("dkp.dm.storage.ca-path","/run/dkp/storage/ca.crt"),spool()));}catch(RuntimeException failure){throw unavailable();}}return storage;}
    private synchronized Cleanup cleanupConnection(){enabledOrThrow();if(cleanup==null){requireSeparateCredentials();}if(cleanup==null){try{cleanup=connections.cleanup(new S3UploadStorageAdapter.Configuration(URI.create(required("dkp.dm.storage.endpoint")),required("dkp.dm.storage.region"),path("dkp.dm.cleanup.access-key-path","/run/dkp/cleanup/access.key"),path("dkp.dm.cleanup.secret-key-path","/run/dkp/cleanup/secret.key"),path("dkp.dm.storage.ca-path","/run/dkp/storage/ca.crt"),spool()));}catch(RuntimeException failure){throw unavailable();}}return cleanup;}
    private void requireSeparateCredentials(){
        try {
            var values=new HashSet<String>();
            for(Path key:List.of(path("dkp.dm.storage.access-key-path","/run/dkp/storage/access.key"),path("dkp.dm.storage.secret-key-path","/run/dkp/storage/secret.key"),path("dkp.dm.cleanup.access-key-path","/run/dkp/cleanup/access.key"),path("dkp.dm.cleanup.secret-key-path","/run/dkp/cleanup/secret.key"))){
                String value=new String(files.read(key,257,true),StandardCharsets.US_ASCII).strip();
                if(value.isEmpty()||!values.add(value))throw unavailable();
            }
        }catch(Exception failure){throw unavailable();}
    }
    CleanupStoragePort cleanup(){return new CleanupStoragePort(){
        public List<ObjectIdentity> versions(ObjectKey key){return cleanupConnection().port().versions(key);}
        public DeletionResult deleteOnce(DeletionFence fence){return cleanupConnection().port().deleteOnce(fence);}
    };}
    private synchronized Scanner scanner(){enabledOrThrow();if(scanner==null){String host=required("dkp.dm.scanner.host");int port=environment.getProperty("dkp.dm.scanner.port",Integer.class).orElse(3310);if(!host.matches("[A-Za-z0-9.-]{1,253}")||port<1||port>65535)throw unavailable();scanner=connections.scanner(new InetSocketAddress(host,port),new StandardDefinitions(path("dkp.dm.scanner.definitions-path","/var/lib/clamav"),clock),clock);}return scanner;}
    private synchronized Inspector inspector(){enabledOrThrow();if(inspector==null){try{byte[] encoded=files.read(path("dkp.dm.inspector.key-path","/run/dkp/inspector/service.key"),128,true);byte[] key=Base64.getDecoder().decode(new String(encoded,StandardCharsets.US_ASCII).strip());inspector=connections.inspector(URI.create(required("dkp.dm.inspector.endpoint")),tls(path("dkp.dm.inspector.ca-path","/run/dkp/inspector/ca.crt")),key,required("dkp.dm.inspector.profile-digest"),required("dkp.dm.inspector.image-digest"),clock);Arrays.fill(key,(byte)0);}catch(Exception failure){throw unavailable();}}return inspector;}
    private SSLContext tls(Path ca)throws Exception{var certificates=CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(files.read(ca,65536,false)));if(certificates.isEmpty())throw unavailable();var store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);int count=0;for(var certificate:certificates){if(!(certificate instanceof java.security.cert.X509Certificate x509)||x509.getBasicConstraints()<0)throw unavailable();x509.checkValidity();store.setCertificateEntry("inspector-ca-"+count++,certificate);}var factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(store);var tls=SSLContext.getInstance("TLS");tls.init(null,factory.getTrustManagers(),null);return tls;}
    @Override public ObjectIdentity put(ObjectKey key,Path source,long size,String hash){return storage().port().put(key,source,size,hash);}
    @Override public InputStream open(ObjectIdentity object){return storage().port().open(object);}
    @Override public ObjectIdentity copy(ObjectIdentity source,ObjectKey target){return storage().port().copy(source,target);}
    @Override public List<ObjectIdentity> versions(ObjectKey key){return storage().port().versions(key);}
    @Override public ScanEvidence scan(ArtifactSource source){return scanner().port().scan(source);}
    @Override public InspectionEvidence inspect(ArtifactSource source){return inspector().port().inspect(source);}
    boolean ready(){if(!enabled())return true;try{return storage().ready().getAsBoolean() && cleanupConnection().ready().getAsBoolean() && scanner().ready().getAsBoolean() && inspectorReady();}catch(Exception unavailable){return false;}}
    private boolean inspectorReady()throws Exception{
        inspector();URI origin=URI.create(required("dkp.dm.inspector.endpoint"));
        return connections.health(origin.resolve("/health/readiness"),tls(path("dkp.dm.inspector.ca-path","/run/dkp/inspector/ca.crt")));
    }
    static final class RealConnections implements Connections {
        public Storage storage(S3UploadStorageAdapter.Configuration configuration){var adapter=S3UploadStorageAdapter.connect(configuration);return new Storage(adapter,adapter::ready,adapter::close);}
        public Cleanup cleanup(S3UploadStorageAdapter.Configuration configuration){var adapter=org.documentknowledge.documentmanagement.adapters.outbound.storage.S3CleanupStorageAdapter.connect(configuration);return new Cleanup(adapter,adapter::ready,adapter::close);}
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
    @Override public synchronized void close(){if(storage!=null)storage.close().run();if(cleanup!=null)cleanup.close().run();if(inspector!=null)inspector.close().run();}
    private static DmFailure unavailable(){return new DmFailure(503,"UNAVAILABLE");}
}
