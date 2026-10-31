package org.documentknowledge.browserbff.application;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class UploadWireTest {
 static final String ID="11111111-1111-4111-8111-111111111111",BASE="/api/v1/documents",SLOT=BASE+"/uploads/"+ID;
 static Map<String,Object> initialize(){return Map.of("documentId",ID,"expectedRevision",0,"format","PDF","sizeBytes",4);}
 @Test void onlyFixedRoutesAndClosedBoundedMetadataCanReachTheGateway(){
  for(String method:List.of("GET","POST","PUT","PATCH","DELETE")){assertEquals(method.equals("POST"),UploadWire.jsonRoute(method,BASE+"/uploads"));assertEquals(method.equals("POST"),UploadWire.jsonRoute(method,SLOT+"/complete"));assertEquals(method.equals("GET"),UploadWire.jsonRoute(method,SLOT+"/status"));assertEquals(method.equals("GET"),UploadWire.jsonRoute(method,BASE+"/"+ID+"/upload-receipts/"+ID+"/version"));}
  for(String path:List.of(SLOT+"/unknown",BASE+"/uploads/not-uuid/status",BASE+"/not-uuid/upload-receipts/"+ID+"/version",BASE+"/"+ID+"/upload-receipts/no/version",BASE+"/"+ID+"/other/"+ID+"/version",BASE+"/"+ID+"/upload-receipts/"+ID+"/wrong"))assertFalse(UploadWire.jsonRoute("GET",path));
  assertTrue(UploadWire.transferRoute(SLOT+"/content"));assertFalse(UploadWire.transferRoute(SLOT+"/status"));assertTrue(UploadWire.contentRoute(BASE+"/"+ID+"/versions/"+ID+"/content"));assertFalse(UploadWire.contentRoute(BASE+"/"+ID+"/acl/"+ID+"/content"));
  for(String format:List.of("PDF","DOCX","PPTX","XLSX")){var value=new HashMap<>(initialize());value.put("format",format);DocumentWire.request("POST",BASE+"/uploads",Map.of(),value,"key");}
  for(String field:initialize().keySet()){var value=new HashMap<>(initialize());value.put(field,null);assertThrows(BffFailure.class,()->DocumentWire.request("POST",BASE+"/uploads",Map.of(),value,"key"));}
  for(Object size:List.of(-1,0,"4",0.5,9007199254740992L)){var value=new HashMap<>(initialize());value.put("sizeBytes",size);assertThrows(BffFailure.class,()->UploadWire.request("POST",BASE+"/uploads",Map.of(),value,"key"));}
  var large=new HashMap<>(initialize());large.put("sizeBytes",100000001);assertEquals(413,assertThrows(BffFailure.class,()->UploadWire.request("POST",BASE+"/uploads",Map.of(),large,"key")).status());
  for(String key:List.of("","with space","x".repeat(129)))assertThrows(BffFailure.class,()->UploadWire.request("POST",BASE+"/uploads",Map.of(),initialize(),key));
  assertThrows(BffFailure.class,()->UploadWire.request("POST",BASE+"/uploads",Map.of(),null,"key"));assertThrows(BffFailure.class,()->UploadWire.request("POST",BASE+"/uploads",Map.of(),initialize(),null));assertThrows(BffFailure.class,()->UploadWire.request("GET",SLOT+"/status",Map.of("target","evil"),null,null));
  assertDoesNotThrow(()->UploadWire.request("GET",SLOT+"/status",Map.of(),null,null));assertDoesNotThrow(()->UploadWire.request("POST",SLOT+"/complete",Map.of(),Map.of(),"key"));assertThrows(BffFailure.class,()->UploadWire.request("POST",SLOT+"/complete",Map.of(),Map.of("extra",true),"key"));
 }
 @Test void successShapesCannotRevealOrAcceptExtraFactsWrongBindingsOrMalformedStates(){
  var status=new HashMap<String,Object>();status.put("uploadId",ID);status.put("state","VERIFIED");status.put("expiresAt","2026-10-31T20:00:00Z");status.put("failureCode",null);
  var valid=Map.<String,Map<String,Object>>of(BASE+"/uploads",Map.of("uploadId",ID),SLOT+"/content",Map.of("uploadId",ID),SLOT+"/complete",Map.of("uploadId",ID,"receiptId",ID),SLOT+"/status",status,BASE+"/"+ID+"/upload-receipts/"+ID+"/version",Map.of("documentId",ID,"versionId",ID));
  for(var entry:valid.entrySet()){assertTrue(UploadWire.response(entry.getKey(),entry.getValue()),entry.getKey());for(String key:entry.getValue().keySet()){var bad=new HashMap<>(entry.getValue());bad.put(key,"invalid");assertFalse(UploadWire.response(entry.getKey(),bad),key);}var extra=new HashMap<>(entry.getValue());extra.put("private","secret");assertFalse(UploadWire.response(entry.getKey(),extra));assertFalse(UploadWire.response(entry.getKey(),null));}
  for(String state:List.of("INITIALIZED","RECEIVING","QUARANTINED","VERIFYING","VERIFIED","CONSUMED","REJECTED","EXPIRED")){status.put("state",state);assertTrue(UploadWire.response(SLOT+"/status",status));}
  status.put("failureCode","DEPENDENCY_UNAVAILABLE");assertTrue(UploadWire.response(SLOT+"/status",status));status.put("expiresAt",12);assertFalse(UploadWire.response(SLOT+"/status",status));status.put("expiresAt","invalid");assertFalse(UploadWire.response(SLOT+"/status",status));
  assertEquals(201,UploadWire.success("POST",BASE+"/uploads"));assertEquals(200,UploadWire.success("POST",SLOT+"/complete"));assertEquals(10,DocumentWire.ACTIONS.size());assertTrue(DocumentWire.ACTIONS.contains("document.content.review"));
 }
}
