package org.documentknowledge.browserbff.application;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.documentknowledge.browserbff.domain.BrowserIdentity;
/** Technical stream boundary receives verified session context, never browser-selected headers or destinations. */
public interface UploadGateway {
 DocumentGateway.Result transfer(String path,String key,InputStream body,long length,BrowserIdentity identity,String tenant,Runnable beforeFirstByte);
 StreamResult download(String path,String range,BrowserIdentity identity,String tenant);
 record StreamResult(int status,String mimeType,String filename,long length,String contentRange,InputStream body,CompletionStage<Void> sourceSettlement) implements AutoCloseable {
  public StreamResult {
   Objects.requireNonNull(body);
   // Never expose the producer's mutable completion authority to a consumer.
   sourceSettlement=Objects.requireNonNull(sourceSettlement).toCompletableFuture().minimalCompletionStage();
  }
  /** Compatibility path: an absent source witness remains unproved, even after close returns. */
  public StreamResult(int status,String mimeType,String filename,long length,String contentRange,InputStream body){this(status,mimeType,filename,length,contentRange,body,new CompletableFuture<>());}
  // This witnesses successful source close plus read exit, not worker or JDK termination.
  @Override public void close(){try{body.close();}catch(java.io.IOException ignored){}}
 }
}
