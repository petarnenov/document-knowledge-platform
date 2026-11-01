package org.documentknowledge.documentmanagement.adapters.inbound;
import io.micronaut.http.*;
import io.micronaut.security.authentication.Authentication;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.documentmanagement.application.sharing.*;
import org.documentknowledge.documentmanagement.application.sharing.SharingModels.*;
import org.documentknowledge.documentmanagement.domain.*;
import org.documentknowledge.identity.*;
import org.documentknowledge.security.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Boundary-unit claims are already verified; actual JWT/PG behavior is separately exercised over HTTP. */
class DocumentShareControllerTest {
    static final UUID ID=UUID.fromString("00000000-0000-0000-0000-000000000001");
    final Actor actor=new Actor("https://issuer.example","user");
    final Authentication authentication=Authentication.build("user",Map.of("iss",actor.issuer(),"sub",actor.subject(),"dkp_tenants",List.of("t")));
    final AtomicInteger calls=new AtomicInteger();
    final JsonMapper json=JsonMapper.builder().build();
    DocumentShareController controller(){
        var config=new TrustConfiguration();config.setMode("resource");config.setProfile("standalone");config.setIssuer(actor.issuer());config.setStandaloneIssuer(actor.issuer());config.setAudience("dkp-dm");config.setJwksUrl(actor.issuer()+"/keys");config.setAuthorizedParties(List.of("dkp-bff"));
        var service=new DocumentShareService(new DocumentShareStore(){public<T>T locked(String tenant,List<UUID> ids,SharingDeadline deadline,java.util.function.Function<List<SharingModels.Document>,T> work){calls.incrementAndGet();return work.apply(ids.stream().map(id->new SharingModels.Document(id,tenant,0,"development-restricted",1,Set.of(new AclRule(new Principal(actor.issuer(),actor.subject()),Action.SHARE_TO_COLLECTION,AclRule.Effect.ALLOW)))).toList());}},(a,t,action,resources)->new RequestScopedResourceAuthorityPort.Snapshot(a,t,action,resources,AuthorityDecision.ALLOW,resources.stream().collect(java.util.stream.Collectors.toMap(x->x,x->AuthorityDecision.ALLOW)),"a".repeat(64)),new SharingAdmission());
        return new DocumentShareController(service,new DocumentController(null,new TrustedTenantResolver(config)));
    }
    MutableHttpRequest<?> request(){var request=HttpRequest.POST("/api/v1/documents/knowledge-share-assessments","").header("X-DKP-Tenant","t").contentType(MediaType.APPLICATION_JSON_TYPE);request.setAttribute(SharingRequestDeadlineFilter.ATTRIBUTE,SharingDeadline.request());return request;}
    InputStream bytes(String text){return new ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    String strict(){return "{\"documents\":[{\"documentId\":\""+ID+"\"}]}";}
    void status(int expected,HttpResponse<?> response){assertEquals(expected,response.code());assertEquals("no-store",response.header("Cache-Control"));}
    @Test void validStrictOptionalRevisionAndDistinctEligibilityBytes(){var c=controller();var response=c.assess(request(),authentication,bytes(strict()));status(200,response);assertInstanceOf(byte[].class,response.body());var parsed=json.readTree((byte[])response.body());assertEquals(4,parsed.size());assertEquals(ID.toString(),parsed.get("assessments").get(0).get("documentId").asText());status(200,c.assess(request(),authentication,bytes(strict().replace("}]",",\"expectedDocumentRevision\":0}]"))));var eligibility=c.discover(request(),authentication,bytes("{\"documentIds\":[\""+ID+"\"]}"));status(200,eligibility);assertTrue(json.readTree((byte[])eligibility.body()).has("requestedSetDigest"));assertEquals(3,calls.get());}
    @Test void closedShapeTypeNullRevisionAndCanonicalUuidBeforeService(){var c=controller();for(String body:List.of("{}","null","{\"documents\":[]}","{\"documents\":true}","{\"documents\":[null]}",strict().replace("}]",",\"extra\":true}]"),strict().replace("}]",",\"expectedDocumentRevision\":null}]"),strict().replace(ID.toString(),"FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF"),strict().replace("documentId","actor"),strict()+"{}")){status(400,c.assess(request(),authentication,bytes(body)));}status(400,c.discover(request(),authentication,bytes(strict())));status(400,c.discover(request(),authentication,bytes("{\"documentIds\":[null]}")));assertEquals(0,calls.get());}
    @Test void contentTypeEncodingAcceptQueryHeadersAndByteBounds(){var c=controller();status(415,c.assess(request().contentType(MediaType.TEXT_PLAIN_TYPE),authentication,bytes(strict())));status(415,c.assess(request().header("Content-Encoding","gzip"),authentication,bytes(strict())));status(406,c.assess(request().header("Accept","text/html"),authentication,bytes(strict())));status(200,c.assess(request().header("Accept","*/*"),authentication,bytes(strict())));status(400,c.assess(request().header("Accept","application/json").header("Accept","*/*"),authentication,bytes(strict())));status(400,c.assess(request().uri(java.net.URI.create("/api/v1/documents/knowledge-share-assessments?actor=x")),authentication,bytes(strict())));status(400,c.assess(request().header("X-DKP-Tenant","other"),authentication,bytes(strict())));status(413,c.assess(request(),authentication,bytes(" ".repeat(16385))));status(413,c.assess(request().header("Content-Length","16385"),authentication,bytes(strict())));}
    @Test void safeFailureAndStreamCloseCannotOverrideResponse(){var c=controller();AtomicInteger closes=new AtomicInteger();InputStream stream=new ByteArrayInputStream(strict().getBytes(java.nio.charset.StandardCharsets.UTF_8)){@Override public void close(){if(closes.incrementAndGet()>1)throw new IllegalStateException("not exposed");}};status(200,c.assess(request(),authentication,stream));status(400,c.assess(request(),authentication,null));var request=request();request.removeAttribute(SharingRequestDeadlineFilter.ATTRIBUTE,SharingDeadline.class);status(503,c.assess(request,authentication,bytes(strict())));}
    @Test void explicitUnsupportedMethodsAreFiniteWithoutSql(){var c=controller();for(var response:List.of(c.get(),c.patch(),c.put(),c.delete(),c.options()))status(405,response);assertEquals(0,calls.get());}
}
