package org.documentknowledge.documentmanagement.adapters.inbound;
import io.micronaut.http.*;
import io.micronaut.core.order.Ordered;
import org.documentknowledge.documentmanagement.application.sharing.SharingDeadline;
import reactor.core.publisher.Mono;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SharingRequestDeadlineFilterTest {
 @Test void ownershipPrecedesChainAndResponseKeepsOriginalStatus(){var filter=new SharingRequestDeadlineFilter();assertEquals(Ordered.HIGHEST_PRECEDENCE,filter.getOrder());var request=HttpRequest.POST("/api/v1/documents/knowledge-share-eligibility","");var response=Mono.from(filter.doFilter(request,r->{assertTrue(r.getAttribute(SharingRequestDeadlineFilter.ATTRIBUTE,SharingDeadline.class).isPresent());return Mono.just(HttpResponse.ok("actual"));})).block(Duration.ofSeconds(2));assertEquals(200,response.code());assertEquals("actual",response.body());}
 @Test void neverCompletingAuthenticationIsBoundedAndOwnershipCannotBeRenewed(){var request=HttpRequest.POST("/api/v1/documents/knowledge-share-eligibility","");long started=System.nanoTime();var response=Mono.from(new SharingRequestDeadlineFilter().doFilter(request,r->Mono.never())).block(Duration.ofSeconds(2));assertEquals(503,response.code());assertEquals("no-store",response.header("Cache-Control"));assertTrue(Duration.ofNanos(System.nanoTime()-started).toMillis()<1900);assertThrows(org.documentknowledge.documentmanagement.application.DmFailure.class,()->request.getAttribute(SharingRequestDeadlineFilter.ATTRIBUTE,SharingDeadline.class).orElseThrow().check());}
 @Test void cancellationAndUnexpectedFailureFailClosed(){var request=HttpRequest.POST("/api/v1/documents/knowledge-share-eligibility","");var response=Mono.from(new SharingRequestDeadlineFilter().doFilter(request,r->Mono.error(new IllegalStateException("private")))).block();assertEquals(503,response.code());var pending=HttpRequest.POST("/api/v1/documents/knowledge-share-eligibility","");var subscription=Mono.from(new SharingRequestDeadlineFilter().doFilter(pending,r->Mono.never())).subscribe();subscription.dispose();assertThrows(org.documentknowledge.documentmanagement.application.DmFailure.class,()->pending.getAttribute(SharingRequestDeadlineFilter.ATTRIBUTE,SharingDeadline.class).orElseThrow().check());}
}
