package org.documentknowledge.documentmanagement.adapters.outbound.verificationtemp;

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
import org.documentknowledge.authorization.TrustedAuthorityFiles;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.application.upload.*;
import static org.documentknowledge.documentmanagement.adapters.outbound.verificationtemp.VerificationLedger.State;

/** One locked durable namespace. Metadata and payload absence are separate facts. */
public final class FileVerificationWorkspace implements VerificationWorkspacePort {
    private static final byte[] MARKER="dkp-verification-spool-v1\n".getBytes(StandardCharsets.US_ASCII);
    private final Path root,directory,state,next,marker,lockPath;
    private final TrustedAuthorityFiles trusted;
    @FunctionalInterface interface OwnedDelete { void delete(Path path) throws IOException; }
    private final OwnedDelete ownedDelete;private final long recoveryWaitMillis;
    private final long uid=new UnixSystem().getUid();
    private final org.documentknowledge.documentmanagement.adapters.outbound.temp.FixedInodeProcessLock processLock;
    private final Object rootKey,directoryKey,markerKey,lockKey;
    private volatile VerificationLedger ledger;private volatile Owned active;private volatile boolean probing,closing,closed;
    private final java.util.concurrent.locks.ReentrantLock gate=new java.util.concurrent.locks.ReentrantLock();
    private volatile CompleteBudget recovery;private volatile Thread recoveryWorker;
    public FileVerificationWorkspace(Path root){this(root,TrustedAuthorityFiles.system());}
    // Filesystem/platform constructor seam is Java-only; never configured through the runtime.
    FileVerificationWorkspace(Path root,TrustedAuthorityFiles trusted){this(root,trusted,Files::delete,60_000);}
    // Deterministic failure/deadline seams exist only in Java tests; production always uses exact delete and 60s.
    FileVerificationWorkspace(Path root,TrustedAuthorityFiles trusted,OwnedDelete ownedDelete,long recoveryWaitMillis){
        if(recoveryWaitMillis<1||recoveryWaitMillis>60_000)throw unavailable();this.ownedDelete=Objects.requireNonNull(ownedDelete);this.recoveryWaitMillis=recoveryWaitMillis;
        this.root=canonical(root);this.trusted=Objects.requireNonNull(trusted);
        directory=this.root.resolve(".verification-spool");state=directory.resolve("state.json");next=directory.resolve("state.next");marker=directory.resolve(".owner");lockPath=this.root.resolve(".verification-spool.lock");
        org.documentknowledge.documentmanagement.adapters.outbound.temp.FixedInodeProcessLock acquired=null;
        try{
            validateRoot();
            if(!Files.exists(lockPath,LinkOption.NOFOLLOW_LINKS)){Files.createFile(lockPath,mode("rw-------"));force(this.root);}
            privateFile(lockPath,0);Object expectedLock=key(lockPath);
            acquired=org.documentknowledge.documentmanagement.adapters.outbound.temp.FixedInodeProcessLock.acquire(lockPath);privateFile(lockPath,0);
            if(!expectedLock.equals(key(lockPath)))throw unsafe();
            this.processLock=acquired;this.lockKey=expectedLock;this.rootKey=key(this.root);
            if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS)){
                refuseLegacy();Files.createDirectory(directory,mode("rwx------"));force(this.root);
                writeNew(marker,MARKER);force(directory);
            }
            this.directoryKey=key(directory);this.markerKey=key(marker);
            guard();discardNext();ledger=readLedger();validatePayloads(ledger);
        }catch(Exception failure){if(acquired!=null)try{acquired.close();}catch(IOException ignored){}throw unavailable();}
    }
    @Override public Workspace reserve(Identity identity,CompleteBudget.Phase phase){
        Objects.requireNonNull(identity);phase.check();
        try(var lease=enter()){live();if(active!=null)throw new DmFailure(429,"QUOTA_EXCEEDED");if(probing)throw unavailable();probing=true;}
        try{
            recover(phase);phase.check();
            try(var lease=enter()){live();guard();if(ledger!=null)throw unavailable();
                var value=new VerificationLedger(UUID.randomUUID(),UUID.randomUUID(),identity,State.RESERVED,VerificationLedger.Role.NONE,State.RESERVED,null);
                publish(null,value,phase);active=new Owned(value.operationId(),value.ownerEpoch());return active;
            }
        }catch(Exception failure){throw safe(failure);}finally{gate.lock();try{probing=false;releaseIfClosing();}finally{gate.unlock();}}
    }
    @Override public void check(Workspace workspace,CompleteBudget.Phase phase){try(var lease=enter()){phase.check();if(!(workspace instanceof Owned owned)||owned!=active)throw unavailable();owned.current();}}
    @Override public boolean ready(){
        if(closing||closed)return false;if(!gate.tryLock())return active!=null&&!active.ended&&!active.closeRequested;
        var result=new java.util.concurrent.CompletableFuture<Boolean>();CompleteBudget budget;
        try{
            if(closing||closed||!processLock.isValid())return false;if(active!=null)return !active.ended&&!active.closeRequested;if(probing)return false;
            probing=true;budget=new CompleteBudget(Clock.systemUTC());recovery=budget;
            recoveryWorker=Thread.ofVirtual().unstarted(()->{
                boolean success=false;
                try(var phase=budget.phase(CompleteBudget.Kind.STORAGE)){recover(phase);phase.check();success=true;}
                catch(Exception failure){/* A failed private recovery stays unavailable; no payload/exception details escape. */}
                finally{gate.lock();try{probing=false;recovery=null;recoveryWorker=null;releaseIfClosing();}finally{gate.unlock();}result.complete(success);}
            });
            try{recoveryWorker.start();}catch(RuntimeException failure){probing=false;recovery=null;recoveryWorker=null;releaseIfClosing();return false;}
        }finally{gate.unlock();}
        try{return result.get(recoveryWaitMillis,java.util.concurrent.TimeUnit.MILLISECONDS)&&!closing;}
        catch(InterruptedException interrupted){budget.cancel();Thread worker=recoveryWorker;if(worker!=null)worker.interrupt();Thread.currentThread().interrupt();return false;}
        catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException unavailable){budget.cancel();Thread worker=recoveryWorker;if(worker!=null)worker.interrupt();return false;}
    }
    @Override public void close(){closing=true;CompleteBudget current=recovery;if(current!=null)current.cancel();Thread worker=recoveryWorker;if(worker!=null)worker.interrupt();if(gate.tryLock())try{releaseIfClosing();}finally{gate.unlock();}}
    private void releaseIfClosing(){if(closing&&!closed&&active==null&&!probing){try{processLock.close();closed=true;}catch(IOException failure){/* Unknown ownership remains unusable, never transfer on a timer. */}}}
    private void recover(CompleteBudget.Phase phase)throws IOException{
        live();phase.check();guard();discardNext();VerificationLedger found=readLedger();if(!Objects.equals(found,ledger))throw unavailable();validatePayloads(found);
        if(found==null)return;
        publish(found,found.epoch(UUID.randomUUID()),phase);
        clean(false,null,phase);clean(true,null,phase);removeRecord(phase);
    }
    private final class Owned implements Workspace {
        private final UUID operation,epoch;private volatile boolean ended,closeRequested;private boolean cleanupFailed;private int inFlight;
        Owned(UUID operation,UUID epoch){this.operation=operation;this.epoch=epoch;}
        private void current(){live();if(ended||closeRequested||active!=this||ledger==null||!operation.equals(ledger.operationId())||!epoch.equals(ledger.ownerEpoch()))throw unavailable();try{guard();if(!ledger.equals(readLedger()))throw unavailable();validatePayloads(ledger);}catch(IOException failure){throw unavailable();}}
        @Override public Buffer source(){try(var lease=enter()){current();return new Slot(this,true,null);}}
        @Override public Use retain(CompleteBudget.Phase phase){try(var lease=enter()){current();phase.check();inFlight++;return new Use(){private boolean released;public void close(){gate.lock();try{if(!released){released=true;inFlight--;settle();}}finally{gate.unlock();}}};}}
        @Override public Buffer scratch(VerificationWorkspacePort.Role role,CompleteBudget.Phase phase){try(var lease=enter()){
            current();phase.check();if(inFlight!=0)throw unavailable();if(ledger.source()!=State.MAY_EXIST||ledger.scratch()!=State.ABSENT&&ledger.role()!=VerificationLedger.Role.NONE)throw unavailable();
            if(ledger.role()==VerificationLedger.Role.READBACK||role==VerificationWorkspacePort.Role.COPY&&ledger.role()!=VerificationLedger.Role.NONE||role==VerificationWorkspacePort.Role.READBACK&&ledger.role()!=VerificationLedger.Role.COPY)throw unavailable();
            UUID reservation=UUID.randomUUID();publishUnchecked(ledger.scratch(VerificationLedger.Role.valueOf(role.name()),State.RESERVED,reservation),phase);return new Slot(this,false,reservation);
        }}
        @Override public void finish(CompleteBudget.Phase phase){try(var lease=enter()){current();phase.check();if(cleanupFailed||inFlight!=0)throw unavailable();try{clean(false,null,phase);clean(true,null,phase);removeRecord(phase);}catch(IOException|RuntimeException failure){cleanupFailed=true;throw safe(failure);}}}
        @Override public void close(){closeRequested=true;if(gate.tryLock())try{settle();}finally{gate.unlock();}}
        private void settle(){if(closeRequested&&inFlight==0&&!ended){ended=true;if(active==this)active=null;releaseIfClosing();}}
    }
    private final class Slot implements Buffer {
        private final Owned owner;private final boolean source;private final UUID reservation;
        Slot(Owned owner,boolean source,UUID reservation){this.owner=owner;this.source=source;this.reservation=reservation;}
        private Path path(){owner.current();if(!source&&!Objects.equals(reservation,ledger.reservationId()))throw unavailable();return payload(ledger,source);}
        @Override public ArtifactFile write(InputStream input,CompleteBudget.Phase phase){
            Path path;long maximum;
            try(var lease=enter()){path=path();phase.check();if(owner.inFlight!=0)throw unavailable();State value=source?ledger.source():ledger.scratch();if(value!=State.RESERVED)throw unavailable();maximum=ledger.identity().declaredSize();publishUnchecked(source?ledger.source(State.MAY_EXIST):ledger.scratch(ledger.role(),State.MAY_EXIST,reservation),phase);owner.inFlight++;}
            try{
                var digest=MessageDigest.getInstance("SHA-256");long count=0;
                try(var lease=enter()){path();phase.check();}
                try(var output=FileChannel.open(path,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),mode("rw-------"))){
                    byte[] bytes=new byte[65536];int read;
                    while(true){phase.check();read=input.read(bytes);phase.check();if(read<0)break;if(read==0)continue;
                        try(var lease=enter()){path();}if(read>maximum-count)throw new DmFailure(422,"SIZE_MISMATCH");
                        ByteBuffer chunk=ByteBuffer.wrap(bytes,0,read);while(chunk.hasRemaining()){try(var lease=enter()){path();phase.check();}output.write(chunk);}digest.update(bytes,0,read);count+=read;
                    }
                    try(var lease=enter()){path();phase.check();}output.force(true);phase.check();
                }
                try(var lease=enter()){path();phase.check();}force(root);phase.check();if(count!=maximum)throw new DmFailure(422,"SIZE_MISMATCH");
                try(var lease=enter()){path();privateFile(path,maximum);}
                return new ArtifactFile(path,count,HexFormat.of().formatHex(digest.digest()));
            }catch(Exception failure){throw safe(failure);}finally{gate.lock();try{owner.inFlight--;owner.settle();}finally{gate.unlock();}}
        }
        @Override public void cleanup(CompleteBudget.Phase phase){try(var lease=enter()){path();phase.check();if(owner.inFlight!=0)throw unavailable();try{clean(source,reservation,phase);}catch(IOException|RuntimeException failure){owner.cleanupFailed=true;throw safe(failure);}}}
    }
    private void clean(boolean source,UUID reservation,CompleteBudget.Phase phase)throws IOException{
        phase.check();State current=source?ledger.source():ledger.scratch();if(!source&&reservation!=null&&!reservation.equals(ledger.reservationId()))throw unavailable();
        if(current==State.ABSENT)return;
        if(!source&&ledger.role()==VerificationLedger.Role.NONE){publish(ledger,ledger.scratch(ledger.role(),State.ABSENT,null),phase);return;}
        Path file=payload(ledger,source);
        if(current!=State.DELETE_PENDING)publish(ledger,source?ledger.source(State.DELETE_PENDING):ledger.scratch(ledger.role(),State.DELETE_PENDING,ledger.reservationId()),phase);
        phase.check();guard();validatePayloads(ledger);if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){privateFile(file,ledger.identity().declaredSize());ownedDelete.delete(file);}
        phase.check();force(root);phase.check();publish(ledger,source?ledger.source(State.ABSENT):ledger.scratch(ledger.role(),State.ABSENT,ledger.reservationId()),phase);
    }
    private void removeRecord(CompleteBudget.Phase phase)throws IOException{
        if(ledger.source()!=State.ABSENT||ledger.scratch()!=State.ABSENT)throw unavailable();phase.check();guard();if(!ledger.equals(readLedger()))throw unavailable();Files.delete(state);force(directory);phase.check();ledger=null;
    }
    private void publishUnchecked(VerificationLedger value,CompleteBudget.Phase phase){try{publish(ledger,value,phase);}catch(IOException failure){throw unavailable();}}
    private void publish(VerificationLedger expected,VerificationLedger value,CompleteBudget.Phase phase)throws IOException{
        phase.check();guard();if(!Objects.equals(expected,readLedger()))throw unavailable();discardNext();writeNew(next,value.encode());phase.check();Files.move(next,state,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);force(directory);ledger=value;phase.check();
    }
    private VerificationLedger readLedger()throws IOException{if(!Files.exists(state,LinkOption.NOFOLLOW_LINKS))return null;privateFile(state,2048);try{return VerificationLedger.decode(trusted.readBounded(state,2048,true));}catch(RuntimeException invalid){throw unavailable();}}
    private void discardNext()throws IOException{if(Files.exists(next,LinkOption.NOFOLLOW_LINKS)){privateFile(next,2048);Files.delete(next);force(directory);}}
    private void guard()throws IOException{
        if(closing||active!=null&&active.closeRequested||!processLock.isValid()||closed||!rootKey.equals(key(root))||!lockKey.equals(key(lockPath))||!directoryKey.equals(key(directory))||!markerKey.equals(key(marker)))throw unavailable();validateRoot();privateFile(lockPath,0);privateDirectory(directory);privateFile(marker,MARKER.length);
        if(!MessageDigest.isEqual(trusted.readBounded(marker,MARKER.length,true),MARKER))throw unavailable();
        try(var entries=Files.list(directory)){var names=entries.limit(5).map(p->p.getFileName().toString()).toList();if(names.size()>3||!Set.of(".owner","state.json","state.next").containsAll(names))throw unavailable();}
    }
    private void validateRoot()throws IOException{privateDirectory(root);byte[] value=trusted.readBounded(root.resolve(".spool-authority"),64,true);if(!MessageDigest.isEqual(value,"dkp-private-spool-v1\n".getBytes(StandardCharsets.US_ASCII)))throw unavailable();}
    private void validatePayloads(VerificationLedger value)throws IOException{
        Set<Path> allowed=new HashSet<>();if(value!=null){allowed.add(payload(value,true));if(value.reservationId()!=null)allowed.add(payload(value,false));}
        try(var entries=Files.list(root)){var all=entries.limit(4097).toList();if(all.size()>4096)throw unavailable();for(Path file:all)if(file.getFileName().toString().startsWith("dkp-verification-")){
            if(!allowed.contains(file))throw unavailable();privateFile(file,value.identity().declaredSize());
            boolean source=file.equals(payload(value,true));State state=source?value.source():value.scratch();if(state==State.RESERVED||state==State.ABSENT)throw unavailable();
        }}
    }
    private void refuseLegacy()throws IOException{try(var entries=Files.list(root)){var names=entries.limit(4097).map(p->p.getFileName().toString()).toList();if(names.size()>4096||names.stream().anyMatch(n->n.startsWith("dkp-bytes-")&&n.endsWith(".tmp")||n.startsWith("verified-copy-")&&n.endsWith(".bin")||n.startsWith("dkp-verification-")))throw unavailable();}}
    private Path payload(VerificationLedger value,boolean source){return root.resolve("dkp-verification-"+value.operationId()+(source?"-source.bin":"-scratch-"+Objects.requireNonNull(value.reservationId())+".bin"));}
    private void privateDirectory(Path path)throws IOException{attributes(path,0040000,0700,Long.MAX_VALUE);}
    private void privateFile(Path path,long maximum)throws IOException{attributes(path,0100000,0600,maximum);}
    private void attributes(Path path,int type,int permissions,long maximum)throws IOException{
        var attributes=Files.readAttributes(path,"unix:uid,mode,nlink,size",LinkOption.NOFOLLOW_LINKS);int mode=((Number)attributes.get("mode")).intValue();
        if(((Number)attributes.get("uid")).longValue()!=uid||(mode&0170000)!=type||(mode&07777)!=permissions||type==0100000&&((Number)attributes.get("nlink")).intValue()!=1||((Number)attributes.get("size")).longValue()>maximum)throw unavailable();
    }
    private static java.nio.file.attribute.FileAttribute<?> mode(String value){return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(value));}
    private static Path canonical(Path root){if(root==null||!root.isAbsolute()||!root.equals(root.normalize()))throw unavailable();return root;}
    private static Object key(Path path)throws IOException{Object key=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();if(key==null)throw unavailable();return key;}
    private static void writeNew(Path path,byte[] value)throws IOException{try(var file=FileChannel.open(path,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),mode("rw-------"))){ByteBuffer bytes=ByteBuffer.wrap(value);while(bytes.hasRemaining())file.write(bytes);file.force(true);}}
    private static void force(Path directory)throws IOException{try(var file=FileChannel.open(directory,StandardOpenOption.READ)){file.force(true);}}
    private GateLease enter(){if(!gate.tryLock())throw unavailable();return new GateLease();}
    private final class GateLease implements AutoCloseable {public void close(){try{if(gate.getHoldCount()==1&&active!=null&&active.closeRequested)active.settle();}finally{gate.unlock();}}}
    private void live(){if(closing||closed||!processLock.isValid())throw unavailable();}
    private static RuntimeException safe(Exception failure){return failure instanceof DmFailure dm?dm:unavailable();}
    private static IllegalStateException unsafe(){return new IllegalStateException("Unavailable verification ownership");}
    private static DmFailure unavailable(){return new DmFailure(503,"UNAVAILABLE");}
}
