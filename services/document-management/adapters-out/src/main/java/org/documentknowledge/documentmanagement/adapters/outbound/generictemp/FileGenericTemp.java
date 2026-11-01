package org.documentknowledge.documentmanagement.adapters.outbound.generictemp;

import com.sun.security.auth.module.UnixSystem;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import org.documentknowledge.authorization.TrustedAuthorityFiles;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.documentmanagement.application.upload.GenericTempPort.*;
import static org.documentknowledge.documentmanagement.adapters.outbound.generictemp.GenericTempLedger.State;

/** Twelve fixed durable lanes under one actual process lock; close never means physical absence. */
public final class FileGenericTemp implements GenericTempPort {
 private static final byte[] MARKER="dkp-generic-upload-temp-v1\n".getBytes(StandardCharsets.US_ASCII);
 @FunctionalInterface interface OwnedDelete{void delete(Path path)throws IOException;}
 enum Event{HEALTH_CHECK,NEXT_FORCED,RENAMED,RECORD_FORCED,BEFORE_CREATE,AFTER_WRITE,PAYLOAD_FORCED,BEFORE_DELETE,AFTER_DELETE,RECORD_REMOVED}
 @FunctionalInterface interface Fault{void after(Event event,GenericTempLedger record)throws IOException;}
 private final Fault fault;
 private final Path root,directory,marker,lockPath;private final TrustedAuthorityFiles trusted;private final OwnedDelete delete;private final long recoveryWaitMillis;
 private final long uid=new UnixSystem().getUid();private final org.documentknowledge.documentmanagement.adapters.outbound.temp.FixedInodeProcessLock processLock;private final Object rootKey,directoryKey,markerKey,lockKey;
 private final GenericTempLedger[] records=new GenericTempLedger[12];private volatile Owned[] owners=new Owned[12];private final ReentrantLock gate=new ReentrantLock();
 private volatile boolean probing,closing,closed;private volatile GenericTempBudget recovery;private volatile Thread recoveryWorker;
 public FileGenericTemp(Path root){this(root,TrustedAuthorityFiles.system());}
 FileGenericTemp(Path root,TrustedAuthorityFiles trusted){this(root,trusted,Files::delete,60_000);}
 // Java-only deterministic mechanism seam. Production has no path/platform/deadline override.
 FileGenericTemp(Path root,TrustedAuthorityFiles trusted,OwnedDelete delete,long waitMillis){this(root,trusted,delete,waitMillis,(event,record)->{});}
 FileGenericTemp(Path root,TrustedAuthorityFiles trusted,OwnedDelete delete,long waitMillis,Fault fault){
  if(root==null||!root.isAbsolute()||!root.equals(root.normalize())||waitMillis<1||waitMillis>60_000)throw unavailable();
  this.fault=Objects.requireNonNull(fault);this.root=root;this.trusted=Objects.requireNonNull(trusted);this.delete=Objects.requireNonNull(delete);recoveryWaitMillis=waitMillis;
  directory=root.resolve(".upload-temp");marker=directory.resolve(".owner");lockPath=root.resolve(".upload-temp.lock");org.documentknowledge.documentmanagement.adapters.outbound.temp.FixedInodeProcessLock lock=null;
  try{
   validateRoot();if(!Files.exists(lockPath,LinkOption.NOFOLLOW_LINKS)){Files.createFile(lockPath,mode("rw-------"));force(root);}privateFile(lockPath,0);Object expected=key(lockPath);
   lock=org.documentknowledge.documentmanagement.adapters.outbound.temp.FixedInodeProcessLock.acquire(lockPath);if(!expected.equals(key(lockPath)))throw unavailable();processLock=lock;lockKey=expected;rootKey=key(root);
   if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS)){refuseLegacy();Files.createDirectory(directory,mode("rwx------"));force(root);writeNew(marker,MARKER);force(directory);}
   directoryKey=key(directory);markerKey=key(marker);guard();for(int i=0;i<12;i++){discardNext(i);records[i]=read(i);}validatePayloads();
  }catch(Exception failure){if(lock!=null)try{lock.close();}catch(IOException ignored){}throw unavailable();}
 }
 @Override public Workspace reserve(Role role,Identity identity,GenericTempBudget budget){
  Objects.requireNonNull(role);Objects.requireNonNull(identity);budget.check();if(budget.role()!=role)throw unavailable();if(role==Role.TRANSFER?identity.receiptId()!=null:identity.receiptId()==null)throw unavailable();
  GenericTempBudget recoveryBudget=budget.recovery();boolean reconcile=false;
  try(var lease=enter()){
   live();guardUnchecked();integrity();if(probing)throw unavailable();boolean retained=false;for(int i=0;i<12;i++)if(records[i]!=null&&owners[i]==null)retained=true;
   if(retained){if(anyOwner())throw unavailable();probing=true;reconcile=true;}
  }
  if(reconcile&&!recoverBounded(recoveryBudget,false))throw unavailable();
  try(var lease=enter()){
   live();budget.check();guardUnchecked();if(probing)throw unavailable();int first=role==Role.TRANSFER?0:10,last=role==Role.TRANSFER?10:12;
   for(int i=first;i<last;i++)if(records[i]==null&&owners[i]==null){var value=new GenericTempLedger(role,i-first,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),identity,State.RESERVED);publish(i,null,value,budget);live();budget.check();var owner=new Owned(i,value.operationId(),value.ownerEpoch(),budget);var updated=owners.clone();updated[i]=owner;owners=updated;if(closing){budget.cancel();owner.close();throw unavailable();}return owner;}
   throw new DmFailure(429,"QUOTA_EXCEEDED");
  }
 }
 @Override public boolean ready(){
  GenericTempBudget budget=new GenericTempBudget(Clock.systemUTC(),Role.PREAPPEND);if(closing||closed||!gate.tryLock())return false;boolean active;
  try{if(closing||closed||!processLock.isValid()||probing)return false;active=anyOwner();probing=true;}finally{gate.unlock();}
  return recoverBounded(budget,active);
 }
 private boolean recoverBounded(GenericTempBudget budget,boolean healthOnly){
  var result=new CompletableFuture<Boolean>();
  if(!gate.tryLock()){budget.cancel();probing=false;return false;}try{
   if(closing||closed){probing=false;releaseIfClosing();return false;}
   try{budget.check();}catch(RuntimeException expired){probing=false;releaseIfClosing();return false;}
   recovery=budget;recoveryWorker=Thread.ofVirtual().unstarted(()->{boolean success=false;try{if(healthOnly){try(var lease=enter(budget)){budget.check();try{fault.after(Event.HEALTH_CHECK,Arrays.stream(records).filter(Objects::nonNull).findFirst().orElse(null));}catch(IOException failure){throw unavailable();}guardUnchecked();integrity();for(int i=0;i<12;i++)if(records[i]!=null&&owners[i]==null||owners[i]!=null&&owners[i].closeRequested)throw unavailable();else if(owners[i]!=null)owners[i].budget.check();}}else recover(budget);budget.check();success=true;}catch(RuntimeException failure){}finally{gate.lock();try{probing=false;recovery=null;recoveryWorker=null;releaseIfClosing();}finally{gate.unlock();}result.complete(success);}});
   try{recoveryWorker.start();}catch(RuntimeException failure){probing=false;recovery=null;recoveryWorker=null;releaseIfClosing();return false;}
  }finally{gate.unlock();}
  try{return result.get(Math.min(TimeUnit.MILLISECONDS.toNanos(recoveryWaitMillis),budget.remaining().toNanos()),TimeUnit.NANOSECONDS)&&!closing;}
  catch(InterruptedException interrupted){budget.cancel();Thread worker=recoveryWorker;if(worker!=null)worker.interrupt();Thread.currentThread().interrupt();return false;}
  catch(ExecutionException|TimeoutException|RuntimeException failure){budget.cancel();Thread worker=recoveryWorker;if(worker!=null)worker.interrupt();return false;}
 }
 @Override public void close(){closing=true;for(var owner:owners)if(owner!=null){owner.budget.cancel();owner.close();}GenericTempBudget budget=recovery;if(budget!=null)budget.cancel();Thread worker=recoveryWorker;if(worker!=null)worker.interrupt();if(gate.tryLock())try{releaseIfClosing();}finally{gate.unlock();}}
 private void releaseIfClosing(){if(closing&&!closed&&!probing&&!anyOwner())try{processLock.close();closed=true;}catch(IOException unknown){}}
 private boolean anyOwner(){for(var owner:owners)if(owner!=null)return true;return false;}
 private void recover(GenericTempBudget budget){
  try{live();budget.check();guard();for(int i=0;i<12;i++){if(owners[i]!=null)throw unavailable();discardNext(i);if(!Objects.equals(read(i),records[i]))throw unavailable();}validatePayloads();
   for(int i=0;i<12;i++)if(records[i]!=null){publish(i,records[i],records[i].epoch(UUID.randomUUID()),budget);clean(i,budget);}
  }catch(Exception failure){throw safe(failure);}
 }
 private final class Owned implements Workspace {
  private final int index;private final UUID operation,epoch;private final GenericTempBudget budget;private int inFlight;private boolean cleanupFailed;private volatile boolean closeRequested,ended;
  Owned(int index,UUID operation,UUID epoch,GenericTempBudget budget){this.index=index;this.operation=operation;this.epoch=epoch;this.budget=budget;}
  private GenericTempLedger current(GenericTempBudget budget){if(budget!=this.budget)throw unavailable();live();if(closeRequested||ended||owners[index]!=this)throw unavailable();var value=records[index];if(value==null||!value.operationId().equals(operation)||!value.ownerEpoch().equals(epoch))throw unavailable();try{guard();if(!value.equals(read(index)))throw unavailable();validatePayloads();return value;}catch(Exception failure){throw safe(failure);}}
  @Override public ArtifactFile write(InputStream input,GenericTempBudget budget){
   Path path;long maximum;try(var lease=enter(budget)){var value=current(budget);budget.check();if(inFlight!=0||value.state()!=State.RESERVED)throw unavailable();publish(index,value,value.state(State.MAY_EXIST),budget);path=payload(value);maximum=value.identity().declaredSize();inFlight++;}
   try(var inputRead=budget.reading(input)){
    var hash=MessageDigest.getInstance("SHA-256");long count=0;try(var lease=enter(budget)){current(budget);budget.check();}
    fault.after(Event.BEFORE_CREATE,records[index]);try(var output=FileChannel.open(path,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),mode("rw-------"))){
     byte[] bytes=new byte[65536];while(true){budget.check();int n=input.read(bytes);budget.check();if(n<0)break;if(n==0)continue;inputRead.progress();try(var lease=enter(budget)){current(budget);}
      if(n>100_000_000-count)throw new DmFailure(413,"PAYLOAD_TOO_LARGE");if(n>maximum-count)throw new DmFailure(422,"SIZE_MISMATCH");ByteBuffer chunk=ByteBuffer.wrap(bytes,0,n);while(chunk.hasRemaining()){try(var lease=enter(budget)){current(budget);budget.check();}output.write(chunk);}hash.update(bytes,0,n);count+=n;fault.after(Event.AFTER_WRITE,records[index]);
     }try(var lease=enter(budget)){current(budget);budget.check();}output.force(true);fault.after(Event.PAYLOAD_FORCED,records[index]);budget.check();
    }try(var lease=enter(budget)){current(budget);budget.check();}force(root);budget.check();if(count!=maximum)throw new DmFailure(422,"SIZE_MISMATCH");return new ArtifactFile(path,count,HexFormat.of().formatHex(hash.digest()));
   }catch(Exception failure){throw safe(failure);}finally{gate.lock();try{inFlight--;settle();}finally{gate.unlock();}}
  }
  @Override public Use retain(GenericTempBudget budget){try(var lease=enter(budget)){var value=current(budget);budget.check();if(value.state()!=State.MAY_EXIST)throw unavailable();inFlight++;return new Use(){boolean released;public void close(){gate.lock();try{if(!released){released=true;inFlight--;settle();}}finally{gate.unlock();}}};}}
  @Override public void finish(GenericTempBudget budget){try(var lease=enter(budget)){current(budget);budget.check();if(cleanupFailed||inFlight!=0)throw unavailable();try{clean(index,budget);}catch(RuntimeException failure){cleanupFailed=true;throw failure;}}}
  @Override public void close(){closeRequested=true;if(gate.tryLock())try{settle();}finally{gate.unlock();}}
  private void settle(){if(closeRequested&&inFlight==0&&!ended){ended=true;if(owners[index]==this){var updated=owners.clone();updated[index]=null;owners=updated;}releaseIfClosing();}}
 }
 private void clean(int index,GenericTempBudget budget){
  try{var value=records[index];budget.check();if(value==null)return;if(value.state()!=State.ABSENT){if(value.state()!=State.DELETE_PENDING){publish(index,value,value.state(State.DELETE_PENDING),budget);value=records[index];}
    budget.check();guard();validatePayloads();Path path=payload(value);if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)){privateFile(path,value.identity().declaredSize());fault.after(Event.BEFORE_DELETE,value);delete.delete(path);fault.after(Event.AFTER_DELETE,value);}budget.check();force(root);budget.check();publish(index,value,value.state(State.ABSENT),budget);value=records[index];}
   guard();budget.check();if(!value.equals(read(index)))throw unavailable();Files.delete(state(index));force(directory);budget.check();records[index]=null;fault.after(Event.RECORD_REMOVED,value);
  }catch(Exception failure){throw safe(failure);}
 }
 private void publish(int index,GenericTempLedger expected,GenericTempLedger value,GenericTempBudget budget){try{budget.check();guard();if(!Objects.equals(expected,read(index)))throw unavailable();discardNext(index);writeNew(next(index),value.encode());fault.after(Event.NEXT_FORCED,value);budget.check();Files.move(next(index),state(index),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);fault.after(Event.RENAMED,value);force(directory);records[index]=value;fault.after(Event.RECORD_FORCED,value);budget.check();}catch(Exception failure){throw safe(failure);}}
 private GenericTempLedger read(int index)throws IOException{Path state=state(index);if(!Files.exists(state,LinkOption.NOFOLLOW_LINKS))return null;privateFile(state,2048);GenericTempLedger value;try{value=GenericTempLedger.decode(trusted.readBounded(state,2048,true));}catch(RuntimeException failure){throw unavailable();}if(value.role()!=(index<10?Role.TRANSFER:Role.PREAPPEND)||value.lane()!=(index<10?index:index-10))throw unavailable();return value;}
 private void discardNext(int index)throws IOException{Path next=next(index);if(Files.exists(next,LinkOption.NOFOLLOW_LINKS)){privateFile(next,2048);Files.delete(next);force(directory);}}
 private void integrity(){try{for(int i=0;i<12;i++)if(!Objects.equals(records[i],read(i)))throw unavailable();validatePayloads();}catch(Exception failure){throw safe(failure);}}
 private void guardUnchecked(){try{guard();}catch(IOException failure){throw unavailable();}}
 private void guard()throws IOException{
  live();if(!rootKey.equals(key(root))||!lockKey.equals(key(lockPath))||!directoryKey.equals(key(directory))||!markerKey.equals(key(marker)))throw unavailable();validateRoot();privateFile(lockPath,0);privateDirectory(directory);privateFile(marker,MARKER.length);if(!MessageDigest.isEqual(MARKER,trusted.readBounded(marker,MARKER.length,true)))throw unavailable();
  Set<String> allowed=new HashSet<>();allowed.add(".owner");for(int i=0;i<12;i++){allowed.add(state(i).getFileName().toString());allowed.add(next(i).getFileName().toString());}try(var stream=Files.list(directory)){var names=stream.limit(26).map(p->p.getFileName().toString()).toList();if(names.size()>25||!allowed.containsAll(names))throw unavailable();}
 }
 private void validateRoot()throws IOException{privateDirectory(root);if(!MessageDigest.isEqual("dkp-private-spool-v1\n".getBytes(StandardCharsets.US_ASCII),trusted.readBounded(root.resolve(".spool-authority"),64,true)))throw unavailable();}
 private void validatePayloads()throws IOException{
  Map<Path,GenericTempLedger> allowed=new HashMap<>();for(var record:records)if(record!=null)allowed.put(payload(record),record);
  try(var stream=Files.list(root)){var paths=stream.limit(4097).toList();if(paths.size()>4096)throw unavailable();for(Path path:paths)if(genericName(path.getFileName().toString())){var record=allowed.get(path);if(record==null||record.state()==State.RESERVED||record.state()==State.ABSENT)throw unavailable();privateFile(path,record.identity().declaredSize());}}
 }
 private void refuseLegacy()throws IOException{try(var stream=Files.list(root)){var names=stream.limit(4097).map(p->p.getFileName().toString()).toList();if(names.size()>4096||names.stream().anyMatch(n->genericName(n)||n.startsWith("dkp-bytes-")&&n.endsWith(".tmp")||n.startsWith("verified-copy-")&&n.endsWith(".bin")))throw unavailable();}}
 private static boolean genericName(String name){return name.startsWith("dkp-transfer-")||name.startsWith("dkp-preappend-");}
 private Path state(int index){return directory.resolve((index<10?"transfer-":"preappend-")+String.format(java.util.Locale.ROOT,"%02d",index<10?index:index-10)+".json");}
 private Path next(int index){return state(index).resolveSibling(state(index).getFileName().toString().replace(".json",".next"));}
 private Path payload(GenericTempLedger value){return root.resolve("dkp-"+value.role().name().toLowerCase(java.util.Locale.ROOT)+"-"+value.operationId()+"-"+value.reservationId()+".bin");}
 private void privateDirectory(Path path)throws IOException{attributes(path,0040000,0700,Long.MAX_VALUE);}
 private void privateFile(Path path,long maximum)throws IOException{attributes(path,0100000,0600,maximum);}
 private void attributes(Path path,int type,int permissions,long maximum)throws IOException{var a=Files.readAttributes(path,"unix:uid,mode,nlink,size",LinkOption.NOFOLLOW_LINKS);int mode=((Number)a.get("mode")).intValue();if(((Number)a.get("uid")).longValue()!=uid||(mode&0170000)!=type||(mode&07777)!=permissions||type==0100000&&((Number)a.get("nlink")).intValue()!=1||((Number)a.get("size")).longValue()>maximum)throw unavailable();}
 private static Object key(Path path)throws IOException{Object value=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();if(value==null)throw unavailable();return value;}
 private static java.nio.file.attribute.FileAttribute<?> mode(String value){return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(value));}
 private static void writeNew(Path path,byte[] bytes)throws IOException{try(var output=FileChannel.open(path,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),mode("rw-------"))){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);}}
 private static void force(Path path)throws IOException{try(var channel=FileChannel.open(path,StandardOpenOption.READ)){channel.force(true);}}
 private Lease enter(GenericTempBudget budget){try{budget.check();if(!gate.tryLock(budget.remaining().toNanos(),TimeUnit.NANOSECONDS))throw unavailable();try{budget.check();return new Lease();}catch(RuntimeException expired){gate.unlock();throw expired;}}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}}
 private Lease enter(){if(!gate.tryLock())throw unavailable();return new Lease();}
 private final class Lease implements AutoCloseable{public void close(){try{if(gate.getHoldCount()==1)for(var owner:owners)if(owner!=null&&owner.closeRequested)owner.settle();releaseIfClosing();}finally{gate.unlock();}}}
 private void live(){if(closing||closed||!processLock.isValid())throw unavailable();}
 private static RuntimeException safe(Exception failure){return failure instanceof DmFailure dm?dm:unavailable();}
 private static DmFailure unavailable(){return new DmFailure(503,"UNAVAILABLE");}
}
