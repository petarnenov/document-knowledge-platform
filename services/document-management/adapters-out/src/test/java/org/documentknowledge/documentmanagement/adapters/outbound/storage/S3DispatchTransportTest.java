package org.documentknowledge.documentmanagement.adapters.outbound.storage;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Public-factory wire regression: SDK maxAttempts(1) alone does not prove one physical dispatch.
 * Real default Linux trusted-file policy and verified TLS; never a test-constructed S3Client.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(80)
class S3DispatchTransportTest {
    @TempDir static Path authority;
    private static SSLContext serverTls;
    private static final ObjectIdentity OBJECT=new ObjectIdentity(new ObjectKey("dkp-quarantine","a".repeat(64)+"/"+UUID.randomUUID()+"/"+UUID.randomUUID()),"wire-exact-version");

    @BeforeAll static void createRealPrivateTlsAuthority()throws Exception {
        authority=authority.toRealPath();Files.setPosixFilePermissions(authority,PosixFilePermissions.fromString("rwx------"));
        String password=UUID.randomUUID().toString();
        keytool("-genkeypair","-alias","ca","-keyalg","RSA","-keysize","2048","-dname","CN=DKP synthetic dispatch CA","-ext","bc=ca:true","-ext","ku=keyCertSign,cRLSign","-validity","2","-keystore","ca.p12","-storetype","PKCS12","-storepass",password,"-keypass",password,"-noprompt");
        keytool("-exportcert","-alias","ca","-keystore","ca.p12","-storepass",password,"-rfc","-file","ca.pem");
        keytool("-genkeypair","-alias","peer","-keyalg","RSA","-keysize","2048","-dname","CN=localhost","-validity","2","-keystore","peer.p12","-storetype","PKCS12","-storepass",password,"-keypass",password,"-noprompt");
        keytool("-certreq","-alias","peer","-keystore","peer.p12","-storepass",password,"-file","peer.csr");
        keytool("-gencert","-alias","ca","-keystore","ca.p12","-storepass",password,"-infile","peer.csr","-outfile","peer.pem","-rfc","-validity","2","-ext","bc=ca:false","-ext","san=dns:localhost,ip:127.0.0.1","-ext","eku=serverAuth","-ext","ku=digitalSignature,keyEncipherment");
        keytool("-importcert","-alias","ca","-keystore","peer.p12","-storepass",password,"-file","ca.pem","-noprompt");
        keytool("-importcert","-alias","peer","-keystore","peer.p12","-storepass",password,"-file","peer.pem","-noprompt");
        Files.writeString(authority.resolve("access-key"),"synthetic-dispatch-key-118\n");Files.writeString(authority.resolve("secret-key"),"synthetic-dispatch-secret-"+"1".repeat(40)+"\n");
        Files.writeString(authority.resolve(".spool-authority"),"dkp-private-spool-v1\n");
        try(var files=Files.list(authority)){for(Path file:files.toList())Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));}
        KeyStore keys=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(authority.resolve("peer.p12"))){keys.load(input,password.toCharArray());}
        var managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());managers.init(keys,password.toCharArray());serverTls=SSLContext.getInstance("TLS");serverTls.init(managers.getKeyManagers(),null,null);
    }
    private static void keytool(String... arguments)throws Exception {
        var command=new ArrayList<String>();command.add(Path.of(System.getProperty("java.home"),"bin","keytool").toString());command.addAll(List.of(arguments));
        var process=new ProcessBuilder(command).directory(authority.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroyForcibly();fail("TLS fixture keytool deadline");}assertEquals(0,process.exitValue(),"TLS fixture keytool failed");
    }
    private S3UploadStorageAdapter connect(Peer peer){return S3UploadStorageAdapter.connect(new S3UploadStorageAdapter.Configuration(peer.endpoint(),"us-east-1",authority.resolve("access-key"),authority.resolve("secret-key"),authority.resolve("ca.pem"),authority));}
    private DeletionFence fence(){return new DeletionFence(UUID.randomUUID(),"synthetic-tenant",UUID.randomUUID(),UUID.randomUUID(),OBJECT,UUID.randomUUID(),Instant.now(),FencePhase.DISPATCHED);}
    private static void unknown(org.documentknowledge.documentmanagement.application.upload.UploadStoragePort.DeletionResult result){assertFalse(result.terminal());assertFalse(result.noFurtherExecution());assertFalse(result.successful());assertFalse(result.absent());}

    @Test void normalExactVersionDeleteDispatchesOnceAndHeadConfirmsAbsence()throws Exception {
        try(var peer=new Peer(Mode.SUCCESS);var storage=connect(peer)) {
            var attempt=fence();var result=storage.deleteOnce(attempt);
            assertEquals(1,peer.deletes.get(),"A zero-dispatch protocol failure is not one-shot success");assertEquals(1,peer.heads.get());
            assertEquals(attempt.attemptId(),result.attemptId());assertEquals(OBJECT,result.object());assertEquals("fixture-delete-118",result.requestId());assertTrue(result.terminal());assertTrue(result.noFurtherExecution());assertTrue(result.successful());assertTrue(result.absent());
            peer.assertExactVersion();peer.releaseAndAssertNoReplay(1);
        }
    }
    @Test void earlyEofCannotCauseAnImplicitSecondPhysicalDelete()throws Exception {ambiguous(Mode.EOF);}
    @Test void connectionResetCannotCauseAnImplicitSecondPhysicalDelete()throws Exception {ambiguous(Mode.RESET);}
    private void ambiguous(Mode mode)throws Exception {
        try(var peer=new Peer(mode);var storage=connect(peer)) {
            var result=storage.deleteOnce(fence());unknown(result);assertEquals(1,peer.deletes.get(),"Ambiguous transport may not retry a dispatched DELETE");assertEquals(0,peer.heads.get());peer.assertExactVersion();peer.releaseAndAssertNoReplay(1);
        }
    }
    @Test void delayedResponseBeyondUnchangedThirtySecondSocketTimeoutRemainsOneUnknownAttempt()throws Exception {
        try(var peer=new Peer(Mode.DELAY);var storage=connect(peer)) {
            long start=System.nanoTime();var result=storage.deleteOnce(fence());long elapsed=Duration.ofNanos(System.nanoTime()-start).toMillis();
            assertTrue(peer.received.getCount()==0,"Actual TLS request must reach the peer");assertTrue(elapsed>=29_000&&elapsed<65_000,"Use real production30s socket/60s API bounds");
            unknown(result);assertEquals(1,peer.deletes.get(),"Timed-out physical request cannot be silently replayed");peer.assertExactVersion();peer.releaseAndAssertNoReplay(1);
        }
    }
    @Test void redirectsNeverReplayDeleteOrForwardItToAnotherPath()throws Exception {
        for(int code:new int[]{301,302,307,308})try(var peer=new Peer(Mode.REDIRECT,code);var storage=connect(peer)) {
            unknown(storage.deleteOnce(fence()));assertEquals(1,peer.deletes.get());assertEquals(0,peer.redirected.get(),"No redirected request may be transmitted");peer.releaseAndAssertNoReplay(1);
        }
    }
    private enum Mode {SUCCESS,EOF,RESET,DELAY,REDIRECT}
    private static final class Peer implements AutoCloseable {
        private final SSLServerSocket listener;
        private final ExecutorService handlers=Executors.newVirtualThreadPerTaskExecutor();
        private final Set<SSLSocket> sockets=ConcurrentHashMap.newKeySet();
        private final AtomicBoolean stopped=new AtomicBoolean();
        final AtomicInteger deletes=new AtomicInteger(),heads=new AtomicInteger(),redirected=new AtomicInteger();
        final CountDownLatch received=new CountDownLatch(1),release=new CountDownLatch(1),delayedFinished=new CountDownLatch(1);
        final List<String> targets=new CopyOnWriteArrayList<>();
        private final Mode mode;private final int redirectCode;
        Peer(Mode mode)throws IOException{this(mode,307);}
        Peer(Mode mode,int redirectCode)throws IOException {
            this.mode=mode;this.redirectCode=redirectCode;
            listener=(SSLServerSocket)serverTls.getServerSocketFactory().createServerSocket(0,16,InetAddress.getLoopbackAddress());
            handlers.submit(()->{while(!stopped.get())try{SSLSocket socket=(SSLSocket)listener.accept();sockets.add(socket);handlers.submit(()->serve(socket));}catch(IOException closed){if(!stopped.get())throw new UncheckedIOException(closed);}});
        }
        URI endpoint(){return URI.create("https://localhost:"+listener.getLocalPort());}
        private void serve(SSLSocket socket) {
            try(socket) {
                socket.setSoTimeout(70_000);socket.startHandshake();String line=line(socket.getInputStream());if(line==null)return;
                String[] request=line.split(" ",3);if(request.length!=3)return;String method=request[0],target=request[1];int length=0,headers=0;
                while((line=line(socket.getInputStream()))!=null&&!line.isEmpty()){if(++headers>100)return;if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).strip());}
                if(length<0||length>1024)return;if(length>0&&socket.getInputStream().readNBytes(length).length!=length)return;
                if(target.startsWith("/redirected")){redirected.incrementAndGet();respond(socket,204,"",Map.of("x-amz-request-id","redirected-118"));return;}
                if(method.equals("HEAD")){heads.incrementAndGet();targets.add(target);respond(socket,404,"Not Found",Map.of("x-amz-request-id","fixture-head-118","x-amz-error-code","NoSuchVersion"));return;}
                if(!method.equals("DELETE"))return;deletes.incrementAndGet();targets.add(target);received.countDown();
                switch(mode) {
                    case EOF -> {return;}
                    case RESET -> {socket.setSoLinger(true,0);return;}
                    case DELAY -> {release.await(70,TimeUnit.SECONDS);respond(socket,204,"",Map.of("x-amz-request-id","fixture-delete-118","x-amz-version-id",OBJECT.versionId()));}
                    case REDIRECT -> respond(socket,redirectCode,"Redirect",Map.of("Location",endpoint()+"/redirected?versionId="+OBJECT.versionId(),"x-amz-request-id","fixture-redirect-118"));
                    case SUCCESS -> respond(socket,204,"",Map.of("x-amz-request-id","fixture-delete-118","x-amz-version-id",OBJECT.versionId()));
                }
            }catch(IOException|InterruptedException ignored){Thread.currentThread().interrupt();}finally{if(mode==Mode.DELAY)delayedFinished.countDown();sockets.remove(socket);}
        }
        private static String line(InputStream input)throws IOException {
            var line=new ByteArrayOutputStream();int b;while((b=input.read())!=-1){if(b=='\n')return line.toString(StandardCharsets.US_ASCII).replace("\r","");if(line.size()>=8192)throw new IOException("Fixture header bound");line.write(b);}return line.size()==0?null:line.toString(StandardCharsets.US_ASCII);
        }
        private static void respond(SSLSocket socket,int status,String reason,Map<String,String> headers)throws IOException {
            StringBuilder response=new StringBuilder("HTTP/1.1 "+status+" "+reason+"\r\nContent-Length: 0\r\nConnection: close\r\n");headers.forEach((key,value)->response.append(key).append(": ").append(value).append("\r\n"));response.append("\r\n");socket.getOutputStream().write(response.toString().getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();
        }
        void assertExactVersion(){assertFalse(targets.isEmpty());for(String target:targets)assertEquals("/"+OBJECT.objectKey().bucket()+"/"+OBJECT.objectKey().key()+"?versionId="+OBJECT.versionId(),target);}
        void releaseAndAssertNoReplay(int expected)throws Exception {release.countDown();if(mode==Mode.DELAY)assertTrue(delayedFinished.await(5,TimeUnit.SECONDS),"Delayed remote execution must actually settle before recount");Thread.sleep(500);assertEquals(expected,deletes.get(),"Count again after releasing every delayed remote execution");assertEquals(0,redirected.get());}
        @Override public void close()throws Exception {stopped.set(true);release.countDown();listener.close();for(var socket:sockets)try{socket.close();}catch(IOException ignored){}handlers.shutdownNow();assertTrue(handlers.awaitTermination(3,TimeUnit.SECONDS));}
    }
}
