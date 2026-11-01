package org.documentknowledge.documentmanagement.application;

import io.micronaut.http.*;
import io.micronaut.http.server.types.files.StreamedFile;
import io.micronaut.security.authentication.Authentication;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.documentknowledge.documentmanagement.adapters.inbound.*;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.documentmanagement.domain.*;
import org.documentknowledge.documentmanagement.domain.upload.*;
import org.documentknowledge.identity.*;
import org.documentknowledge.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual service graph with transactional fixtures; no mocked authorization or command handlers. */
class UploadControllerTest {
 @TempDir Path spool;
 final UploadMemory repository=new UploadMemory();final Clock clock=Clock.systemUTC();
 final Actor actor=new Actor("https://issuer.example","user");
 final Authentication authentication=Authentication.build("user",Map.of("iss",actor.issuer(),"sub",actor.subject()));
 final EnumSet<Action> allowed=EnumSet.allOf(Action.class);final Map<ObjectIdentity,byte[]> objects=new HashMap<>();
 final ResourceAuthorizationPort grants=(a,t,x,r)->allowed.contains(Action.parse(x))?AuthorityDecision.ALLOW:AuthorityDecision.DENY;
 final UploadStoragePort storage=new UploadStoragePort(){
  public ObjectIdentity put(ObjectKey key,Path file,long size,String hash){try{var object=new ObjectIdentity(key,UUID.randomUUID().toString());objects.put(object,Files.readAllBytes(file));return object;}catch(IOException failure){throw new IllegalStateException(failure);}}
  public InputStream open(ObjectIdentity object){return new ByteArrayInputStream(objects.get(object));}
  public ObjectIdentity copy(ObjectIdentity source,ObjectKey target){var object=new ObjectIdentity(target,UUID.randomUUID().toString());objects.put(object,objects.get(source).clone());return object;}
  public List<ObjectIdentity> versions(ObjectKey key){throw new UnsupportedOperationException();}
  public DeletionResult deleteOnce(DeletionFence fence){throw new UnsupportedOperationException();}
 };
 DocumentService documents;UploadController controller;UUID document;
 @BeforeEach void prepare(){
  var delegation=new DelegationPolicyPort(){public AuthorityDecision ceiling(Actor a,String t,Action x){return AuthorityDecision.ALLOW;}public AuthorityDecision targetGrant(Actor a,String t,Action x){return AuthorityDecision.ALLOW;}};
  var cursors=new DocumentCursorPort(){public String encode(PagePosition p,Actor a,String t){return "opaque";}public PagePosition decode(String c,Actor a,String t){throw new UnsupportedOperationException();}};
  documents=new DocumentService(repository,grants,delegation,new ReceiptArtifactAuthority(repository,storage,clock,spool),cursors,clock);
  var resolver=new TrustedTenantResolver(new TrustConfiguration()){@Override public TenantMembership resolve(Map<String,Object> claims){return new TenantMembership(AuthorityDecision.ALLOW,Set.of("tenant-a"));}};
  var boundary=new DocumentController(documents,resolver);
  MalwareScanPort scanner=s->new ScanEvidence(s.sha256(),s.sizeBytes(),"1.5.4","1",clock.instant().minusSeconds(1),"d".repeat(64),clock.instant());
  StructureInspectionPort inspector=s->new InspectionEvidence(s.format(),s.format().mime(),s.sizeBytes(),s.sha256(),1,"1","f".repeat(64),"sha256:"+"a".repeat(64),clock.instant());
  controller=new UploadController(new UploadService(repository,grants,storage,scanner,inspector,clock,spool),new ContentService(repository,grants,storage,clock,spool),boundary);
  document=documents.create(new DocumentCaller(actor,"tenant-a",()->AuthorityDecision.ALLOW),"create",new Metadata("Synthetic"," ")).documentId();
 }
 MutableHttpRequest<?> request(String path,String type){return HttpRequest.POST(path,"").header("X-DKP-Tenant","tenant-a").header("Idempotency-Key","command").header("Content-Type",type);}
 InputStream json(Object value){return new ByteArrayInputStream(JsonMapper.builder().build().writeValueAsBytes(value));}
 Map<?,?> body(HttpResponse<?> response){return (Map<?,?>)response.body();}
 UUID initialize(){var response=controller.initialize(request("/api/v1/documents/uploads","application/json"),authentication,json(Map.of("documentId",document.toString(),"expectedRevision",0,"format","PDF","sizeBytes",4)));assertEquals(201,response.code());assertEquals(Set.of("uploadId"),body(response).keySet());return UUID.fromString((String)body(response).get("uploadId"));}
 @Test void fullWireJourneyPreservesMinimalCommandsAndExactRangeAndRevocation()throws Exception{
  UUID slot=initialize();assertEquals(slot.toString(),body(controller.initialize(request("/api/v1/documents/uploads","application/json"),authentication,json(Map.of("documentId",document.toString(),"expectedRevision",0,"format","PDF","sizeBytes",4)))).get("uploadId"));
  String route="/api/v1/documents/uploads/"+slot;assertEquals(200,controller.transfer(request(route+"/content","application/octet-stream"),authentication,slot.toString(),new ByteArrayInputStream(new byte[]{1,2,3,4})).code());
  var complete=controller.complete(request(route+"/complete","application/json"),authentication,slot.toString(),json(Map.of()));assertEquals(200,complete.code());assertEquals(Set.of("uploadId","receiptId"),body(complete).keySet());String receipt=(String)body(complete).get("receiptId");
  var status=controller.status(request(route+"/status","application/json"),authentication,slot.toString());assertEquals(Set.of("uploadId","state","expiresAt","failureCode"),body(status).keySet());assertNull(body(status).get("failureCode"));
  documents.appendVersion(new DocumentCaller(actor,"tenant-a",()->AuthorityDecision.ALLOW),document,0,"append",receipt);
  var mapping=controller.discover(request("/api/v1/documents/"+document+"/upload-receipts/"+receipt+"/version","application/json"),authentication,document.toString(),receipt);assertEquals(200,mapping.code());assertEquals(Set.of("documentId","versionId"),body(mapping).keySet());String version=(String)body(mapping).get("versionId");
  String content="/api/v1/documents/"+document+"/versions/"+version+"/content";
  var download=controller.download(request(content,"application/json").header("Range","bytes=1-2"),authentication,document.toString(),version);assertEquals(206,download.code());assertEquals("bytes 1-2/4",download.header("Content-Range"));assertEquals("nosniff",download.header("X-Content-Type-Options"));assertTrue(download.header("Content-Disposition").startsWith("attachment;"));
  assertEquals(2,((StreamedFile)download.body()).getLength());
  try(var stream=((StreamedFile)download.body()).getInputStream()){assertArrayEquals(new byte[]{2,3},stream.readAllBytes());}
  var full=controller.download(request(content,"application/json"),authentication,document.toString(),version);assertEquals(200,full.code());assertNull(full.header("Content-Range"));assertEquals(4,((StreamedFile)full.body()).getLength());try(var stream=((StreamedFile)full.body()).getInputStream()){assertArrayEquals(new byte[]{1,2,3,4},stream.readAllBytes());}
  assertEquals(400,controller.download(request(content,"application/json").header("Range","bytes=0-1").header("Range","bytes=2-3"),authentication,document.toString(),version).code());
  assertEquals(416,controller.download(request(content,"application/json").header("Range","bytes=100-200"),authentication,document.toString(),version).code());
  allowed.remove(Action.CONTENT_REVIEW);assertEquals(404,controller.download(request(content,"application/json"),authentication,document.toString(),version).code());assertEquals(404,controller.discover(request(content,"application/json"),authentication,document.toString(),receipt).code());
 }
 @Test void boundedClosedTransportRejectsBeforeReadingBinaryOrMutatingState(){
  UUID slot=initialize();String path="/api/v1/documents/uploads/"+slot+"/content";InputStream unread=new InputStream(){public int read(){throw new AssertionError("Rejected transport must not consume body");}};
  for(var request:List.of(request(path,"text/plain"),request(path,"application/octet-stream").header("Content-Encoding","gzip"),request(path,"application/octet-stream").header("Content-Length","100000001"),request(path,"application/octet-stream").header("Content-Length","0"),request(path,"application/octet-stream").header("Content-Length","1").header("Transfer-Encoding","chunked"))){int result=controller.transfer(request,authentication,slot.toString(),unread).code();assertTrue(Set.of(400,413,415).contains(result));}
  assertEquals(415,controller.transfer(HttpRequest.POST(path,"").header("X-DKP-Tenant","tenant-a"),authentication,slot.toString(),unread).code());
  assertEquals(400,controller.transfer(request(path,"application/octet-stream").header("Content-Length","4").header("Content-Length","5"),authentication,slot.toString(),unread).code());
  assertEquals(400,controller.transfer(request(path,"application/octet-stream").header("Transfer-Encoding","chunked").header("Transfer-Encoding","chunked"),authentication,slot.toString(),unread).code());
  assertEquals(400,controller.complete(request(path,"application/json"),authentication,slot.toString(),json(Map.of("extra",true))).code());
  assertEquals(400,controller.initialize(request(path,"application/json"),authentication,json(Map.of("documentId",document.toString(),"expectedRevision",0,"format","HTML","sizeBytes",4))).code());
  assertEquals(413,controller.initialize(request(path,"application/json"),authentication,new ByteArrayInputStream(new byte[16385])).code());
  assertEquals(400,controller.transfer(request(path,"application/octet-stream"),authentication,slot.toString(),null).code());
  assertEquals(400,controller.status(request(path+"?forged=yes","application/json"),authentication,slot.toString()).code());
  assertEquals(404,controller.status(request(path,"application/json"),authentication,UUID.randomUUID().toString()).code());
 }
}
