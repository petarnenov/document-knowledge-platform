package org.documentknowledge.documentmanagement.bootstrap;
import io.micronaut.context.ApplicationContext;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class UploadRuntimeTest {
 @Test void disabledProvidersCannotUseAmbientCredentialsAndPreserveBaseReadiness(){
  try(var context=ApplicationContext.run(Map.of());var runtime=new UploadRuntime(context.getEnvironment(),Clock.systemUTC())){
   assertFalse(runtime.enabled());assertTrue(runtime.ready());assertEquals(Path.of("/var/lib/dkp/uploads"),runtime.spool());
   for(Runnable operation:List.<Runnable>of(()->runtime.put(null,null,1,""),()->runtime.open(null),()->runtime.copy(null,null),()->runtime.versions(null),()->runtime.cleanup().deleteOnce(null),()->runtime.scan(null),()->runtime.inspect(null))){var error=assertThrows(DmFailure.class,operation::run);assertEquals(503,error.status());assertEquals("UNAVAILABLE",error.code());}
  }
 }
 @Test void malformedOrMissingProviderConfigurationCannotReportReady(){
  for(var settings:List.of(Map.<String,Object>of("dkp.dm.upload.enabled",true),Map.<String,Object>of("dkp.dm.upload.enabled",true,"dkp.dm.storage.endpoint","http://rustfs:9000/","dkp.dm.storage.region","us-east-1"),Map.<String,Object>of("dkp.dm.upload.enabled",true,"dkp.dm.storage.endpoint","https://rustfs:9000/","dkp.dm.storage.region","us-east-1"))){
   try(var context=ApplicationContext.run(settings);var runtime=new UploadRuntime(context.getEnvironment(),Clock.systemUTC())){assertTrue(runtime.enabled());assertFalse(runtime.ready());assertThrows(DmFailure.class,()->runtime.open(null));}
  }
 }
 @Test void scannerAndInspectorRejectUnusableFixedConfiguration(){
  for(var settings:List.of(Map.<String,Object>of("dkp.dm.upload.enabled",true),Map.<String,Object>of("dkp.dm.upload.enabled",true,"dkp.dm.scanner.host","bad/host"),Map.<String,Object>of("dkp.dm.upload.enabled",true,"dkp.dm.scanner.host","localhost","dkp.dm.scanner.port",0),Map.<String,Object>of("dkp.dm.upload.enabled",true,"dkp.dm.scanner.host","localhost","dkp.dm.scanner.port",65536))){
   try(var context=ApplicationContext.run(settings);var runtime=new UploadRuntime(context.getEnvironment(),Clock.systemUTC())){assertThrows(DmFailure.class,()->runtime.scan(null));assertThrows(DmFailure.class,()->runtime.inspect(null));}
  }
  try(var context=ApplicationContext.run(Map.of("dkp.dm.upload.private-spool-path","\u0000"));var runtime=new UploadRuntime(context.getEnvironment(),Clock.systemUTC())){assertThrows(DmFailure.class,runtime::spool);}
 }
 @Test void completeConnectionGraphIsLazyScopedReusableAndClosesResources()throws Exception{
  var properties=new HashMap<String,Object>();properties.put("dkp.dm.upload.enabled",true);properties.put("dkp.dm.storage.endpoint","https://storage.example:9000/");properties.put("dkp.dm.storage.region","us-east-1");properties.put("dkp.dm.scanner.host","scanner.example");properties.put("dkp.dm.inspector.endpoint","https://inspector.example:8443/internal/v1/inspect");properties.put("dkp.dm.inspector.profile-digest","f".repeat(64));properties.put("dkp.dm.inspector.image-digest","sha256:"+"a".repeat(64));
  var calls=new java.util.concurrent.atomic.AtomicInteger();var closed=new java.util.concurrent.atomic.AtomicInteger();var storageReady=new java.util.concurrent.atomic.AtomicBoolean(true);var scanReady=new java.util.concurrent.atomic.AtomicBoolean(true);var inspectReady=new java.util.concurrent.atomic.AtomicBoolean(true);
  var storage=new org.documentknowledge.documentmanagement.application.upload.UploadStoragePort(){
   public org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectIdentity put(org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectKey k,Path p,long size,String hash){calls.incrementAndGet();return null;}
   public java.io.InputStream open(org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectIdentity o){calls.incrementAndGet();return java.io.InputStream.nullInputStream();}
   public org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectIdentity copy(org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectIdentity o,org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectKey k){calls.incrementAndGet();return null;}
   public java.util.List<org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectIdentity> versions(org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectKey k){calls.incrementAndGet();return java.util.List.of();}
  };
  var connections=new UploadRuntime.Connections(){
   public org.documentknowledge.documentmanagement.application.upload.VerificationWorkspacePort verification(Path root){return new VerificationRuntimeBindingTest.Manager();}
   public org.documentknowledge.documentmanagement.application.upload.GenericTempPort generic(Path root){return new GenericRuntimeBindingTest.Manager();}
   public UploadRuntime.Storage storage(org.documentknowledge.documentmanagement.adapters.outbound.storage.S3UploadStorageAdapter.Configuration configuration){assertEquals("https://storage.example:9000/",configuration.endpoint().toString());assertEquals(Path.of("/run/dkp/storage/access.key"),configuration.accessKeyFile());return new UploadRuntime.Storage(storage,storageReady::get,closed::incrementAndGet);}
   public UploadRuntime.Cleanup cleanup(org.documentknowledge.documentmanagement.adapters.outbound.storage.S3UploadStorageAdapter.Configuration configuration){assertEquals(Path.of("/run/dkp/cleanup/access.key"),configuration.accessKeyFile());return new UploadRuntime.Cleanup(new org.documentknowledge.documentmanagement.application.upload.CleanupStoragePort(){public java.util.List<org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectIdentity> versions(org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectKey key){return java.util.List.of();}public org.documentknowledge.documentmanagement.application.upload.UploadStoragePort.DeletionResult deleteOnce(org.documentknowledge.documentmanagement.domain.upload.UploadModels.DeletionFence f){calls.incrementAndGet();return null;}},storageReady::get,closed::incrementAndGet);}
   public UploadRuntime.Scanner scanner(java.net.InetSocketAddress endpoint,org.documentknowledge.documentmanagement.adapters.outbound.verification.StandardDefinitions definitions,Clock clock){assertEquals(3310,endpoint.getPort());return new UploadRuntime.Scanner(source->{calls.incrementAndGet();return null;},scanReady::get);}
   public UploadRuntime.Inspector inspector(java.net.URI endpoint,javax.net.ssl.SSLContext tls,byte[] key,String profile,String image,Clock clock){assertEquals(32,key.length);assertEquals("f".repeat(64),profile);assertEquals("https://inspector.example:8443/internal/v1/inspect",endpoint.toString());return new UploadRuntime.Inspector(source->{calls.incrementAndGet();return null;},closed::incrementAndGet);}
   public boolean health(java.net.URI endpoint,javax.net.ssl.SSLContext tls){assertEquals("https://inspector.example:8443/health/readiness",endpoint.toString());return inspectReady.get();}
  };
  byte[] ca=publicCa();UploadRuntime.AuthorityFiles files=(path,max,privateRead)->{if(privateRead&&max==257)return (path.toString()+"-synthetic-credential").getBytes(java.nio.charset.StandardCharsets.US_ASCII);if(privateRead){assertEquals(128,max);return Base64.getEncoder().encode(new byte[32]);}assertEquals(65536,max);return ca;};
  try(var context=ApplicationContext.run(properties);var runtime=new UploadRuntime(context.getEnvironment(),Clock.systemUTC(),files,connections)){
   assertTrue(runtime.ready());runtime.put(null,null,1,"");runtime.open(null).close();runtime.copy(null,null);runtime.versions(null);runtime.cleanup().deleteOnce(null);runtime.scan(null);runtime.inspect(null);assertEquals(7,calls.get());
   storageReady.set(false);assertFalse(runtime.ready());storageReady.set(true);scanReady.set(false);assertFalse(runtime.ready());scanReady.set(true);inspectReady.set(false);assertFalse(runtime.ready());inspectReady.set(true);assertTrue(runtime.ready());
  }assertEquals(3,closed.get());
 }
 @Test void aliasedOrUnavailableCleanupAuthorityBlocksNormalAndCleanupBeforeAnyConnection(){
  for(boolean missing:List.of(false,true)){
   var properties=Map.<String,Object>of("dkp.dm.upload.enabled",true,"dkp.dm.storage.endpoint","https://storage.example:9000/","dkp.dm.storage.region","us-east-1");
   var connections=new UploadRuntime.Connections(){
    public UploadRuntime.Storage storage(org.documentknowledge.documentmanagement.adapters.outbound.storage.S3UploadStorageAdapter.Configuration c){throw new AssertionError("No normal connection before separated authority");}
    public UploadRuntime.Cleanup cleanup(org.documentknowledge.documentmanagement.adapters.outbound.storage.S3UploadStorageAdapter.Configuration c){throw new AssertionError("No cleanup connection before separated authority");}
    public UploadRuntime.Scanner scanner(java.net.InetSocketAddress e,org.documentknowledge.documentmanagement.adapters.outbound.verification.StandardDefinitions d,Clock c){throw new AssertionError();}
    public UploadRuntime.Inspector inspector(java.net.URI e,javax.net.ssl.SSLContext tls,byte[] k,String p,String image,Clock c){throw new AssertionError();}
    public boolean health(java.net.URI e,javax.net.ssl.SSLContext tls){throw new AssertionError();}
   };
   UploadRuntime.AuthorityFiles files=(p,max,privateRead)->{if(missing&&p.toString().contains("cleanup"))throw new java.io.IOException();return "same-aliased-private-credential".getBytes(java.nio.charset.StandardCharsets.US_ASCII);};
   try(var context=ApplicationContext.run(properties);var runtime=new UploadRuntime(context.getEnvironment(),Clock.systemUTC(),files,connections)){
    assertFalse(runtime.ready());assertEquals(503,assertThrows(DmFailure.class,()->runtime.put(null,null,1,"hash")).status());assertEquals(503,assertThrows(DmFailure.class,()->runtime.cleanup().deleteOnce(null)).status());
   }
  }
 }
 private byte[] publicCa()throws Exception{
  var certificates=java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());try(var input=java.nio.file.Files.newInputStream(Path.of(System.getProperty("java.home"),"lib/security/cacerts"))){certificates.load(input,"changeit".toCharArray());}
  for(var alias:java.util.Collections.list(certificates.aliases())){var certificate=(java.security.cert.X509Certificate)certificates.getCertificate(alias);try{certificate.checkValidity();if(certificate.getBasicConstraints()>=0)return certificate.getEncoded();}catch(java.security.cert.CertificateException expired){}}
  throw new AssertionError("JDK has no valid public CA");
 }
}
