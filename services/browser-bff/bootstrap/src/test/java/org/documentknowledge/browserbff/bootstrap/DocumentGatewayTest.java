package org.documentknowledge.browserbff.bootstrap;

import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.json.JsonMapper;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.documentknowledge.browserbff.adapters.outbound.FixedDocumentGateway;
import org.documentknowledge.browserbff.application.BffFailure;
import org.documentknowledge.browserbff.application.DocumentWire;
import org.documentknowledge.browserbff.domain.BrowserIdentity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocumentGatewayTest {
    private final java.util.List<AutoCloseable> ownedClients = new java.util.ArrayList<>();
    private <T extends AutoCloseable> T owned(T gateway) { ownedClients.add(gateway); return gateway; }
    @org.junit.jupiter.api.AfterEach void closeOwnedClients() throws Exception {
        for (var client : ownedClients) client.close();
    }

    static final String ID = "11111111-1111-4111-8111-111111111111";
    static final String PATH = "/api/v1/documents/" + ID;
    static final Map<String,Object> UPDATE = Map.of("expectedRevision", 0, "metadata", Map.of("title", "Draft", "description", ""));
    static final Map<String,Object> CREATE = Map.of("metadata", Map.of("title", "Draft", "description", ""), "classification", "development-restricted", "classificationPolicyRevision", 1);
    static Map<String,Object> draft() {
        var m = new java.util.HashMap<String,Object>();
        m.put("documentId",ID);m.put("tenant","tenant-a");m.put("creator",Map.of("issuer","https://issuer.example","subject","someone-else"));m.put("createdAt","2026-10-31T10:00:00Z");m.put("revision",0);m.put("lifecycle","DRAFT");m.put("metadata",Map.of("title","Draft","description",""));m.put("classification","development-restricted");m.put("classificationPolicyRevision",1);m.put("activeVersionId",null);
        var retention=new java.util.HashMap<String,Object>();retention.put("policyRef","development-unresolved");retention.put("policyRevision",1);retention.put("resolution","UNRESOLVED");retention.put("retainUntil",null);retention.put("destructiveEligible",false);m.put("retention",retention);return m;
    }
    @Test void fixedTransportReconstructsCredentialsAndAcceptsOnlyClosedContractResponses() throws Exception {
        try(var context=ApplicationContext.run()) {
            var json=context.getBean(JsonMapper.class);var status=new AtomicInteger(200);var response=new AtomicReference<Map<String,Object>>(draft());var type=new AtomicReference<>("application/json");var calls=new AtomicInteger();
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api/v1/documents", exchange->{calls.incrementAndGet();assertEquals("Bearer synthetic-access",exchange.getRequestHeaders().getFirst("Authorization"));assertEquals("tenant-a",exchange.getRequestHeaders().getFirst("X-DKP-Tenant"));assertNull(exchange.getRequestHeaders().getFirst("Cookie"));assertNull(exchange.getRequestHeaders().getFirst("X-User-Id"));
                if(!exchange.getRequestMethod().equals("GET")){assertEquals("retry-key",exchange.getRequestHeaders().getFirst("Idempotency-Key"));assertTrue(exchange.getRequestBody().readAllBytes().length>0);}
                byte[] bytes=json.writeValueAsBytes(response.get());exchange.getResponseHeaders().set("Content-Type",type.get());exchange.getResponseHeaders().set("Location","http://127.0.0.1:1/steal");exchange.sendResponseHeaders(status.get(),bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}});server.start();
            try {
                var gateway=owned(new FixedDocumentGateway("http://127.0.0.1:"+server.getAddress().getPort(),json));
                var identity=new BrowserIdentity("https://issuer.example","staff-1","Synthetic",List.of("tenant-a"),true,"synthetic-access",Instant.now().plusSeconds(120));
                assertEquals(200,gateway.exchange("GET",PATH,Map.of(),null,null,identity,"tenant-a").status());
                response.set(Map.of("documentId",ID,"revision",1));assertEquals(200,gateway.exchange("PATCH",PATH,Map.of(),UPDATE,"retry-key",identity,"tenant-a").status());
                status.set(201);assertEquals(201,gateway.exchange("POST","/api/v1/documents",Map.of(),CREATE,"retry-key",identity,"tenant-a").status());
                assertEquals(201,gateway.exchange("POST",PATH+"/versions",Map.of(),Map.of("expectedRevision",0,"receiptId","opaque"),"retry-key",identity,"tenant-a").status());
                for(int denied:List.of(400,401,403,404,409,413,415,422,503)){status.set(denied);response.set(Map.of("code","REQUEST_DENIED"));assertEquals(denied,gateway.exchange("GET",PATH,Map.of(),null,null,identity,"tenant-a").status());}
                for(int bad:List.of(202,204,302,500)){status.set(bad);assertEquals(503,assertThrows(BffFailure.class,()->gateway.exchange("GET",PATH,Map.of(),null,null,identity,"tenant-a")).status());}
                status.set(403);for(var bad:List.of(Map.of("code","diagnostics-secret"),Map.of("code","DENIED","token","secret"))){response.set(new java.util.HashMap<>(bad));assertEquals(503,assertThrows(BffFailure.class,()->gateway.exchange("GET",PATH,Map.of(),null,null,identity,"tenant-a")).status());}
                status.set(200);response.set(draft());type.set("text/html");assertEquals(503,assertThrows(BffFailure.class,()->gateway.exchange("GET",PATH,Map.of(),null,null,identity,"tenant-a")).status());type.set("application/json");response.get().put("tenant","tenant-b");assertEquals(503,assertThrows(BffFailure.class,()->gateway.exchange("GET",PATH,Map.of(),null,null,identity,"tenant-a")).status());
                int before=calls.get();for(String bad:List.of("/api/v1/documents/../steal","https://evil.example","/api/v1/documents/"+ID+"/acl?url=evil"))assertThrows(BffFailure.class,()->gateway.exchange("GET",bad,Map.of(),null,null,identity,"tenant-a"));assertEquals(before,calls.get());
                for(String invalid:List.of("", "relative", "ftp://host", "http://user:password@host", "http://host/path", "http://host?query", "http://host#fragment", "http://[", "http://127.0.0.1:1"))assertEquals(503,assertThrows(BffFailure.class,()->owned(new FixedDocumentGateway(invalid,json)).exchange("GET",PATH,Map.of(),null,null,identity,"tenant-a")).status());
            } finally {server.stop(0);}
        }
    }
    @Test void everyInspectionShapeIsClosedBoundedAndDocumentScoped() {
        var principal=Map.of("issuer","https://issuer.example","subject","staff-1");
        var rule=Map.of("principal",principal,"action","document.inspect-draft","effect","ALLOW");
        var hold=Map.of("holdId",ID,"documentId",ID,"revision",0,"active",true,"reason","Synthetic hold","placedBy",principal,"placedAt","2026-10-31T10:00:00Z");
        var page=new java.util.HashMap<String,Object>();page.put("items",List.of(draft()));page.put("nextCursor",null);
        var acl=Map.of("documentId",ID,"revision",0,"rules",List.of(rule));
        for(var entry:Map.of("/api/v1/documents",page,PATH+"/acl",acl,PATH+"/legal-holds",Map.of("items",List.of(hold))).entrySet()) {
            assertTrue(DocumentWire.response("GET",entry.getKey(),entry.getValue(),"tenant-a",20),entry.getKey());
            for(String field:entry.getValue().keySet()){var bad=new java.util.HashMap<String,Object>(entry.getValue());bad.put(field,field.equals("nextCursor") ? "" : "invalid");assertFalse(DocumentWire.response("GET",entry.getKey(),bad,"tenant-a",20),field);}
        }
        for(String field:rule.keySet()){var bad=new java.util.HashMap<String,Object>(rule);bad.put(field,null);assertFalse(DocumentWire.response("GET",PATH+"/acl",Map.of("documentId",ID,"revision",0,"rules",List.of(bad)),"tenant-a",20));}
        for(String field:hold.keySet()){var bad=new java.util.HashMap<String,Object>(hold);bad.put(field,field.equals("reason") ? "" : "invalid");assertFalse(DocumentWire.response("GET",PATH+"/legal-holds",Map.of("items",List.of(bad)),"tenant-a",20),field);}
        assertFalse(DocumentWire.response("GET","/api/v1/documents",Map.of("items",List.of(draft()),"nextCursor",""),"tenant-a",20));
        assertFalse(DocumentWire.response("GET","/api/v1/documents",Map.of("items",List.of(draft()),"nextCursor","opaque"),"tenant-a",0));
        assertFalse(DocumentWire.response("GET",PATH+"/acl",Map.of("documentId",ID,"revision",0,"rules",java.util.Collections.nCopies(101,rule)),"tenant-a",20));
        assertFalse(DocumentWire.response("GET",PATH+"/legal-holds",Map.of("items",java.util.Collections.nCopies(101,hold)),"tenant-a",20));
        for(String field:principal.keySet()){var bad=new java.util.HashMap<String,Object>(principal);bad.put(field,"");var changed=draft();changed.put("creator",bad);assertFalse(DocumentWire.response("GET",PATH,changed,"tenant-a",20));}
        for(String operation:List.of("ADD_ALLOW","REMOVE_ALLOW","ADD_DENY","REMOVE_DENY")){
            var change=Map.of("principal",principal,"action","document.inspect-draft","operation",operation);
            var body=Map.<String,Object>of("expectedRevision",0,"mode","DELEGATE","changes",List.of(change));
            assertDoesNotThrow(()->DocumentWire.request("PATCH",PATH+"/acl",Map.of(),body,"key"));
            for(String field:change.keySet()){var bad=new java.util.HashMap<String,Object>(change);bad.put(field,null);assertThrows(BffFailure.class,()->DocumentWire.request("PATCH",PATH+"/acl",Map.of(),Map.of("expectedRevision",0,"mode","RESTRICT","changes",List.of(bad)),"key"));}
        }
        for(Object invalid:List.of("bad",-1,9007199254740992L,0.5))assertThrows(BffFailure.class,()->DocumentWire.request("PATCH",PATH,Map.of(),Map.of("expectedRevision",invalid,"metadata",Map.of("title","Draft","description","")),"key"));
        assertThrows(BffFailure.class,()->DocumentWire.request("PATCH",PATH+"/acl",Map.of(),Map.of("expectedRevision",0,"mode","OTHER","changes",List.of()),"key"));
        assertThrows(BffFailure.class,()->DocumentWire.request("PATCH",PATH+"/acl",Map.of(),Map.of("expectedRevision",0,"mode","RESTRICT","changes",List.of()),"key"));
        assertThrows(BffFailure.class,()->DocumentWire.request("POST",PATH+"/legal-holds",Map.of(),Map.of("expectedRevision",0,"reason",""),"key"));
        assertThrows(BffFailure.class,()->DocumentWire.request("POST",PATH+"/versions",Map.of(),Map.of("expectedRevision",0,"receiptId",""),"key"));
    }
    @Test void closedValidationRejectsSpoofedFieldsAndEveryMalformedDocumentField() {
        assertTrue(DocumentWire.response("GET",PATH,draft(),"tenant-a",20));
        for(String key:draft().keySet()){var changed=draft();changed.put(key,"invalid");assertFalse(DocumentWire.response("GET",PATH,changed,"tenant-a",20),key);}
        for(String key:List.of("title","description")){var changed=draft();var metadata=new java.util.HashMap<>(Map.of("title","Draft","description",""));metadata.put(key,"\u0000");changed.put("metadata",metadata);assertFalse(DocumentWire.response("GET",PATH,changed,"tenant-a",20));}
        for(String key:List.of("policyRef","policyRevision","resolution","retainUntil","destructiveEligible")){var changed=draft();var retention=new java.util.HashMap<>((Map<String,Object>)changed.get("retention"));retention.put(key,"invalid");changed.put("retention",retention);assertFalse(DocumentWire.response("GET",PATH,changed,"tenant-a",20),key);}
        assertFalse(DocumentWire.response("PATCH",PATH,draft(),"tenant-a",20));
        assertFalse(DocumentWire.response("PATCH",PATH,Map.of("documentId","22222222-2222-4222-8222-222222222222","revision",1),"tenant-a",20));
        for(String bad:List.of("", "0", "51", "1.5", "-1"))assertThrows(BffFailure.class,()->DocumentWire.request("GET","/api/v1/documents",Map.of("limit",bad),null,null));
        for(var query:List.of(Map.of("unknown","x"),Map.of("cursor",""),Map.of("cursor","x".repeat(2049))))assertThrows(BffFailure.class,()->DocumentWire.request("GET","/api/v1/documents",query,null,null));
        assertThrows(BffFailure.class,()->DocumentWire.request("GET",PATH,Map.of("limit","1"),null,null));
        assertDoesNotThrow(()->DocumentWire.request("GET","/api/v1/documents",Map.of("limit","50","cursor","opaque"),null,null));
        assertDoesNotThrow(()->DocumentWire.request("POST","/api/v1/documents",Map.of(),CREATE,"key"));
        for(String field:CREATE.keySet()){var changed=new java.util.HashMap<>(CREATE);changed.put(field,null);assertThrows(BffFailure.class,()->DocumentWire.request("POST","/api/v1/documents",Map.of(),changed,"key"));}
        for(String key:List.of("", "with space", "x".repeat(129)))assertThrows(BffFailure.class,()->DocumentWire.request("PATCH",PATH,Map.of(),UPDATE,key));
        assertThrows(BffFailure.class,()->DocumentWire.request("PATCH",PATH,Map.of(),UPDATE,null));
        assertThrows(BffFailure.class,()->DocumentWire.request("PATCH",PATH,Map.of(),null,"key"));
        assertThrows(BffFailure.class,()->DocumentWire.request("PATCH",PATH,Map.of("url","evil"),UPDATE,"key"));
        for(String suffix:List.of("/acl","/legal-holds","/versions")){String method=suffix.equals("/acl")?"PATCH":"POST";assertThrows(BffFailure.class,()->DocumentWire.request(method,PATH+suffix,Map.of(),Map.of(),"key"));}
        for(String path:List.of("/api/v1/documents/","/api/v1/documents/x","/api/v1/documents/"+ID+"/unknown","/api/v1/documents/"+ID+"/acl/extra"))assertFalse(DocumentWire.route("GET",path));
        assertFalse(DocumentWire.route("DELETE",PATH));assertFalse(DocumentWire.route("PUT",PATH+"/acl"));
    }
}
