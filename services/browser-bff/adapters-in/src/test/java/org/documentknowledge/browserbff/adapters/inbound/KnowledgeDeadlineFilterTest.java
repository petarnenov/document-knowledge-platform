package org.documentknowledge.browserbff.adapters.inbound;
import io.micronaut.http.*;
import java.util.Map;
import org.documentknowledge.browserbff.application.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
class KnowledgeDeadlineFilterTest {
 private static final KnowledgeWire wire=new KnowledgeWire(value->{try{return java.net.URI.create(value).isAbsolute();}catch(IllegalArgumentException invalid){return false;}});
 @Test void frameworkFailuresAreFiniteClosedKbErrorsWithoutChangingLegacyFilters(){
  for(int code:new int[]{400,401,403,404,405,406,413,415,429,500,503}){
   var response=Mono.from(new KnowledgeDeadlineFilter((incoming,deadline)->()->{},wire).doFilter(HttpRequest.GET("/api/v1/kb/collections"),request->Mono.just(HttpResponse.status(HttpStatus.valueOf(code)).body(Map.of("exception","sensitive provider diagnostic"))))).block();
   assertNotNull(response);assertTrue(wire.validate("Error",response.body()));assertEquals(code>=500?503:code==405?404:code,response.code());assertEquals("no-store",response.header("Cache-Control"));assertFalse(response.body().toString().contains("diagnostic"));
  }
 }
 @Test void legitimateCurrentAuthorityConflictRetainsItsSpecificCode(){var response=Mono.from(new KnowledgeDeadlineFilter((incoming,deadline)->()->{},wire).doFilter(HttpRequest.GET("/api/v1/kb/collections"),request->Mono.just(HttpResponse.status(HttpStatus.CONFLICT).body(Map.of("code","STALE_AUTHORITY"))))).block();assertNotNull(response);assertEquals(Map.of("code","STALE_AUTHORITY"),response.body());}
 @Test void errorAndCancellationInvalidateOwnerBeforeAnyLateDispatch(){var request=HttpRequest.GET("/api/v1/kb/collections");var response=Mono.from(new KnowledgeDeadlineFilter((incoming,deadline)->()->{},wire).doFilter(request,ignored->Mono.error(new IllegalStateException("private failure")))).block();assertNotNull(response);assertEquals(503,response.code());var owner=request.getAttribute(KnowledgeDeadlineFilter.ATTRIBUTE,KnowledgeDeadline.class).orElseThrow();assertThrows(BffFailure.class,owner::check);var cancelled=HttpRequest.GET("/api/v1/kb/collections");var subscription=Mono.from(new KnowledgeDeadlineFilter((incoming,deadline)->()->{},wire).doFilter(cancelled,ignored->Mono.never())).subscribe();subscription.dispose();assertThrows(BffFailure.class,cancelled.getAttribute(KnowledgeDeadlineFilter.ATTRIBUTE,KnowledgeDeadline.class).orElseThrow()::check);}
}
