package org.documentknowledge.knowledgebase.domain;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.documentknowledge.knowledgebase.domain.KbModels.*;
import org.documentknowledge.knowledgebase.domain.KbModels.Collection;

class KbModelsTest {
    final UUID id=new UUID(0,1), document=new UUID(0,2);
    final Principal actor=new Principal("issuer","subject");
    final Context context=new Context(actor,"tenant");
    final Instant now=Instant.EPOCH;
    AudiencePolicy policy() { return new AudiencePolicy(id,1,Set.of(),now); }
    @Test void revisionOverflowNeverWrapsOrMutatesAndBoundsMatchJavascriptIntegers() {
        assertEquals(0,KbModels.revision(0)); assertEquals(KbModels.MAX_REVISION,KbModels.revision(KbModels.MAX_REVISION)); assertEquals(1,KbModels.next(0));
        KbFailure overflow=assertThrows(KbFailure.class,()->KbModels.next(KbModels.MAX_REVISION)); assertEquals(503,overflow.status()); assertEquals("UNAVAILABLE",overflow.code());
        assertThrows(KbFailure.class,()->KbModels.revision(-1)); assertThrows(KbFailure.class,()->KbModels.revision(KbModels.MAX_REVISION+1));
    }
    @Test void metadataIdentityAndReasonsAreBoundedBeforePersistence() {
        assertThrows(KbFailure.class,()->new Principal("", "s")); assertThrows(KbFailure.class,()->new Principal("i","s".repeat(257)));
        assertThrows(KbFailure.class,()->new Context(actor,"")); assertThrows(KbFailure.class,()->new Metadata("","")); assertThrows(KbFailure.class,()->new Metadata("t","d".repeat(4097)));
        assertThrows(KbFailure.class,()->KbModels.text(null,0,10)); assertEquals("",KbModels.text("",0,10));
        assertThrows(KbFailure.class,()->new Membership(id,"tenant",id,document,0,State.ACTIVE,actor,now,""));
    }
    @Test void explicitAclDenyOverridesAllowAndNeverDerivesFromOwnership() {
        AclRule allow=new AclRule(actor,Action.UPDATE,Effect.ALLOW), deny=new AclRule(actor,Action.UPDATE,Effect.DENY);
        Collection collection=new Collection(id,"tenant",0,new Metadata("Title",""),Status.ACTIVE,0,policy(),Set.of(),actor,now);
        assertFalse(collection.permits(actor,Action.UPDATE));
        collection=new Collection(id,"tenant",0,collection.metadata(),Status.ACTIVE,0,policy(),Set.of(allow),actor,now); assertTrue(collection.permits(actor,Action.UPDATE));
        collection=new Collection(id,"tenant",0,collection.metadata(),Status.ACTIVE,0,policy(),Set.of(allow,deny),actor,now); assertFalse(collection.permits(actor,Action.UPDATE));
        Collection next=collection.change(new Metadata("Next",""),Status.ARCHIVED,policy(),collection.rules(),true);
        assertEquals(1,next.revision()); assertEquals(1,next.membershipRevision()); assertEquals(actor,next.creator()); assertEquals(now,next.createdAt());
        assertEquals(0,collection.revision()); assertEquals(0,collection.membershipRevision()); assertEquals("knowledge.collection.update",Action.UPDATE.wire());
    }
    @Test void selectorShapesPolicyIdentityCapsAndCopiesAreStrict() {
        assertEquals(SelectorKind.TENANT,new Selector(SelectorKind.TENANT,null,"tenant").kind());
        assertThrows(KbFailure.class,()->new Selector(SelectorKind.TENANT,"issuer","tenant")); assertThrows(KbFailure.class,()->new Selector(SelectorKind.FIRM,null,"firm"));
        assertNotNull(new AclChange(Delta.REMOVE_DENY,actor,Action.DELEGATE));
        Set<AudienceRule> rules=new HashSet<>(); rules.add(new AudienceRule(Effect.DENY,new Selector(SelectorKind.RESOURCE_SCOPE,"issuer","scope")));
        AudiencePolicy policy=new AudiencePolicy(id,1,rules,now); rules.clear(); assertEquals(1,policy.rules().size());
        assertThrows(KbFailure.class,()->new AudiencePolicy(id,0,Set.of(),now));
        for(int i=0;i<101;i++) rules.add(new AudienceRule(Effect.ALLOW,new Selector(SelectorKind.PRINCIPAL,"issuer","p"+i)));
        assertThrows(KbFailure.class,()->new AudiencePolicy(id,1,rules,now));
        assertThrows(KbFailure.class,()->new Collection(document,"tenant",0,new Metadata("T",""),Status.ACTIVE,0,policy(),Set.of(),actor,now));
        Set<AclRule> acl=new HashSet<>(); for(int i=0;i<101;i++) acl.add(new AclRule(new Principal("issuer","p"+i),Action.UPDATE,Effect.ALLOW));
        assertThrows(KbFailure.class,()->new Collection(id,"tenant",0,new Metadata("T",""),Status.ACTIVE,0,policy(),acl,actor,now));
    }
    @Test void immutableMembershipRetainsCreatorAndEvidenceRecordsValidateFiniteStatuses() {
        Membership m=new Membership(id,"tenant",id,document,0,State.ACTIVE,actor,now,"original"); Membership changed=m.change(State.INACTIVE,"restricted");
        assertEquals(1,changed.revision()); assertEquals(actor,changed.creator()); assertEquals(now,changed.createdAt()); assertEquals(State.ACTIVE,m.state());
        Scope scope=new Scope("tenant",document,0); assertEquals(0,scope.revision());
        ScopeEntry entry=new ScopeEntry(id,id,0,0,1,State.INACTIVE,Status.ARCHIVED);
        Snapshot snapshot=new Snapshot("a".repeat(64),context,document,0,0,0,List.of(entry),Decision.INCOMPATIBLE,now); assertEquals(1,snapshot.entries().size());
        assertThrows(KbFailure.class,()->new Snapshot("a".repeat(64),context,document,0,0,0,Collections.nCopies(51,entry),Decision.INCOMPATIBLE,now));
        CommandKey key=new CommandKey(context,"update",id.toString(),"key"); Receipt receipt=new Receipt(id,null,1);
        assertEquals(201,new CommandOutcome(key,"a".repeat(64),201,receipt).status()); assertEquals(200,new CommandOutcome(key,"a".repeat(64),200,receipt).status());
        assertThrows(KbFailure.class,()->new CommandOutcome(key,"a".repeat(64),202,receipt));
        assertNotNull(new Fact(id,context,id,1,"update","reason",now)); assertEquals("NOT_FOUND",KbFailure.hidden().code()); assertEquals("STALE_POLICY",KbFailure.stale().code());
    }
}
