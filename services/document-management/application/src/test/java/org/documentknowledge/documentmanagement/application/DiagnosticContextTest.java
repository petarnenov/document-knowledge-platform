package org.documentknowledge.documentmanagement.application;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.documentknowledge.identity.Actor;
import org.documentknowledge.identity.AuthorityDecision;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticContextTest {
    @Test void acceptsOnlyValidNonAuthorityCorrelationAndOptionalTrace() {
        var id=UUID.randomUUID();
        assertEquals(id,new DiagnosticContext(id,null,null).correlationId());
        assertEquals("a".repeat(32),new DiagnosticContext(id,"a".repeat(32),null).traceId());
        assertEquals("b".repeat(16),new DiagnosticContext(id,"a".repeat(32),"b".repeat(16)).spanId());
        assertNotEquals(DiagnosticContext.fresh(),DiagnosticContext.fresh());
        assertThrows(NullPointerException.class,()->new DiagnosticContext(null,null,null));
        assertThrows(IllegalArgumentException.class,()->new DiagnosticContext(new UUID(0,0),null,null));
        assertThrows(IllegalArgumentException.class,()->new DiagnosticContext(new UUID(0,1),null,"b".repeat(16)));
        assertEquals(new UUID(1,0),new DiagnosticContext(new UUID(1,0),null,null).correlationId());
        for(String invalid:new String[]{"", "a".repeat(31),"A".repeat(32),"0".repeat(32),"g".repeat(32)})
            assertThrows(IllegalArgumentException.class,()->new DiagnosticContext(id,invalid,null));
        for(String invalid:new String[]{"", "b".repeat(15),"B".repeat(16),"0".repeat(16),"g".repeat(16)})
            assertThrows(IllegalArgumentException.class,()->new DiagnosticContext(id,"a".repeat(32),invalid));
    }
    @Test void callerContextDoesNotChangeIdentityOrMembership() {
        var actor=new Actor("https://issuer.example","staff");var context=DiagnosticContext.fresh();
        var caller=new DocumentCaller(actor,"tenant-a",()->AuthorityDecision.ALLOW,context);
        assertSame(context,caller.diagnostics());assertEquals(actor.issuer(),caller.principal().issuer());
        assertEquals(actor.subject(),caller.principal().subject());assertEquals(AuthorityDecision.ALLOW,caller.membership());
        var legacy=new DocumentCaller(actor,"tenant-a",()->AuthorityDecision.DENY);
        assertNotNull(legacy.diagnostics());assertEquals(caller.principal(),legacy.principal());
        assertEquals(AuthorityDecision.DENY,legacy.membership());
        assertThrows(NullPointerException.class,()->new DocumentCaller(actor,"tenant-a",()->AuthorityDecision.ALLOW,null));
    }
    @Test void compatiblePortsDelegateWithNoInventedAuthorityOrSwallowedFailure() {
        var calls=new AtomicInteger();var context=DiagnosticContext.fresh();
        DocumentUnitOfWork work=new DocumentUnitOfWork(){
            public <T>T execute(Function<DocumentRepository,T> action){calls.incrementAndGet();return action.apply(null);}
        };
        assertEquals("result",work.execute(context,repository->"result"));assertEquals(1,calls.get());
        var original=new IllegalStateException("controlled");
        assertSame(original,assertThrows(IllegalStateException.class,()->work.execute(context,repository->{throw original;})));
        assertThrows(NullPointerException.class,()->work.execute(null,repository->"ignored"));assertEquals(2,calls.get());
        var actor=new Actor("https://issuer.example","staff");var document=UUID.randomUUID();
        var artifact=new VerifiedArtifactAuthorityPort.VerifiedArtifact("digest","mime","reference","producer");
        VerifiedArtifactAuthorityPort port=(a,tenant,id,receipt)->{
            assertSame(actor,a);assertEquals("tenant-a",tenant);assertEquals(document,id);assertEquals("receipt",receipt);return artifact;
        };
        assertSame(artifact,port.verify(context,actor,"tenant-a",document,"receipt"));
        assertThrows(NullPointerException.class,()->port.verify(null,actor,"tenant-a",document,"receipt"));
    }
}
