package org.documentknowledge.browserbff.application;
import java.io.InputStream;
import org.documentknowledge.browserbff.domain.BrowserIdentity;
/** Technical stream boundary receives verified session context, never browser-selected headers or destinations. */
public interface UploadGateway {
 DocumentGateway.Result transfer(String path,String key,InputStream body,long length,BrowserIdentity identity,String tenant,Runnable beforeFirstByte);
 StreamResult download(String path,String range,BrowserIdentity identity,String tenant);
 record StreamResult(int status,String mimeType,String filename,long length,String contentRange,InputStream body) implements AutoCloseable {
  @Override public void close(){try{body.close();}catch(java.io.IOException ignored){}}
 }
}
