package org.documentknowledge.documentmanagement.adapters.outbound.storage;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.DeletionFence;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.FencePhase;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectIdentity;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.DockerClientFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import static org.junit.jupiter.api.Assertions.*;

/** Real release gate, explicitly invoked by rustfs-compat harness on Linux. No mock transport or root credentials. */
@EnabledIfEnvironmentVariable(named="DKP_RUSTFS_COMPAT_ENDPOINT",matches="https://.+")
class RustFsUploadStorageIntegrationTest {
    private static final byte[] CONTENT="Synthetic issue-104 immutable compatibility content.\n".getBytes(StandardCharsets.UTF_8);
    private static Path authority;
    private static URI endpoint;
    private static String prefix,tenant,container;
    private static UUID document;
    private static S3UploadStorageAdapter storage;
    private static S3Client scoped;
    private static HttpClient http;
    private static TrustManagerFactory trust;
    private static AwsBasicCredentials credentials;

    @BeforeAll static void connectRealProductionFactory()throws Exception {
        assertEquals("Linux",System.getProperty("os.name"),"Actual trusted-path Linux provider is mandatory");
        authority=Path.of(required("DKP_RUSTFS_COMPAT_AUTHORITY"));endpoint=URI.create(required("DKP_RUSTFS_COMPAT_ENDPOINT"));
        prefix=required("DKP_RUSTFS_COMPAT_PREFIX");tenant=required("DKP_RUSTFS_COMPAT_TENANT");
        document=UUID.fromString(prefix.split("/")[1]);container=required("DKP_RUSTFS_COMPAT_CONTAINER");
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(tenant.getBytes(StandardCharsets.UTF_8)))+"/"+document+"/",prefix);
        credentials=AwsBasicCredentials.create(Files.readString(authority.resolve("access-key")).strip(),Files.readString(authority.resolve("secret-key")).strip());
        var certificates=CertificateFactory.getInstance("X.509").generateCertificates(Files.newInputStream(authority.resolve("ca.pem")));
        var store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);int i=0;
        for(var certificate:certificates)store.setCertificateEntry("ca"+i++,certificate);
        trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);
        var ssl=SSLContext.getInstance("TLS");ssl.init(null,trust.getTrustManagers(),null);
        http=HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        storage=S3UploadStorageAdapter.connect(configuration(authority.resolve("access-key"),authority.resolve("secret-key"),authority.resolve("ca.pem"),authority));
        scoped=client(credentials);
        var server=DockerClientFactory.instance().client().inspectContainerCmd(container).exec();
        assertTrue(container.matches("dkp104-[a-f0-9]{12}"));assertEquals("104",server.getConfig().getLabels().get("dkp.issue"));
    }
    @AfterAll static void close(){if(storage!=null)storage.close();if(scoped!=null)scoped.close();}
    private static String required(String name){String value=System.getenv(name);assertNotNull(value,name+" must be explicitly provided by isolated harness");return value;}
    private static S3UploadStorageAdapter.Configuration configuration(Path access,Path secret,Path ca,Path spool){return new S3UploadStorageAdapter.Configuration(endpoint,"us-east-1",access,secret,ca,spool);}
    private static S3Client client(AwsBasicCredentials keys){return S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(keys)).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).checksumValidationEnabled(true).build())
        .overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(15)).retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()))
        .httpClientBuilder(UrlConnectionHttpClient.builder().tlsTrustManagersProvider(()->trust.getTrustManagers()).connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(5))).build();}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static ObjectKey key(String bucket){return new ObjectKey(bucket,prefix+UUID.randomUUID());}
    private static ObjectIdentity put(ObjectKey key,byte[] bytes)throws Exception {
        Path source=Files.createTempFile(authority,"compat-",".bin");
        try{Files.write(source,bytes);return storage.put(key,source,bytes.length,sha(bytes));}finally{Files.deleteIfExists(source);}
    }
    private static byte[] read(ObjectIdentity identity)throws Exception{try(InputStream input=storage.open(identity)){return input.readAllBytes();}}
    private static DeletionFence fence(ObjectIdentity object){return new DeletionFence(UUID.randomUUID(),tenant,document,UUID.randomUUID(),object,UUID.randomUUID(),Instant.now(),FencePhase.DISPATCHED);}

    @Test void conditionalPutReturnsExactVersionAndCopyPreservesReadbackHashAndRange()throws Exception {
        assertTrue(storage.ready());
        var source=put(key("dkp-quarantine"),CONTENT);assertNotNull(source.versionId());assertNotEquals("null",source.versionId());assertArrayEquals(CONTENT,read(source));
        assertEquals(List.of(source),storage.versions(source.objectKey()));
        var head=scoped.headObject(HeadObjectRequest.builder().bucket(source.objectKey().bucket()).key(source.objectKey().key()).versionId(source.versionId()).build());
        assertEquals(source.versionId(),head.versionId());assertEquals(CONTENT.length,head.contentLength());
        var copied=storage.copy(source,key("dkp-originals"));assertArrayEquals(CONTENT,read(copied));assertEquals(sha(CONTENT),sha(read(copied)));
        try(var range=scoped.getObject(GetObjectRequest.builder().bucket(copied.objectKey().bucket()).key(copied.objectKey().key()).versionId(copied.versionId()).range("bytes=3-12").build())) {
            assertEquals(206,range.response().sdkHttpResponse().statusCode());assertEquals("bytes 3-12/"+CONTENT.length,range.response().contentRange());assertArrayEquals(java.util.Arrays.copyOfRange(CONTENT,3,13),range.readAllBytes());
        }
    }
    @Test void conditionalCreateCannotOverwriteOrAccumulateVersionsAndWrongVersionCannotSubstitute()throws Exception {
        var key=key("dkp-quarantine");var original=put(key,CONTENT);
        assertEquals(503,assertThrows(DmFailure.class,()->put(key,"different synthetic bytes".getBytes(StandardCharsets.UTF_8))).status());
        assertEquals(List.of(original),storage.versions(key));assertArrayEquals(CONTENT,read(original));
        assertEquals(503,assertThrows(DmFailure.class,()->storage.open(new ObjectIdentity(key,"unrelated-version"))).status());
        Path bad=Files.createTempFile(authority,"bad-hash-",".bin");try{Files.write(bad,CONTENT);assertThrows(DmFailure.class,()->storage.put(key("dkp-quarantine"),bad,CONTENT.length,"0".repeat(64)));}finally{Files.delete(bad);}
    }
    @Test void exactVersionDeletionHasRealRequestBoundTerminalProofAndAuthoritativeAbsence()throws Exception {
        var object=put(key("dkp-quarantine"),CONTENT);var attempt=fence(object);var result=storage.deleteOnce(attempt);
        assertEquals(attempt.attemptId(),result.attemptId());assertEquals(object,result.object());assertNotNull(result.requestId());
        assertTrue(result.terminal());assertTrue(result.noFurtherExecution());assertTrue(result.successful());assertTrue(result.absent());
        assertTrue(storage.versions(object.objectKey()).isEmpty());assertThrows(DmFailure.class,()->storage.open(object));
        assertThrows(DmFailure.class,()->storage.deleteOnce(new DeletionFence(attempt.attemptId(),tenant,document,attempt.uploadId(),object,attempt.executorId(),attempt.createdAt(),FencePhase.AUTHORIZED)));
    }
    @Test void nativeIamDeniesAnonymousWrongCredentialsAdminUnversionedDeleteAndOutsidePrefix()throws Exception {
        var object=put(key("dkp-quarantine"),CONTENT);
        var anonymous=http.send(HttpRequest.newBuilder(endpoint.resolve("/"+object.objectKey().bucket()+"/"+object.objectKey().key()+"?versionId="+object.versionId())).GET().build(),HttpResponse.BodyHandlers.discarding());assertEquals(403,anonymous.statusCode());
        try(var wrong=client(AwsBasicCredentials.create(credentials.accessKeyId(),"X".repeat(64)))){assertEquals(403,assertThrows(S3Exception.class,()->wrong.headObject(b->b.bucket(object.objectKey().bucket()).key(object.objectKey().key()).versionId(object.versionId()))).statusCode());}
        assertEquals(403,assertThrows(S3Exception.class,()->scoped.createBucket(b->b.bucket("dkp104-must-not-create"))).statusCode());
        assertEquals(403,assertThrows(S3Exception.class,()->scoped.listBuckets()).statusCode());
        assertEquals(403,assertThrows(S3Exception.class,()->scoped.listObjectVersions(b->b.bucket("dkp-quarantine"))).statusCode());
        assertEquals(403,assertThrows(S3Exception.class,()->scoped.deleteObject(b->b.bucket(object.objectKey().bucket()).key(object.objectKey().key()))).statusCode());
        assertEquals(403,assertThrows(S3Exception.class,()->scoped.putObject(PutObjectRequest.builder().bucket("dkp-outside").key("outside").build(),RequestBody.fromBytes(CONTENT))).statusCode());
        assertEquals(403,assertThrows(S3Exception.class,()->scoped.putObject(PutObjectRequest.builder().bucket("dkp-quarantine").key("0".repeat(64)+"/"+document+"/"+UUID.randomUUID()).build(),RequestBody.fromBytes(CONTENT))).statusCode());
        var request=SdkHttpFullRequest.builder().uri(endpoint.resolve("/rustfs/admin/v3/list-users")).method(SdkHttpMethod.GET).putHeader("x-amz-content-sha256",sha(new byte[0])).build();
        var signed=Aws4Signer.create().sign(request,Aws4SignerParams.builder().awsCredentials(credentials).signingRegion(Region.US_EAST_1).signingName("s3").build());
        var nativeAdmin=HttpRequest.newBuilder(signed.getUri()).GET().timeout(Duration.ofSeconds(10));
        signed.headers().forEach((name,values)->{if(!name.equalsIgnoreCase("Host"))values.forEach(value->nativeAdmin.header(name,value));});
        assertEquals(403,http.send(nativeAdmin.build(),HttpResponse.BodyHandlers.discarding()).statusCode());
        assertArrayEquals(CONTENT,read(object));
    }
    @Test void productionFactoryRejectsUnsafeAuthorityCaCredentialsAndSpoolMarker()throws Exception {
        var privateDir=Files.createTempDirectory(authority,"factory-negative-");Files.setPosixFilePermissions(privateDir,PosixFilePermissions.fromString("rwx------"));
        var marker=privateDir.resolve(".spool-authority");Files.writeString(marker,"wrong-marker\n");Files.setPosixFilePermissions(marker,PosixFilePermissions.fromString("rw-------"));
        assertThrows(DmFailure.class,()->S3UploadStorageAdapter.connect(configuration(authority.resolve("access-key"),authority.resolve("secret-key"),authority.resolve("ca.pem"),privateDir)));
        Files.writeString(marker,"dkp-private-spool-v1\n");
        var badAccess=privateDir.resolve("access");var badSecret=privateDir.resolve("secret");var badCa=privateDir.resolve("ca");
        for(var value:List.of("short","!".repeat(32))) {
            Files.writeString(badAccess,value);Files.setPosixFilePermissions(badAccess,PosixFilePermissions.fromString("rw-------"));
            assertThrows(DmFailure.class,()->S3UploadStorageAdapter.connect(configuration(badAccess,authority.resolve("secret-key"),authority.resolve("ca.pem"),privateDir)));
        }
        Files.writeString(badAccess,"a".repeat(32));Files.writeString(badSecret,"short");Files.setPosixFilePermissions(badSecret,PosixFilePermissions.fromString("rw-------"));
        assertThrows(DmFailure.class,()->S3UploadStorageAdapter.connect(configuration(badAccess,badSecret,authority.resolve("ca.pem"),privateDir)));

        try(var wrongCa=S3UploadStorageAdapter.connect(configuration(authority.resolve("access-key"),authority.resolve("secret-key"),authority.resolve("untrusted-ca.pem"),privateDir))){assertFalse(wrongCa.ready());}
        var wrongHost=new S3UploadStorageAdapter.Configuration(URI.create("https://rustfs-compat-wrong:9000"),"us-east-1",authority.resolve("access-key"),authority.resolve("secret-key"),authority.resolve("ca.pem"),privateDir);
        try(var hostnameMismatch=S3UploadStorageAdapter.connect(wrongHost)){assertFalse(hostnameMismatch.ready());}
        assertThrows(DmFailure.class,()->S3UploadStorageAdapter.connect(configuration(authority.resolve("access-key"),authority.resolve("secret-key"),authority.resolve("non-ca.pem"),privateDir)));
        Files.writeString(badSecret,"X".repeat(64));
        try(var denied=S3UploadStorageAdapter.connect(configuration(authority.resolve("access-key"),badSecret,authority.resolve("ca.pem"),privateDir))){assertFalse(denied.ready());}
        for(var invalid:List.of("", "not-a-certificate")) {
            Files.writeString(badCa,invalid);Files.setPosixFilePermissions(badCa,PosixFilePermissions.fromString("rw-------"));
            assertThrows(DmFailure.class,()->S3UploadStorageAdapter.connect(configuration(authority.resolve("access-key"),authority.resolve("secret-key"),badCa,privateDir)));
        }
        Files.setPosixFilePermissions(badAccess,PosixFilePermissions.fromString("rw-rw-rw-"));
        assertThrows(DmFailure.class,()->S3UploadStorageAdapter.connect(configuration(badAccess,authority.resolve("secret-key"),authority.resolve("ca.pem"),privateDir)));
        var symlink=privateDir.resolve("linked-access");Files.createSymbolicLink(symlink,authority.resolve("access-key"));
        assertThrows(DmFailure.class,()->S3UploadStorageAdapter.connect(configuration(symlink,authority.resolve("secret-key"),authority.resolve("ca.pem"),privateDir)));
        for(var file:List.of(symlink,badAccess,badSecret,badCa,marker))Files.delete(file);Files.delete(privateDir);
    }
    @Test void actualServerOutageFailsClosedAndDurableExactVersionsSurviveRestart()throws Exception {
        var object=put(key("dkp-quarantine"),CONTENT);var docker=DockerClientFactory.instance().client();
        docker.stopContainerCmd(container).withTimeout(10).exec();
        try {
            assertFalse(storage.ready());assertEquals(503,assertThrows(DmFailure.class,()->storage.open(object)).status());
            var unknown=storage.deleteOnce(fence(object));assertFalse(unknown.terminal());assertFalse(unknown.noFurtherExecution());assertFalse(unknown.successful());assertFalse(unknown.absent());assertNull(unknown.requestId());
        }finally{docker.startContainerCmd(container).exec();}
        boolean recovered=false;for(int i=0;i<100;i++){try{if(storage.versions(object.objectKey()).contains(object)){recovered=true;break;}}catch(DmFailure unavailable){Thread.sleep(200);}}
        assertTrue(recovered,"RustFS must recover the same persisted exact version");assertArrayEquals(CONTENT,read(object));
    }
}
