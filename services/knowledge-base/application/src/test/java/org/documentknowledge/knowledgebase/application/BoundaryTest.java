package org.documentknowledge.knowledgebase.application;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.documentknowledge.knowledgebase.domain.KbModels.*;
import org.documentknowledge.knowledgebase.application.DocumentShareBatchAuthorityPort.*;
import org.documentknowledge.knowledgebase.application.DocumentShareEligibilityPort.ShareEligibilityResult;

class BoundaryTest {
    final Context context=new Context(new Principal("issuer","subject"),"tenant");
    final UUID id=UUID.fromString("00000000-0000-0000-0000-000000000001");
    final Assessment row=new Assessment(id,1,"development-restricted",1);
    @Test void exactDigestAndBoundedEvidenceRejectUnknownOrIncompleteAuthority() {
        assertEquals(BatchEvidence.hash("[\""+id+"\"]"),BatchEvidence.requestedDigest(List.of(id)));
        List<Expectation> request=List.of(new Expectation(id,1L));
        assertEquals(List.of(row),BatchEvidence.strict(context,request,new ShareBatchAssessment(context,BatchEvidence.ACTION,List.of(row))));
        assertThrows(KbFailure.class,()->BatchEvidence.strict(context,request,null));
        assertThrows(KbFailure.class,()->BatchEvidence.strict(context,request,new ShareBatchAssessment(context,"wrong",List.of(row))));
        assertThrows(KbFailure.class,()->BatchEvidence.strict(context,request,new ShareBatchAssessment(context,BatchEvidence.ACTION,List.of())));
        assertThrows(KbFailure.class,()->BatchEvidence.strict(context,List.of(new Expectation(id,2L)),new ShareBatchAssessment(context,BatchEvidence.ACTION,List.of(row))));
        assertThrows(KbFailure.class,()->BatchEvidence.strict(context,List.of(new Expectation(id,null),new Expectation(id,null)),new ShareBatchAssessment(context,BatchEvidence.ACTION,List.of(row,row))));
        assertThrows(KbFailure.class,()->BatchEvidence.strict(context,List.of(),new ShareBatchAssessment(context,BatchEvidence.ACTION,List.of())));
    }
    @Test void eligibilityMayOmitButNeverInventReorderOrWeakenClassification() {
        var ids=List.of(id); String digest=BatchEvidence.requestedDigest(ids);
        assertEquals(List.of(),BatchEvidence.eligibility(context,ids,new ShareEligibilityResult(context,BatchEvidence.ACTION,digest,List.of())));
        assertThrows(KbFailure.class,()->BatchEvidence.eligibility(context,ids,null));
        assertThrows(KbFailure.class,()->BatchEvidence.eligibility(context,ids,new ShareEligibilityResult(new Context(context.actor(),"foreign"),BatchEvidence.ACTION,digest,List.of())));
        assertThrows(KbFailure.class,()->BatchEvidence.eligibility(context,ids,new ShareEligibilityResult(context,"wrong",digest,List.of())));
        assertThrows(KbFailure.class,()->BatchEvidence.eligibility(context,ids,new ShareEligibilityResult(context,BatchEvidence.ACTION,"0".repeat(64),List.of())));
        List<Assessment> bad=List.of(new Assessment(UUID.randomUUID(),1,"development-restricted",1),new Assessment(id,-1,"development-restricted",1),new Assessment(id,9_007_199_254_740_992L,"development-restricted",1),new Assessment(id,1,"public",1),new Assessment(id,1,"development-restricted",2));
        for(Assessment r:bad) assertThrows(KbFailure.class,()->BatchEvidence.eligibility(context,ids,new ShareEligibilityResult(context,BatchEvidence.ACTION,digest,List.of(r))));
        UUID second=new UUID(0,2); var two=List.of(id,second);
        assertThrows(KbFailure.class,()->BatchEvidence.eligibility(context,two,new ShareEligibilityResult(context,BatchEvidence.ACTION,BatchEvidence.requestedDigest(two),List.of(new Assessment(second,1,"development-restricted",1),row))));
    }
    @Test void deadlinesAreMonotonicParentCancellationAndExactExpiryDominateChildren() {
        AtomicLong ticker=new AtomicLong(); Deadline d=new Deadline(ticker::get,Duration.ofSeconds(8)); Deadline child=d.child(Duration.ofSeconds(2));
        assertEquals(Duration.ofSeconds(2).toNanos(),child.remainingNanos()); ticker.set(Duration.ofSeconds(2).toNanos()); assertThrows(KbFailure.class,child::check); d.check();
        Deadline late=d.child(Duration.ofSeconds(20)); d.cancel(); assertThrows(KbFailure.class,late::check);
        assertTrue(Deadline.request().remainingNanos()>0);
        Deadline thread=new Deadline(ticker::get,Duration.ofSeconds(1)); Thread.currentThread().interrupt(); try { assertThrows(KbFailure.class,thread::check); } finally { Thread.interrupted(); }
    }
    @Test void admissionIsGlobal16Actor1AcrossTenantsNoQueueAndIdempotentRelease() {
        RequestAdmission a=new RequestAdmission(); var first=a.acquire(context.actor()); assertThrows(KbFailure.class,()->a.acquire(context.actor()));
        List<RequestAdmission.Permit> rest=new ArrayList<>(); for(int i=0;i<15;i++) rest.add(a.acquire(new Principal("issuer","s"+i)));
        assertThrows(KbFailure.class,()->a.acquire(new Principal("issuer","overflow"))); first.close(); first.close();
        try(var admitted=a.acquire(context.actor())) { assertNotNull(admitted); }
        rest.forEach(RequestAdmission.Permit::close);
        assertNotNull(new KBUnitOfWork.LockPlan("tenant",List.of(id,id),List.of(id,id),null));
    }
}
