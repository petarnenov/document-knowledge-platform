package org.documentknowledge.documentmanagement.application;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.security.auth.module.UnixSystem;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.documentknowledge.documentmanagement.adapters.outbound.generictemp.FileGenericTemp;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.documentmanagement.domain.upload.UploadFormat;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;

/** Explicit actual elapsed PUBLIC300s/60s/idle30 service gate. Genuine unprivileged Linux FS + TCP;
 * UploadMemory/BytesStorage/scanners remain SYNTHETIC, so this is not GTEMP14 provider acceptance. */
public final class GenericTempDurationAcceptance {
 private GenericTempDurationAcceptance(){}
 public static void main(String[] args)throws Exception{
  assertEquals(1,args.length);assertTrue(Set.of("TRANSFER","PREAPPEND","IDLE").contains(args[0]));assertEquals("required",System.getenv("DKP_GENERIC_DURATION_ACCEPTANCE"));assertEquals("Linux",System.getProperty("os.name"));assertNotEquals(0,new UnixSystem().getUid());
  String mode=args[0];Path parent=Path.of("build/generic-duration-private").toAbsolutePath();Files.createDirectories(parent);Files.setPosixFilePermissions(parent,PosixFilePermissions.fromString("rwx------"));Path root=Files.createTempDirectory(parent,"owned-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
  Files.writeString(root.resolve(".spool-authority"),"dkp-private-spool-v1\n");Files.setPosixFilePermissions(root.resolve(".spool-authority"),PosixFilePermissions.fromString("rw-------"));
  var manager=new FileGenericTemp(root);var f=new UploadLifecycleTest();f.temporary=root;f.genericTemp=manager;f.clock.now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);f.create();var sent=new AtomicInteger();var peerFailure=new AtomicReference<Throwable>();var stop=new AtomicBoolean();
  try(var listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress());var source=new Socket()){
   listener.setSoTimeout(5000);source.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),listener.getLocalPort()),5000);
   try(var peer=listener.accept()){
    byte[] bytes=f.bytes();Thread writer=Thread.ofVirtual().start(()->{try{var output=peer.getOutputStream();int index=0;while(!stop.get()&&index<bytes.length){output.write(bytes[index++]);output.flush();sent.incrementAndGet();if(mode.equals("IDLE")){while(!stop.get())Thread.sleep(1000);}else Thread.sleep(20_000);}}catch(Throwable failure){if(!stop.get()&&!(failure instanceof SocketException))peerFailure.set(failure);}});
    long before=System.nanoTime();
    try{
     if(mode.equals("PREAPPEND")){
      var complete=f.verified(f.uploads(),UploadFormat.PDF);var receipt=f.store.uploads.receipt(f.caller.tenant(),complete.receiptId()).orElseThrow();var facts=Map.copyOf(f.store.uploads.outcomes);
      UploadStoragePort storage=new UploadStoragePort(){public InputStream open(ObjectIdentity object){throw new AssertionError("No legacy open");}public InputStream open(ObjectIdentity object,GenericTempBudget budget){budget.check();assertEquals(receipt.original(),object);try{return source.getInputStream();}catch(IOException failure){throw new UncheckedIOException(failure);}}public ObjectIdentity put(ObjectKey k,Path p,long n,String h){throw new AssertionError();}public ObjectIdentity copy(ObjectIdentity a,ObjectKey b){throw new AssertionError();}public List<ObjectIdentity> versions(ObjectKey key){throw new AssertionError();}};
      var authority=new ReceiptArtifactAuthority(f.store,storage,Clock.systemUTC(),manager);before=System.nanoTime();assertEquals(503,assertThrows(DmFailure.class,()->authority.verify(f.actor,f.caller.tenant(),f.document,complete.receiptId().toString())).status());assertEquals(facts,f.store.uploads.outcomes);assertTrue(f.store.uploads.consumed.isEmpty());assertTrue(f.store.docs.get(f.document).versions().isEmpty());
     }else{
      var app=f.uploads();UUID upload=f.admitted(app,UploadFormat.PDF);before=System.nanoTime();assertEquals(503,assertThrows(DmFailure.class,()->app.transfer(f.caller,upload,"elapsed-role",source.getInputStream())).status());assertTrue(f.storage.objects.isEmpty());assertTrue(f.store.uploads.outcomes.values().stream().noneMatch(outcome->outcome.key().operation().equals("upload.transfer")));
     }
     long elapsed=Duration.ofNanos(System.nanoTime()-before).toMillis();long limit=mode.equals("TRANSFER")?300_000:mode.equals("PREAPPEND")?60_000:30_000;
     assertTrue(elapsed>=limit-1000&&elapsed<limit+30_000,"Public production budget must be observed, with bounded scheduling tolerance");assertTrue(sent.get()>0);long retained=payloadBytes(root);assertTrue(retained>0&&retained<=f.bytes().length);assertNull(peerFailure.get());
     stop.set(true);source.close();peer.close();writer.interrupt();writer.join(5000);assertFalse(writer.isAlive());
     assertTimeoutPreemptively(Duration.ofSeconds(10),()->{while(!manager.ready())Thread.onSpinWait();});assertEquals(0,payloadBytes(root));
     System.out.println("{\"requirement\":\"GTEMP-09\",\"mode\":\""+mode+"\",\"elapsedMs\":"+elapsed+",\"retainedBytesBeforeRecovery\":"+retained+",\"realFilesystem\":true,\"realTcp\":true,\"realProviders\":false,\"result\":\"PASS\"}");
    }finally{stop.set(true);source.close();peer.close();writer.interrupt();writer.join(5000);assertFalse(writer.isAlive());}
   }
  }finally{f.closeContent();}
 }
 private static long payloadBytes(Path root)throws IOException{try(var files=Files.list(root)){long result=0;for(Path file:files.toList())if(file.getFileName().toString().startsWith("dkp-transfer-")||file.getFileName().toString().startsWith("dkp-preappend-"))result+=Files.size(file);return result;}}
}
