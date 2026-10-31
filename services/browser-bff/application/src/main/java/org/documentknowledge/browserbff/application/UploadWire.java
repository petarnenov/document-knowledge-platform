package org.documentknowledge.browserbff.application;
import java.time.Instant;
import java.util.*;
/** Fixed BP03 routes and closed bodies. No arbitrary upstream routing or implicit content grant. */
public final class UploadWire {
 public static final Set<String> ERRORS=Set.of("INVALID_REQUEST","UNAUTHENTICATED","TENANT_DENIED","OPERATION_DENIED","NOT_FOUND","REVISION_CONFLICT","IDEMPOTENCY_CONFLICT","UPLOAD_STATE_CONFLICT","RECEIPT_CONSUMED","UPLOAD_EXPIRED","PAYLOAD_TOO_LARGE","UNSUPPORTED_MEDIA_TYPE","SIZE_MISMATCH","INVALID_FILE","MALWARE_DETECTED","STRUCTURE_LIMIT","UNSUPPORTED_CONTENT","RANGE_NOT_SATISFIABLE","QUOTA_EXCEEDED","UNAVAILABLE");
 private UploadWire(){}
 public static boolean jsonRoute(String method,String path){String[] p=path.split("/",-1);if(path.equals("/api/v1/documents/uploads"))return method.equals("POST");if(p.length==7&&path.startsWith("/api/v1/documents/uploads/")&&DocumentWire.uuid(p[5]))return p[6].equals("complete")&&method.equals("POST")||p[6].equals("status")&&method.equals("GET");return p.length==8&&path.startsWith("/api/v1/documents/")&&DocumentWire.uuid(p[4])&&p[5].equals("upload-receipts")&&DocumentWire.uuid(p[6])&&p[7].equals("version")&&method.equals("GET");}
 public static boolean transferRoute(String path){String[] p=path.split("/",-1);return p.length==7&&path.startsWith("/api/v1/documents/uploads/")&&DocumentWire.uuid(p[5])&&p[6].equals("content");}
 public static boolean contentRoute(String path){String[] p=path.split("/",-1);return p.length==8&&path.startsWith("/api/v1/documents/")&&DocumentWire.uuid(p[4])&&p[5].equals("versions")&&DocumentWire.uuid(p[6])&&p[7].equals("content");}
 public static void request(String method,String path,Map<String,String> query,Map<String,Object> body,String key){
  if(!jsonRoute(method,path)||!query.isEmpty())throw new BffFailure(400,"INVALID_REQUEST");
  if(method.equals("GET"))return;if(key==null||!key.matches("[!-~]{1,128}")||body==null)throw new BffFailure(400,"INVALID_REQUEST");
  if(path.endsWith("/complete")){if(!body.isEmpty())throw new BffFailure(400,"INVALID_REQUEST");return;}
  if(!body.keySet().equals(Set.of("documentId","expectedRevision","format","sizeBytes"))||!DocumentWire.uuid(body.get("documentId"))||!revision(body.get("expectedRevision"))||!(body.get("format") instanceof String format)||!Set.of("PDF","DOCX","PPTX","XLSX").contains(format)||!revision(body.get("sizeBytes"))||((Number)body.get("sizeBytes")).longValue()<1)throw new BffFailure(400,"INVALID_REQUEST");
  if(((Number)body.get("sizeBytes")).longValue()>100_000_000)throw new BffFailure(413,"PAYLOAD_TOO_LARGE");
 }
 public static int success(String method,String path){return path.equals("/api/v1/documents/uploads")?201:200;}
 public static boolean response(String path,Map<?,?> body){if(body==null)return false;String[] p=path.split("/",-1);
  if(path.endsWith("/version"))return body.keySet().equals(Set.of("documentId","versionId"))&&p[4].equalsIgnoreCase(String.valueOf(body.get("documentId")))&&DocumentWire.uuid(body.get("versionId"));
  if(path.endsWith("/status")){if(!body.keySet().equals(Set.of("uploadId","state","expiresAt","failureCode"))||!p[5].equalsIgnoreCase(String.valueOf(body.get("uploadId")))||!(body.get("state") instanceof String state)||!Set.of("INITIALIZED","RECEIVING","QUARANTINED","VERIFYING","VERIFIED","CONSUMED","REJECTED","EXPIRED").contains(state))return false;try{if(!(body.get("expiresAt") instanceof String expires))return false;Instant.parse(expires);}catch(java.time.DateTimeException invalid){return false;}return body.get("failureCode")==null||Set.of("INVALID_FILE","MALWARE_DETECTED","STRUCTURE_LIMIT","UNSUPPORTED_CONTENT","DEPENDENCY_UNAVAILABLE","UPLOAD_EXPIRED","TRANSFER_INCOMPLETE").contains(body.get("failureCode"));}
  boolean complete=path.endsWith("/complete");return body.keySet().equals(complete?Set.of("uploadId","receiptId"):Set.of("uploadId"))&&DocumentWire.uuid(body.get("uploadId"))&&(p.length==5||p[5].equalsIgnoreCase(String.valueOf(body.get("uploadId"))))&&(!complete||DocumentWire.uuid(body.get("receiptId")));
 }
 private static boolean revision(Object value){return (value instanceof Integer||value instanceof Long)&&((Number)value).longValue()>=0&&((Number)value).longValue()<=9_007_199_254_740_991L;}
}
