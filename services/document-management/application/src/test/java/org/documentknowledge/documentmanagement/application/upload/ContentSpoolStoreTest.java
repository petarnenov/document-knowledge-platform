package org.documentknowledge.documentmanagement.application.upload;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ContentSpoolStoreTest {
 @TempDir Path root;
 final Clock clock=Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"),ZoneOffset.UTC);
 static final String A="a".repeat(64),B="b".repeat(64),C="c".repeat(64);
 @BeforeEach void permissions()throws Exception{mode(root,"rwx------");}
 static void mode(Path p,String mode)throws IOException{Files.setPosixFilePermissions(p,PosixFilePermissions.fromString(mode));}
 void unavailable(Runnable action){assertEquals(503,assertThrows(DmFailure.class,action::run).status());}
 void quota(Runnable action){assertEquals(429,assertThrows(DmFailure.class,action::run).status());}
 @Test void exactSlotOwnershipAndActiveReservationSurviveServiceClose()throws Exception{
  var store=new ContentSpoolStore(root);var first=store.reserve(A);var second=store.reserve(B);
  assertEquals(64,first.write(new ByteArrayInputStream(new byte[]{1,2}),2,clock).length());
  quota(()->store.reserve(A));quota(()->store.reserve(C));store.close();unavailable(()->store.reserve(C));unavailable(()->new ContentSpoolStore(root));
  first.close();second.close();try(var next=new ContentSpoolStore(root);var lease=next.reserve(A)){lease.write(new ByteArrayInputStream(new byte[]{3}),1,clock);}
 }
 @Test void failedDeleteRemainsChargedAndRecoveryNeverTouchesActiveSlot()throws Exception{
  try(var store=new ContentSpoolStore(root)){
   var a=store.reserve(A);var b=store.reserve(B);a.write(new ByteArrayInputStream(new byte[20]),20,clock);b.write(new ByteArrayInputStream(new byte[30]),30,clock);
   Path dir=root.resolve("content-gateway");mode(dir,"r-x------");try{unavailable(a::close);assertEquals(20,Files.size(dir.resolve("slot-0.bytes")));}finally{mode(dir,"rwx------");}
   try(var replacement=store.reserve(C)){assertEquals(30,Files.size(dir.resolve("slot-1.bytes")));assertEquals(0,Files.size(dir.resolve("slot-0.bytes")));}b.close();
  }
 }
 @Test void staleClosedOwnerCannotDeleteSameActorSlotReusedByNewProcessOwner()throws Exception{
  var old=new ContentSpoolStore(root);var stale=old.reserve(A);stale.write(new ByteArrayInputStream(new byte[20]),20,clock);Path dir=root.resolve("content-gateway");mode(dir,"r-x------");try{unavailable(stale::close);old.close();}finally{mode(dir,"rwx------");}
  try(var current=new ContentSpoolStore(root);var active=current.reserve(A)){
   active.write(new ByteArrayInputStream(new byte[31]),31,clock);unavailable(stale::close);assertEquals(31,Files.size(dir.resolve("slot-0.bytes")));try(var channel=active.open()){assertEquals(31,channel.size());}
  }
 }
 @Test void malformedOwnershipForeignNamesSymlinksAndUnsafePermissionsFailClosed()throws Exception{
  try(var store=new ContentSpoolStore(root)){}
  Path dir=root.resolve("content-gateway"),marker=dir.resolve(".owner");String original=Files.readString(marker);Files.writeString(marker,"unknown\n");unavailable(()->new ContentSpoolStore(root));Files.writeString(marker,original);
  Path foreign=dir.resolve("foreign");Files.writeString(foreign,"keep");unavailable(()->new ContentSpoolStore(root));assertEquals("keep",Files.readString(foreign));Files.delete(foreign);
  Path bytes=dir.resolve("slot-0.bytes");Files.createSymbolicLink(bytes,marker);unavailable(()->new ContentSpoolStore(root));Files.delete(bytes);
  mode(marker,"rw-r--r--");unavailable(()->new ContentSpoolStore(root));mode(marker,"rw-------");
  mode(root,"rwxr-x---");unavailable(()->new ContentSpoolStore(root));mode(root,"rwx------");
  try(var store=new ContentSpoolStore(root)){var lease=store.reserve(A);Path meta=dir.resolve("slot-0.meta");String expected=Files.readString(meta);Files.writeString(meta,"dkp-content-slot-v1\n"+B+"\n");unavailable(lease::close);assertTrue(Files.exists(bytes));Files.writeString(meta,expected);lease.close();}
 }
 @Test void orphanAndLegacyFilesAreNotAdoptedOrDeleted()throws Exception{
  Path legacy=root.resolve("dkp-bytes-unowned.tmp");Files.writeString(legacy,"untrusted");unavailable(()->new ContentSpoolStore(root));assertEquals("untrusted",Files.readString(legacy));Files.delete(legacy);
  try(var store=new ContentSpoolStore(root)){}
  Path orphan=root.resolve("content-gateway/slot-0.bytes");Files.createFile(orphan,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));unavailable(()->new ContentSpoolStore(root));assertTrue(Files.exists(orphan));
 }
 @Test void expectedLengthAndDeadlineRejectBeforeWritingInvalidChunk()throws Exception{
  try(var store=new ContentSpoolStore(root);var a=store.reserve(A)){
   assertEquals(422,assertThrows(DmFailure.class,()->a.write(new ByteArrayInputStream(new byte[3]),2,clock)).status());assertEquals(0,Files.size(root.resolve("content-gateway/slot-0.bytes")));
   assertEquals(422,assertThrows(DmFailure.class,()->a.write(new ByteArrayInputStream(new byte[0]),2,clock)).status());
   unavailable(()->{try{a.write(InputStream.nullInputStream(),0,clock);}catch(IOException e){throw new UncheckedIOException(e);}});
   Clock advancing=new Clock(){int calls;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return clock.instant().plusSeconds(calls++==0?0:301);}};
   unavailable(()->{try{a.write(new ByteArrayInputStream(new byte[1]),1,advancing);}catch(IOException e){throw new UncheckedIOException(e);}});
  }
 }
 @Test void actualHundredMillionByteHardLimitRejectsExcessBeforePrivateWrite()throws Exception{
  try(var store=new ContentSpoolStore(root);var lease=store.reserve(A)){
   InputStream generated=new InputStream(){long remaining=100_000_001L;public int read(){if(remaining==0)return -1;remaining--;return 0;}public int read(byte[] b,int off,int length){if(remaining==0)return -1;int n=(int)Math.min(remaining,length);Arrays.fill(b,off,off+n,(byte)0);remaining-=n;return n;}};
   assertEquals(413,assertThrows(DmFailure.class,()->lease.write(generated,100_000_000L,clock)).status());
   assertTrue(Files.size(root.resolve("content-gateway/slot-0.bytes"))<=100_000_000L);
   assertEquals(99_942_400L,Files.size(root.resolve("content-gateway/slot-0.bytes")),"Oversending final chunk is rejected as a whole before writing");
  }
 }
 @Test void realProcessCrashLeavesDurableOwnedBytesAndExclusiveLockIsNotTransferable()throws Exception{
  try(var parent=new ContentSpoolStore(root)){assertEquals(23,child("lock"));}
  assertEquals(17,child("crash"));Path dir=root.resolve("content-gateway");assertEquals(65536,Files.size(dir.resolve("slot-0.bytes")));assertTrue(Files.exists(dir.resolve("slot-0.meta")));
  try(var recovered=new ContentSpoolStore(root)){assertFalse(Files.exists(dir.resolve("slot-0.bytes")));try(var a=recovered.reserve(A);var b=recovered.reserve(B)){quota(()->recovered.reserve(C));}}
 }
 int child(String mode)throws Exception{
  Set<String> paths=new LinkedHashSet<>();for(Class<?> type:List.of(ContentSpoolStoreTest.class,ContentSpoolStore.class,DmFailure.class,UploadModels.class,org.documentknowledge.identity.Actor.class))paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
  Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",String.join(File.pathSeparator,paths),Probe.class.getName(),mode,root.toString()).redirectErrorStream(true).start();
  assertTrue(child.waitFor(20,TimeUnit.SECONDS),"Bounded child process must finish");String output=new String(child.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertTrue(output.isEmpty(),"Child emits no paths or file content");return child.exitValue();
 }
 public static final class Probe {
  public static void main(String[] args)throws Exception{
   try(var store=new ContentSpoolStore(Path.of(args[1]))){if(args[0].equals("lock"))Runtime.getRuntime().halt(24);var lease=store.reserve(A);lease.write(new ByteArrayInputStream(new byte[65536]),65536,Clock.systemUTC());Runtime.getRuntime().halt(17);}catch(DmFailure unavailable){Runtime.getRuntime().halt(23);}
  }
 }
}
