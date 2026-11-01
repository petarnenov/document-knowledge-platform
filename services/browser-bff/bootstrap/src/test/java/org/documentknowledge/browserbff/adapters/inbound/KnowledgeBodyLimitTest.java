package org.documentknowledge.browserbff.adapters.inbound;

import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.exceptions.BufferLengthExceededException;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.filters.SecurityFilter;
import io.micronaut.session.Session;
import io.micronaut.session.SessionStore;
import io.micronaut.session.http.HttpSessionFilter;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.documentknowledge.browserbff.adapters.inbound.*;
import org.documentknowledge.browserbff.application.*;
import org.documentknowledge.browserbff.domain.BrowserIdentity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The real framework size exception must not become malformed-JSON400 when
 * Future.get wraps a streaming failure in ExecutionException. */
class KnowledgeBodyLimitTest {
 @Test @SuppressWarnings("unchecked") void streamedFrameworkOverflowRemains413AndOrdinaryIoRemains400() throws Exception {
  try(var server=ApplicationContext.run(EmbeddedServer.class,Map.of("micronaut.server.port",-1,"dkp.bff.portal-origin","https://localhost:18443"))){
   var context=server.getApplicationContext();
   Session session=context.getBean(SessionStore.class).newSession();
   var identity=new BrowserIdentity("https://issuer.example","staff-1","Synthetic",List.of("tenant-a"),true,"synthetic-access",Instant.now().plusSeconds(120));
   session.put(SecurityFilter.AUTHENTICATION,Authentication.build("staff-1",Map.of(VerifiedOpenIdMapper.IDENTITY_ATTRIBUTE,identity)));
   session.put("csrfToken",".fixture-csrf");session.put("dkp.selected.tenant","tenant-a");
   var calls=new AtomicInteger();
   KnowledgeGateway gateway=(method,path,query,body,key,actor,tenant,deadline)->{calls.incrementAndGet();throw new AssertionError("Rejected body must not reach KB");};
   var controller=new KnowledgeController(context.getBean(BrowserSessions.class),gateway,context.getBean(KnowledgeWire.class));
   for(int kind=0;kind<7;kind++){
    final int failureKind=kind;
    var request=HttpRequest.POST("/api/v1/kb/collections","").contentType("application/json").header("Origin","https://localhost:18443").header("X-CSRF-TOKEN",".fixture-csrf").header("Idempotency-Key","synthetic-key");
    request.setAttribute(HttpSessionFilter.SESSION_ATTRIBUTE,session);
    request.setAttribute(KnowledgeDeadlineFilter.ATTRIBUTE,new KnowledgeDeadline());
    if(kind>=3)request.header("Content-Length","17000");
    if(kind==4)request.getAttributes().remove(HttpSessionFilter.SESSION_ATTRIBUTE);
    if(kind==5)request.getHeaders().set("X-CSRF-TOKEN","wrong");
    if(kind==6)request.getHeaders().remove("Content-Length");
    var reads=new AtomicInteger();var closes=new AtomicInteger();
    var stream=new InputStream(){public int read() throws IOException {reads.incrementAndGet();if(failureKind==0)throw new BufferLengthExceededException(16384,17000);if(failureKind==1)throw new IOException(new BufferLengthExceededException(16384,17000));if(failureKind==6)throw new io.micronaut.http.exceptions.ContentLengthExceededException(16384,17000);throw new IOException("Synthetic ordinary I/O failure");}public void close(){closes.incrementAndGet();}};
    var response=controller.route1(request,stream);
    int expected=kind==2?400:kind==4?401:kind==5?403:413;
    assertEquals(expected,response.code(),"case="+kind);
    assertEquals(Map.of("code",kind==2?"BAD_REQUEST":kind==4?"UNAUTHENTICATED":kind==5?"FORBIDDEN":"PAYLOAD_TOO_LARGE"),response.body());
    assertEquals(kind>=3&&kind<=5?0:1,reads.get());assertEquals(1,closes.get());assertEquals(0,calls.get());
   }
  }
 }
}
