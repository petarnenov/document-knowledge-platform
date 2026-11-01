package org.documentknowledge.documentmanagement.application.upload;
import java.io.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.documentmanagement.domain.Action;
import org.documentknowledge.identity.ResourceAuthorizationPort;
import static org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;

/** Exact immutable source verified before transmission; bounded disk and new-boundary authorization per request. */
public final class ContentService {
 private final DocumentUnitOfWork transactions;private final UploadPolicy policy;private final UploadStoragePort storage;private final Clock clock;private final Path temporary;
 private final Semaphore streams=new Semaphore(2);private final Set<String> actors=ConcurrentHashMap.newKeySet();
 public ContentService(DocumentUnitOfWork transactions,ResourceAuthorizationPort grants,UploadStoragePort storage,Clock clock,Path temporary){this.transactions=Objects.requireNonNull(transactions);policy=new UploadPolicy(grants);this.storage=Objects.requireNonNull(storage);this.clock=Objects.requireNonNull(clock);this.temporary=Objects.requireNonNull(temporary);}
 public ContentResponse open(DocumentCaller caller,UUID document,UUID version,String range){
  policy.route(caller,Action.CONTENT_REVIEW,document,true);UploadReceipt receipt=resolve(caller,document,version);
  ByteRange bytes=ByteRange.parse(range,receipt.sizeBytes());String actor=UploadSupport.fingerprint(caller.actor().issuer(),caller.actor().subject());
  if(!streams.tryAcquire())throw new DmFailure(429,"QUOTA_EXCEEDED");if(!actors.add(actor)){streams.release();throw new DmFailure(429,"QUOTA_EXCEEDED");}
  UploadSupport.Spool spool=null;
  try(var source=storage.open(receipt.original())){
   spool=UploadSupport.spool(source,temporary,receipt.sizeBytes(),clock,300);if(!spool.sha256().equals(receipt.sha256()))throw UploadPolicy.unavailable();
   final UploadSupport.Spool owned=spool;resolve(caller,document,version);
   return new ContentResponse(bytes.partial()?206:200,receipt.format().mime(),version+"."+receipt.format().extension(),bytes.length(),bytes.partial()?"bytes "+bytes.start()+"-"+bytes.end()+"/"+receipt.sizeBytes():null,owned,bytes,()->{UploadReceipt current=resolve(caller,document,version);if(!current.equals(receipt))throw UploadPolicy.unavailable();},()->{actors.remove(actor);streams.release();},clock);
  }catch(IOException|RuntimeException failure){try{if(spool!=null)spool.close();}finally{actors.remove(actor);streams.release();}if(failure instanceof DmFailure d)throw d;throw UploadPolicy.unavailable();}
 }
 private UploadReceipt resolve(DocumentCaller caller,UUID document,UUID version){return transactions.execute(r->{var doc=policy.document(r,caller,document,Action.CONTENT_REVIEW,true);var found=doc.versions().stream().filter(v->v.versionId().equals(version)).findFirst().orElseThrow(UploadPolicy::hidden);
  UUID receiptId;try{receiptId=UUID.fromString(found.originalArtifactRef());}catch(IllegalArgumentException legacy){throw UploadPolicy.unavailable();}
  var u=r.uploads();var receipt=u.receipt(caller.tenant(),receiptId).orElseThrow(UploadPolicy::hidden);var consumption=u.consumption(caller.tenant(),receiptId).orElseThrow(UploadPolicy::hidden);
  if(!receipt.tenant().equals(caller.tenant())||!receipt.documentId().equals(document)||!consumption.documentId().equals(document)||!consumption.versionId().equals(version)||!found.sha256().equals(receipt.sha256())||!found.mimeType().equals(receipt.format().mime()))throw UploadPolicy.hidden();
  var evidence=u.evidence(caller.tenant(),receipt.evidenceId()).orElseThrow(UploadPolicy::unavailable);if(!evidence.uploadId().equals(receipt.uploadId())||!evidence.inspection().sha256().equals(receipt.sha256())||evidence.inspection().sizeBytes()!=receipt.sizeBytes())throw UploadPolicy.unavailable();policy.document(r,caller,document,Action.CONTENT_REVIEW,true);return receipt;
 });}
 public record ByteRange(long start,long end,boolean partial){
  public long length(){return end-start+1;}
  public static ByteRange parse(String value,long size){
   if(value==null)return new ByteRange(0,size-1,false);if(value.length()>128||!value.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)"))throw invalid();
   try {String[] parts=value.substring(6).split("-",-1);long start,end;
    if(parts[0].isEmpty()){long suffix=Long.parseLong(parts[1]);if(suffix<1)throw invalid();start=suffix>=size?0:size-suffix;end=size-1;}
    else {start=Long.parseLong(parts[0]);end=parts[1].isEmpty()?size-1:Math.min(Long.parseLong(parts[1]),size-1);if(start>=size||start>end)throw invalid();}
    return new ByteRange(start,end,true);
   }catch(NumberFormatException bad){throw invalid();}
  }
  private static DmFailure invalid(){return new DmFailure(416,"RANGE_NOT_SATISFIABLE");}
 }
 public static final class ContentResponse implements AutoCloseable {
  private final int status;private final String mimeType,filename,contentRange;private final long length;
  private final UploadSupport.Spool spool;private final ByteRange range;private final Runnable authorize,release;private final Clock clock;private final AtomicBoolean opened=new AtomicBoolean(),closed=new AtomicBoolean();
  private ContentResponse(int status,String mime,String filename,long length,String contentRange,UploadSupport.Spool spool,ByteRange range,Runnable authorize,Runnable release,Clock clock){this.status=status;mimeType=mime;this.filename=filename;this.length=length;this.contentRange=contentRange;this.spool=spool;this.range=range;this.authorize=authorize;this.release=release;this.clock=clock;}
  public int status(){return status;}public String mimeType(){return mimeType;}public String filename(){return filename;}public long length(){return length;}public String contentRange(){return contentRange;}
  public InputStream openStream(){if(closed.get()||!opened.compareAndSet(false,true))throw UploadPolicy.unavailable();
   try {authorize.run();InputStream input=Files.newInputStream(spool.file());input.skipNBytes(range.start());var deadline=clock.instant().plusSeconds(300);
    return new FilterInputStream(input){long remaining=length;
     @Override public int read()throws IOException {byte[] b=new byte[1];return read(b,0,1)==-1?-1:Byte.toUnsignedInt(b[0]);}
     @Override public int read(byte[] b,int offset,int count)throws IOException {if(clock.instant().isAfter(deadline)){close();throw new IOException("Content deadline");}if(count==0)return 0;if(remaining==0){close();return -1;}int n=super.read(b,offset,(int)Math.min(Math.min(count,65536),remaining));if(n<0)throw new IOException("Truncated verified content");remaining-=n;if(remaining==0)close();return n;}
     @Override public void close()throws IOException {try{super.close();}finally{ContentResponse.this.close();}}
    };
   }catch(IOException|RuntimeException failure){close();if(failure instanceof DmFailure d)throw d;throw UploadPolicy.unavailable();}
  }
  @Override public void close(){if(closed.compareAndSet(false,true)){try{spool.close();}finally{release.run();}}}
 }
}
