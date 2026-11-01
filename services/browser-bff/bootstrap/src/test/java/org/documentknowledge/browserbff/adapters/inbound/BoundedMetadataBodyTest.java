package org.documentknowledge.browserbff.adapters.inbound;
import static org.junit.jupiter.api.Assertions.*;
import io.micronaut.http.HttpRequest;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.documentknowledge.browserbff.application.BffFailure;
import org.junit.jupiter.api.Test;
class BoundedMetadataBodyTest {
 @Test void exactRawBytesAndStrictJsonAreIndependentOfParsedMapSize() {
  var request=HttpRequest.POST("/", "");
  for(String invalid:new String[]{"[]","null","{} {}","{\"a\":1,\"a\":2}","", "{"})
   assertEquals(400,assertThrows(BffFailure.class,()->BoundedMetadataBody.decode(request,bytes(invalid))).status());
  assertEquals(0,BoundedMetadataBody.decode(request,bytes("{}"+" ".repeat(16382))).size());
  assertEquals(413,assertThrows(BffFailure.class,()->BoundedMetadataBody.decode(request,bytes("{}"+" ".repeat(16383)))).status());
  assertEquals(400,assertThrows(BffFailure.class,()->BoundedMetadataBody.decode(request,null)).status());
 }
 @Test void declaredOversizeNeverReadsAndReadFailureRemainsFinite() {
  var input=new InputStream(){public int read(){fail("Declared oversize must not read");return -1;}};
  assertEquals(413,assertThrows(BffFailure.class,()->BoundedMetadataBody.decode(HttpRequest.POST("/", "").header("Content-Length","16385"),input)).status());
  assertEquals(400,assertThrows(BffFailure.class,()->BoundedMetadataBody.decode(HttpRequest.POST("/", ""),new InputStream(){public int read()throws IOException{throw new IOException("private-test-detail");}})).status());
 }
 @Test void ownedBodyClosesWithoutReplacingAlreadySelectedResponse() {
  var closed=new boolean[1];var input=new ByteArrayInputStream(new byte[0]){public void close()throws IOException{closed[0]=true;throw new IOException("private-test-detail");}};
  BoundedMetadataBody.close(input);assertTrue(closed[0]);BoundedMetadataBody.close(null);BoundedMetadataBody.close(bytes("{}"));
 }
 static InputStream bytes(String value){return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));}
}
