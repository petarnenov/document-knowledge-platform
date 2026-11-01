package org.documentknowledge.knowledgebase.application;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.documentknowledge.knowledgebase.domain.KbModels.*;
import org.documentknowledge.knowledgebase.domain.KbModels.Collection;
import org.documentknowledge.knowledgebase.application.DocumentShareBatchAuthorityPort.*;
import org.documentknowledge.knowledgebase.application.DocumentShareEligibilityPort.ShareEligibilityResult;
import org.documentknowledge.knowledgebase.application.KnowledgeService.*;

class KnowledgeServiceTest {
    final Context actor=new Context(new Principal("https://issuer","owner"),"tenant-a");
    final Principal target=new Principal("https://issuer","target");
    final Instant now=Instant.parse("2026-11-01T00:00:00Z");
    final UUID collectionId=new UUID(0,1), documentId=new UUID(0,2);
    final Memory db=new Memory();
    final Map<String,KnowledgeAuthorityPort.Result> grants=new HashMap<>();
    final Map<Action,KnowledgeAuthorityPort.Result> ceilings=new HashMap<>();
    java.util.function.BiConsumer<Context,Action> authorizationRead=(c,a)->{};
    java.util.function.Consumer<Action> ceilingRead=a->{};
    Runnable relationRead=()->{};
    int eligibilityConflictPhase;
    Runnable beforeEligibilityConflict=()->{};
    final RequestAdmission admission=new RequestAdmission();
    final Set<UUID> hidden=new HashSet<>();
    final Map<UUID,Long> documentRevisions=new HashMap<>();
    final AtomicLong nanos=new AtomicLong();
    final Map<String,MemberCursorPort.Position> memberCursors=new HashMap<>();
    final Map<String,CollectionCursorPort.Position> collectionCursors=new HashMap<>();
    KnowledgeAuthorityPort.Result relation=KnowledgeAuthorityPort.Result.ALLOW;
    Runnable afterFirst=()->{};
    Runnable afterFinal=()->{};
    int calls;
    boolean malformed;
    KnowledgeService service;
    Deadline deadline() { return new Deadline(nanos::get,Duration.ofSeconds(8)); }
    @BeforeEach void setup() {
        db.collections.put(collectionId,collection(collectionId));
        DocumentShareBatchAuthorityPort sharing=(c,expected,d)->{
            assertFalse(db.inTransaction,"HTTP cannot borrow a SQL connection"); d.check();
            if(expected.stream().anyMatch(e->hidden.contains(e.documentId()))) throw KbFailure.hidden();
            var rows=expected.stream().map(e->assessment(e.documentId())).sorted(Comparator.comparing(a->a.documentId().toString())).toList();
            calls++; if(calls%2==1) afterFirst.run(); else afterFinal.run();
            return new ShareBatchAssessment(malformed?new Context(target,c.tenant()):c,BatchEvidence.ACTION,rows);
        };
        DocumentShareEligibilityPort eligibility=(c,docs,d)->{
            assertFalse(db.inTransaction); d.check(); calls++;
            if(calls==eligibilityConflictPhase) { beforeEligibilityConflict.run(); throw KbFailure.stale(); }
            var rows=docs.stream().filter(id->!hidden.contains(id)).map(this::assessment).sorted(Comparator.comparing(a->a.documentId().toString())).toList();
            if(calls%2==1) afterFirst.run(); else afterFinal.run();
            return new ShareEligibilityResult(c,BatchEvidence.ACTION,BatchEvidence.requestedDigest(docs),rows);
        };
        service=new KnowledgeService(db,new KnowledgeAuthorityPort() {
            public Result authorize(Context c,Action a) { authorizationRead.accept(c,a); return grants.getOrDefault(c.actor().subject()+a,Result.ALLOW); }
            public Result delegationCeiling(Context c,Action a) { ceilingRead.accept(a); return ceilings.getOrDefault(a,Result.ALLOW); }
        },(c,s)->{ relationRead.run(); return relation; },sharing,eligibility,()->"A".repeat(43),new MemberCursorPort() {
            public String encode(Position p) { String token="m"+memberCursors.size(); memberCursors.put(token,p); return token; }
            public Position decode(String token) { return Optional.ofNullable(memberCursors.get(token)).orElseThrow(()->new KbFailure(400,"BAD_REQUEST")); }
        },new CollectionCursorPort() {
            public String encode(Position p) { String token="c"+collectionCursors.size(); collectionCursors.put(token,p); return token; }
            public Position decode(String token) { return Optional.ofNullable(collectionCursors.get(token)).orElseThrow(()->new KbFailure(400,"BAD_REQUEST")); }
        },admission,Clock.fixed(now,ZoneOffset.UTC),UUID::randomUUID);
    }
    Assessment assessment(UUID id) { return new Assessment(id,documentRevisions.getOrDefault(id,0L),"development-restricted",1); }
    Collection collection(UUID id) {
        Set<AclRule> rules=new HashSet<>(); for(Action a:Action.values()) rules.add(new AclRule(actor.actor(),a,Effect.ALLOW));
        return new Collection(id,actor.tenant(),0,new Metadata("Collection",""),Status.ACTIVE,0,new AudiencePolicy(id,1,Set.of(),now),rules,actor.actor(),now);
    }
    Membership member(UUID document,State state) {
        Membership value=new Membership(UUID.randomUUID(),actor.tenant(),collectionId,document,0,state,actor.actor(),now,"seed"); db.members.put(value.membershipId(),value); return value;
    }
    CommandOutcome change(Change c,String key) { return service.change(actor,collectionId,c,key,BatchEvidence.hash(c.toString()),deadline()); }
    KbFailure failure(int status,Runnable action) { KbFailure result=assertThrows(KbFailure.class,action::run); assertEquals(status,result.status(),result.code()); return result; }
    @Test void createSeedsOnlyCurrentGrantsAndReplaysWithoutDuplicateFacts() {
        grants.put(actor.actor().subject()+Action.UPDATE,KnowledgeAuthorityPort.Result.DENY);
        CommandOutcome result=service.create(actor,new Metadata("Created",""),"create","a".repeat(64),deadline());
        Collection created=db.collections.get(result.receipt().collectionId()); assertFalse(created.permits(actor.actor(),Action.UPDATE)); assertEquals(Set.of(),created.audience().rules());
        assertEquals(result,service.create(actor,new Metadata("Created",""),"create","a".repeat(64),deadline())); assertEquals(1,db.outcomes.size());
        failure(409,()->service.create(actor,new Metadata("Other",""),"create","b".repeat(64),deadline()));
        grants.put(actor.actor().subject()+Action.CREATE,KnowledgeAuthorityPort.Result.DENY); failure(403,()->service.create(actor,new Metadata("Other",""),"create","b".repeat(64),deadline()));
    }
    @Test void unavailableCreateSeedRollsBackEverything() {
        grants.put(actor.actor().subject()+Action.UPDATE,KnowledgeAuthorityPort.Result.UNAVAILABLE);
        failure(503,()->service.create(actor,new Metadata("Created",""),"key","a".repeat(64),deadline())); assertEquals(1,db.collections.size()); assertTrue(db.outcomes.isEmpty());
    }
    @Test void metadataCasNoopReplayAndCurrentRevocation() {
        EditMetadata edit=new EditMetadata(0,new Metadata("Changed","description")); CommandOutcome result=change(edit,"m"); assertEquals(1,result.receipt().revision());
        assertEquals(result,change(edit,"m")); assertEquals(1,db.audit.size()); failure(409,()->change(new EditMetadata(0,new Metadata("Other","")),"m"));
        grants.put(actor.actor().subject()+Action.UPDATE,KnowledgeAuthorityPort.Result.DENY); failure(403,()->change(edit,"m"));
    }
    @Test void aclRevocationPrecedesStaleRevisionAndBodyConflict() {
        change(new EditMetadata(0,new Metadata("First","")),"m");
        Collection old=db.collections.get(collectionId); Set<AclRule> acl=new HashSet<>(old.rules()); acl.add(new AclRule(actor.actor(),Action.UPDATE,Effect.DENY));
        db.collections.put(collectionId,new Collection(collectionId,old.tenant(),old.revision(),old.metadata(),old.status(),old.membershipRevision(),old.audience(),acl,old.creator(),old.createdAt()));
        failure(404,()->change(new EditMetadata(0,new Metadata("Other","")),"m"));
    }
    @Test void membershipCreateReactivationAndStateNoopsAdvanceHistoryOnce() {
        CommandOutcome added=change(new AddMembership(0,documentId,0,"attach"),"add"); assertEquals(201,added.status()); assertNotNull(added.receipt().membershipId());
        assertEquals(added,change(new AddMembership(0,documentId,0,"attach"),"add")); assertEquals(1,db.members.size());
        change(new EditMembership(1,added.receipt().membershipId(),0,0,State.INACTIVE,"disable"),"off");
        CommandOutcome activated=change(new AddMembership(2,documentId,0,"reactivate"),"again"); assertEquals(added.receipt().membershipId(),activated.receipt().membershipId());
        change(new EditMembership(3,activated.receipt().membershipId(),2,0,State.ACTIVE,"noop"),"noop"); assertEquals(4,db.audit.size()); assertEquals(4,db.histories);
    }
    @Test void missingMembershipAndExpectedRevisionAreGuarded() {
        failure(404,()->change(new EditMembership(0,UUID.randomUUID(),0,0,State.INACTIVE,"x"),"x"));
        Membership m=member(documentId,State.ACTIVE);
        failure(409,()->change(new EditMembership(0,m.membershipId(),2,0,State.INACTIVE,"x"),"x"));
        failure(409,()->change(new AddMembership(0,documentId,1,"x"),"x")); assertTrue(db.audit.isEmpty());
    }
    @Test void archivedCollectionRejectsNewActiveMembershipButCanInactivate() {
        Membership m=member(documentId,State.ACTIVE); change(new EditStatus(0,Status.ARCHIVED,"archive"),"archive");
        failure(422,()->change(new AddMembership(1,UUID.randomUUID(),0,"add"),"a"));
        change(new EditMembership(1,m.membershipId(),0,0,State.INACTIVE,"off"),"off"); assertEquals(State.INACTIVE,db.members.get(m.membershipId()).state());
    }
    @Test void activeReplayAssessesCurrentMembersAndNeverReactivatesOrAddsFacts() {
        member(documentId,State.ACTIVE); EditStatus active=new EditStatus(0,Status.ACTIVE,"active"); var result=change(active,"active");
        assertEquals(result,change(active,"active")); assertEquals(1,db.audit.size());
        hidden.add(documentId); failure(404,()->change(active,"active")); assertEquals(1,db.audit.size());
    }
    @Test void changedCurrentSetCannotUseEarlierAssessment() {
        member(documentId,State.ACTIVE); afterFinal=()->member(UUID.randomUUID(),State.ACTIVE);
        failure(409,()->change(new EditStatus(0,Status.ACTIVE,"active"),"a")); assertTrue(db.audit.isEmpty());
    }
    @Test void lateAllowAndCancellationNeverAdmitFinalSql() {
        member(documentId,State.ACTIVE); afterFinal=()->nanos.set(Duration.ofSeconds(9).toNanos());
        failure(503,()->change(new EditStatus(0,Status.ACTIVE,"a"),"a")); assertEquals(0,db.executions); assertTrue(db.audit.isEmpty());
        nanos.set(0); afterFinal=()->{}; Deadline request=deadline(); afterFirst=request::cancel;
        failure(503,()->service.change(actor,collectionId,new EditStatus(0,Status.ACTIVE,"a"),"a","a".repeat(64),request)); assertEquals(0,db.executions);
    }
    @Test void malformedStrictBindingNeverAdmitsSql() { malformed=true; member(documentId,State.ACTIVE); failure(503,()->change(new EditStatus(0,Status.ACTIVE,"a"),"a")); assertEquals(0,db.executions); }
    @Test void audiencePolicyNoopIsImmutableRevisionAndChecksAllActiveMembers() {
        member(documentId,State.ACTIVE); Set<AudienceRule> rules=Set.of(new AudienceRule(Effect.ALLOW,new Selector(SelectorKind.TENANT,null,actor.tenant())));
        change(new EditAudience(0,rules,"policy"),"p"); assertEquals(2,db.collections.get(collectionId).audience().revision());
        change(new EditAudience(1,rules,"noop"),"p2"); assertEquals(3,db.collections.get(collectionId).audience().revision());
        hidden.add(documentId); failure(404,()->change(new EditAudience(2,Set.of(),"denyall"),"p3")); assertEquals(3,db.collections.get(collectionId).audience().revision());
    }
    @Test void foreignTenantAudienceFailsAtomically() {
        failure(422,()->change(new EditAudience(0,Set.of(new AudienceRule(Effect.ALLOW,new Selector(SelectorKind.TENANT,null,"foreign"))),"bad"),"p")); assertTrue(db.audit.isEmpty());
    }
    @Test void allFourSelectorsAreExecutableAndDenyWins() {
        for(Selector s:List.of(new Selector(SelectorKind.PRINCIPAL,actor.actor().issuer(),actor.actor().subject()),new Selector(SelectorKind.TENANT,null,actor.tenant()),new Selector(SelectorKind.FIRM,actor.actor().issuer(),"firm"),new Selector(SelectorKind.RESOURCE_SCOPE,actor.actor().issuer(),"scope"))) {
            Collection old=db.collections.get(collectionId); var policy=new AudiencePolicy(collectionId,old.audience().revision()+1,Set.of(new AudienceRule(Effect.ALLOW,s)),now);
            db.collections.put(collectionId,new Collection(collectionId,old.tenant(),old.revision(),old.metadata(),old.status(),old.membershipRevision(),policy,old.rules(),old.creator(),old.createdAt()));
            assertEquals(Effect.ALLOW,service.evaluate(actor,collectionId,deadline()).decision());
        }
        relation=KnowledgeAuthorityPort.Result.DENY; assertEquals(Effect.DENY,service.evaluate(actor,collectionId,deadline()).decision());
        relation=KnowledgeAuthorityPort.Result.UNAVAILABLE; failure(503,()->service.evaluate(actor,collectionId,deadline()));
        Collection old=db.collections.get(collectionId); var s=new Selector(SelectorKind.PRINCIPAL,actor.actor().issuer(),actor.actor().subject());
        db.collections.put(collectionId,new Collection(collectionId,old.tenant(),0,old.metadata(),old.status(),0,new AudiencePolicy(collectionId,1,Set.of(new AudienceRule(Effect.ALLOW,s),new AudienceRule(Effect.DENY,s)),now),old.rules(),old.creator(),old.createdAt()));
        assertEquals(Effect.DENY,service.evaluate(actor,collectionId,deadline()).decision());
    }
    @Test void restrictionSelfRevokesAndReplayIsHidden() {
        EditAcl edit=new EditAcl(0,Mode.RESTRICT,List.of(new AclChange(Delta.REMOVE_ALLOW,actor.actor(),Action.RESTRICT)),"self");
        failure(422,()->change(new EditAcl(0,Mode.RESTRICT,List.of(new AclChange(Delta.ADD_ALLOW,target,Action.UPDATE)),"bad"),"bad"));
        assertEquals(1,change(edit,"acl").receipt().revision()); failure(404,()->change(edit,"acl"));
    }
    @Test void delegationPreservesDenyAndUnrelatedRulesForAllFourDeltas() {
        long revision=0;
        for(Delta operation:Delta.values()) change(new EditAcl(revision++,Mode.DELEGATE,List.of(new AclChange(operation,target,Action.UPDATE)),"delegate"),operation.name());
        assertTrue(db.collections.get(collectionId).permits(actor.actor(),Action.UPDATE));
        change(new EditAcl(revision,Mode.DELEGATE,List.of(new AclChange(Delta.ADD_DENY,target,Action.UPDATE)),"deny"),"deny");
        change(new EditAcl(revision+1,Mode.DELEGATE,List.of(new AclChange(Delta.ADD_ALLOW,target,Action.UPDATE)),"allow"),"allow"); assertFalse(db.collections.get(collectionId).permits(target,Action.UPDATE));
    }
    @Test void duplicateDelegationTargetAndCeilingFailuresHaveNoPartialFacts() {
        AclChange delta=new AclChange(Delta.ADD_ALLOW,target,Action.UPDATE);
        failure(422,()->change(new EditAcl(0,Mode.DELEGATE,List.of(delta,delta),"duplicate"),"d"));
        grants.put(target.subject()+Action.UPDATE,KnowledgeAuthorityPort.Result.DENY); failure(422,()->change(new EditAcl(0,Mode.DELEGATE,List.of(delta),"target"),"t"));
        grants.put(target.subject()+Action.UPDATE,KnowledgeAuthorityPort.Result.UNAVAILABLE); failure(503,()->change(new EditAcl(0,Mode.DELEGATE,List.of(delta),"target"),"u"));
        Collection old=db.collections.get(collectionId); Set<AclRule> rules=new HashSet<>(old.rules()); rules.remove(new AclRule(actor.actor(),Action.UPDATE,Effect.ALLOW));
        db.collections.put(collectionId,new Collection(collectionId,old.tenant(),0,old.metadata(),old.status(),0,old.audience(),rules,old.creator(),old.createdAt()));
        ceilings.put(Action.UPDATE,KnowledgeAuthorityPort.Result.DENY);
        failure(403,()->change(new EditAcl(0,Mode.DELEGATE,List.of(delta),"ceiling"),"c")); assertTrue(db.audit.isEmpty());
    }
    @Test void snapshotCompatibilityIsNotAdministratorAudiencePermission() {
        member(documentId,State.ACTIVE); assertEquals(Effect.DENY,service.evaluate(actor,collectionId,deadline()).decision());
        var snapshot=service.snapshot(actor,documentId,0,deadline()); assertEquals(Decision.COMPATIBLE,snapshot.decision());
        assertEquals(Decision.COMPATIBLE,service.validate(actor,documentId,0,snapshot.snapshotToken(),deadline()).decision());
        assertEquals(1,db.snapshots.size()); assertTrue(db.audit.isEmpty());
    }
    @Test void emptySnapshotIsIncompatibleAndLaterAdditionInvalidatesIt() {
        var snapshot=service.snapshot(actor,documentId,0,deadline()); assertEquals(Decision.INCOMPATIBLE,snapshot.decision());
        member(documentId,State.INACTIVE); failure(409,()->service.validate(actor,documentId,0,snapshot.snapshotToken(),deadline()));
        Collection old=db.collections.get(collectionId); Set<AclRule> rules=new HashSet<>(old.rules()); rules.remove(new AclRule(actor.actor(),Action.EVALUATE,Effect.ALLOW));
        db.collections.put(collectionId,new Collection(collectionId,old.tenant(),0,old.metadata(),old.status(),0,old.audience(),rules,old.creator(),old.createdAt()));
        failure(404,()->service.validate(actor,documentId,0,snapshot.snapshotToken(),deadline()));
    }
    @Test void newlyHiddenScopePrecedesDriftAndNoTokenIsStored() {
        afterFinal=()->{ member(documentId,State.INACTIVE); Collection old=db.collections.get(collectionId); db.collections.put(collectionId,new Collection(collectionId,old.tenant(),0,old.metadata(),old.status(),0,old.audience(),Set.of(),old.creator(),old.createdAt())); };
        failure(404,()->service.snapshot(actor,documentId,0,deadline())); assertTrue(db.snapshots.isEmpty());
    }
    @Test void snapshotBindsOwnerDocumentRevisionFenceAndCompleteScope() {
        var snapshot=service.snapshot(actor,documentId,0,deadline());
        failure(404,()->service.validate(actor,UUID.randomUUID(),0,snapshot.snapshotToken(),deadline()));
        documentRevisions.put(documentId,1L); failure(409,()->service.validate(actor,documentId,1,snapshot.snapshotToken(),deadline()));
        documentRevisions.clear(); db.fence++; failure(409,()->service.validate(actor,documentId,0,snapshot.snapshotToken(),deadline()));
        failure(400,()->service.validate(actor,documentId,0,"bad",deadline()));
    }
    @Test void inactiveAndArchivedScopesStillNeedAuthorityButAreIncompatible() {
        member(documentId,State.INACTIVE); assertEquals(Decision.INCOMPATIBLE,service.snapshot(actor,documentId,0,deadline()).decision());
        Collection old=db.collections.get(collectionId); db.collections.put(collectionId,new Collection(collectionId,old.tenant(),0,old.metadata(),Status.ARCHIVED,0,old.audience(),old.rules(),old.creator(),old.createdAt()));
        assertEquals(Decision.INCOMPATIBLE,service.snapshot(actor,documentId,0,deadline()).decision());
        grants.put(actor.actor().subject()+Action.EVALUATE,KnowledgeAuthorityPort.Result.DENY); failure(404,()->service.snapshot(actor,documentId,0,deadline()));
    }
    @Test void full100CandidatesAreFilteredBefore50AndCursorUsesLastVisible() {
        List<Membership> visible=new ArrayList<>();
        for(int i=0;i<100;i++) { UUID id=new UUID(0,100+i); var m=member(id,State.ACTIVE); if(i<49) hidden.add(id); else visible.add(m); }
        var first=service.members(actor,collectionId,null,50,deadline()); assertEquals(50,first.items().size()); assertNotNull(first.nextCursor()); assertEquals(2,calls);
        var second=service.members(actor,collectionId,first.nextCursor(),50,deadline()); assertEquals(1,second.items().size()); assertNull(second.nextCursor());
        Set<UUID> all=new HashSet<>(); first.items().forEach(m->all.add(m.membershipId())); second.items().forEach(m->assertTrue(all.add(m.membershipId()))); assertEquals(51,all.size());
        hidden.add(second.items().getFirst().documentId()); failure(409,()->service.members(actor,collectionId,first.nextCursor(),50,deadline()));
    }
    @Test void allHiddenAndEmptyListsDoNotExposeContinuationOrCallEmptyDm() {
        assertEquals(new Page<Membership>(List.of(),null),service.members(actor,collectionId,null,deadline())); assertEquals(0,calls);
        for(int i=0;i<100;i++) { UUID id=new UUID(0,100+i); member(id,State.ACTIVE); hidden.add(id); }
        assertEquals(new Page<Membership>(List.of(),null),service.members(actor,collectionId,null,deadline())); assertEquals(2,calls);
    }
    @Test void listVisibilityChangeBetweenPhasesOrWrongCursorFailsClosed() {
        member(documentId,State.ACTIVE); afterFirst=()->hidden.add(documentId); failure(409,()->service.members(actor,collectionId,null,deadline()));
        afterFirst=()->{}; hidden.clear(); memberCursors.put("wrong",new MemberCursorPort.Position(new Context(target,actor.tenant()),collectionId,0,0,"x","y",documentId));
        failure(400,()->service.members(actor,collectionId,"wrong",deadline()));
        failure(400,()->service.members(actor,collectionId,"missing",deadline()));
    }
    @Test void collectionListUsesSqlAclFilteringBeforeLimitAndScopedCursor() {
        for(int i=0;i<60;i++) db.collections.put(new UUID(0,100+i),collection(new UUID(0,100+i)));
        var first=service.list(actor,null,50,deadline()); assertEquals(50,first.items().size()); assertNotNull(first.nextCursor());
        assertEquals(11,service.list(actor,first.nextCursor(),50,deadline()).items().size()); assertEquals(collectionId,service.inspect(actor,collectionId,deadline()).collectionId());
        db.fence++; failure(409,()->service.list(actor,first.nextCursor(),50,deadline()));
        collectionCursors.put("wrong",new CollectionCursorPort.Position(new Context(target,actor.tenant()),db.fence,collectionId)); failure(400,()->service.list(actor,"wrong",deadline()));
    }
    @Test void knownRollbackAndUnknownCommittedOutcomeReconcileOnlyOriginalKey() {
        EditMetadata edit=new EditMetadata(0,new Metadata("Changed","")); db.failBeforeCommit=true;
        failure(503,()->change(edit,"key")); assertTrue(db.outcomes.isEmpty()); assertEquals(0,db.collections.get(collectionId).revision());
        db.failBeforeCommit=false; db.unknownCommit=true; failure(503,()->change(edit,"key")); assertEquals(1,db.outcomes.size());
        db.unknownCommit=false; assertEquals(1,change(edit,"key").receipt().revision()); assertEquals(1,db.audit.size());
        failure(409,()->change(new EditMetadata(0,new Metadata("Newbody","")),"key"));
    }
    @Test void structuralKeysAndUnavailableCatalogFailBeforeWrites() {
        failure(400,()->change(new EditMetadata(0,new Metadata("T","")),"bad key"));
        grants.put(actor.actor().subject()+Action.UPDATE,KnowledgeAuthorityPort.Result.UNAVAILABLE); failure(503,()->change(new EditMetadata(0,new Metadata("T","")),"key")); assertEquals(0,db.executions);
    }
    @Test void membershipCapAndAudienceCapAreAtomic() {
        for(int i=0;i<100;i++) member(new UUID(0,100+i),State.INACTIVE);
        failure(422,()->change(new AddMembership(0,documentId,0,"overflow"),"cap")); assertTrue(db.audit.isEmpty());
        Set<AudienceRule> rules=new HashSet<>(); for(int i=0;i<101;i++) rules.add(new AudienceRule(Effect.ALLOW,new Selector(SelectorKind.PRINCIPAL,"https://issuer","s"+i)));
        failure(422,()->change(new EditAudience(0,rules,"overflow"),"cap2"));
    }
    @Test void conflictingStatusBodyStillRechecksOriginalActiveScopeBeforeConflict() {
        member(documentId,State.ACTIVE); change(new EditStatus(0,Status.ACTIVE,"active"),"key");
        hidden.add(documentId);
        failure(404,()->change(new EditStatus(1,Status.ARCHIVED,"differentbody"),"key"));
        hidden.clear(); failure(409,()->change(new EditStatus(1,Status.ARCHIVED,"differentbody"),"key")); assertEquals(1,db.audit.size());
    }
    @Test void conflictingMembershipBodyStillGuardsOriginalDocument() {
        change(new AddMembership(0,documentId,0,"add"),"key"); hidden.add(documentId);
        failure(404,()->change(new AddMembership(1,UUID.randomUUID(),0,"differentbody"),"key")); assertEquals(1,db.members.size());
    }
    @Test void currentLocalDenialPrecedesFinalDmRevisionConflict() {
        member(documentId,State.ACTIVE);
        afterFirst=()->{ documentRevisions.put(documentId,1L); Collection old=db.collections.get(collectionId); Set<AclRule> rules=new HashSet<>(old.rules()); rules.add(new AclRule(actor.actor(),Action.ARCHIVE,Effect.DENY)); db.collections.put(collectionId,new Collection(collectionId,old.tenant(),0,old.metadata(),old.status(),0,old.audience(),rules,old.creator(),old.createdAt())); };
        failure(404,()->change(new EditStatus(0,Status.ACTIVE,"active"),"key")); assertTrue(db.audit.isEmpty());
    }
    @Test void originalReplaySucceedsAfterUnrelatedTenantFenceChange() {
        EditMetadata edit=new EditMetadata(0,new Metadata("Changed","")); var original=change(edit,"key"); db.fence++;
        assertEquals(original,change(edit,"key")); assertEquals(1,db.audit.size());
    }
    @Test void concurrentActorRequestCannotQueueAndOriginalKeyCanReconcileAfterRelease() throws Exception {
        member(documentId,State.ACTIVE);
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        afterFirst=()->{ entered.countDown(); try { assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS)); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); } };
        var result=new java.util.concurrent.atomic.AtomicReference<CommandOutcome>(); var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        EditStatus command=new EditStatus(0,Status.ACTIVE,"active");
        Thread first=Thread.ofPlatform().start(()->{ try { result.set(change(command,"key")); } catch(Throwable failure) { error.set(failure); } });
        assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
        try { failure(503,()->change(command,"key")); assertTrue(db.audit.isEmpty()); }
        finally { release.countDown(); first.join(5000); }
        assertFalse(first.isAlive()); assertNull(error.get()); afterFirst=()->{};
        assertEquals(result.get(),change(command,"key")); assertEquals(1,db.audit.size());
    }
    @Test void ordinaryOperationGrantAndAclNeverSubstituteForExplicitCeilingAcrossAllDeltas() {
        assertEquals(KnowledgeAuthorityPort.Result.UNAVAILABLE,((KnowledgeAuthorityPort)(c,a)->KnowledgeAuthorityPort.Result.ALLOW).delegationCeiling(actor,Action.UPDATE));
        for(Delta operation:Delta.values()) {
            ceilings.put(Action.UPDATE,KnowledgeAuthorityPort.Result.DENY);
            var edit=new EditAcl(0,Mode.DELEGATE,List.of(new AclChange(operation,target,Action.UPDATE)),"explicit-ceiling");
            KbFailure absent=failure(403,()->change(edit,"deny-"+operation)); assertEquals("DELEGATION_CEILING",absent.code());
            ceilings.put(Action.UPDATE,KnowledgeAuthorityPort.Result.UNAVAILABLE);
            failure(503,()->change(edit,"unknown-"+operation));
            assertTrue(db.audit.isEmpty()); assertEquals(0,db.collections.get(collectionId).revision());
        }
    }
    @Test void finalCreateRevocationDuringSeedReadsRollsBackTheWholeCommand() {
        authorizationRead=(c,a)->{ if(a==Action.EVALUATE) grants.put(actor.actor().subject()+Action.CREATE,KnowledgeAuthorityPort.Result.DENY); };
        failure(403,()->service.create(actor,new Metadata("New",""),"key","a".repeat(64),deadline()));
        assertEquals(1,db.collections.size()); assertEquals(0,db.fence); assertTrue(db.outcomes.isEmpty()); assertTrue(db.audit.isEmpty());
    }
    @Test void finalSeedSetOmitsRevokedGrantAndNeverSeedsUnknownAuthority() {
        AtomicInteger evaluations=new AtomicInteger();
        authorizationRead=(c,a)->{ if(a==Action.EVALUATE&&evaluations.incrementAndGet()==1) grants.put(actor.actor().subject()+Action.UPDATE,KnowledgeAuthorityPort.Result.DENY); };
        var created=service.create(actor,new Metadata("New",""),"key","a".repeat(64),deadline());
        assertFalse(db.collections.get(created.receipt().collectionId()).permits(actor.actor(),Action.UPDATE));
        grants.clear(); evaluations.set(0);
        authorizationRead=(c,a)->{ if(a==Action.EVALUATE&&evaluations.incrementAndGet()==1) grants.put(actor.actor().subject()+Action.UPDATE,KnowledgeAuthorityPort.Result.UNAVAILABLE); };
        failure(503,()->service.create(actor,new Metadata("Unknown",""),"unknown","b".repeat(64),deadline()));
        assertEquals(2,db.collections.size()); assertEquals(1,db.outcomes.size()); assertEquals(1,db.audit.size());
    }
    @Test void wholePatchRechecksFirstTargetAfterSecondTargetReadBeforeAnyWrite() {
        Principal second=new Principal("https://issuer","second");
        authorizationRead=(c,a)->{ if(c.actor().equals(second)) grants.put(target.subject()+Action.UPDATE,KnowledgeAuthorityPort.Result.DENY); };
        var edit=new EditAcl(0,Mode.DELEGATE,List.of(new AclChange(Delta.ADD_ALLOW,target,Action.UPDATE),new AclChange(Delta.ADD_ALLOW,second,Action.UPDATE)),"race");
        failure(422,()->change(edit,"race"));
        assertFalse(db.collections.get(collectionId).permits(target,Action.UPDATE)); assertFalse(db.collections.get(collectionId).permits(second,Action.UPDATE));
        assertTrue(db.audit.isEmpty()); assertTrue(db.outcomes.isEmpty()); assertEquals(0,db.fence);
    }
    @Test void wholePatchRechecksCeilingAndOperationAfterAllTargetReads() {
        Principal second=new Principal("https://issuer","second");
        var edit=new EditAcl(0,Mode.DELEGATE,List.of(new AclChange(Delta.ADD_ALLOW,target,Action.UPDATE),new AclChange(Delta.ADD_ALLOW,second,Action.ARCHIVE)),"race");
        authorizationRead=(c,a)->{ if(c.actor().equals(second)) ceilings.put(Action.UPDATE,KnowledgeAuthorityPort.Result.DENY); };
        failure(403,()->change(edit,"ceiling")); assertTrue(db.audit.isEmpty());
        ceilings.clear(); authorizationRead=(c,a)->{ if(c.actor().equals(second)) grants.put(actor.actor().subject()+Action.DELEGATE,KnowledgeAuthorityPort.Result.DENY); };
        failure(404,()->change(edit,"operation")); assertTrue(db.audit.isEmpty()); assertEquals(0,db.collections.get(collectionId).revision());
    }
    @Test void finalAudienceGuardRunsAfterLastRelationReadForCatalogAndAcl() {
        Collection old=db.collections.get(collectionId);
        var policy=new AudiencePolicy(collectionId,1,Set.of(new AudienceRule(Effect.ALLOW,new Selector(SelectorKind.FIRM,actor.actor().issuer(),"firm"))),now);
        db.collections.put(collectionId,new Collection(collectionId,old.tenant(),0,old.metadata(),old.status(),0,policy,old.rules(),old.creator(),old.createdAt()));
        AtomicInteger relationCalls=new AtomicInteger();
        relationRead=()->{ if(relationCalls.incrementAndGet()==2) grants.put(actor.actor().subject()+Action.EVALUATE,KnowledgeAuthorityPort.Result.DENY); };
        failure(404,()->service.evaluate(actor,collectionId,deadline()));
        grants.clear(); relationCalls.set(0); relationRead=()->{ if(relationCalls.incrementAndGet()==2) grants.put(actor.actor().subject()+Action.EVALUATE,KnowledgeAuthorityPort.Result.UNAVAILABLE); };
        failure(503,()->service.evaluate(actor,collectionId,deadline()));
        grants.clear(); relationCalls.set(0); relationRead=()->{ if(relationCalls.incrementAndGet()==2) revokeAcl(Action.EVALUATE); };
        failure(404,()->service.evaluate(actor,collectionId,deadline()));
    }
    void revokeAcl(Action action) {
        Collection old=db.collections.get(collectionId); Set<AclRule> rules=new HashSet<>(old.rules()); rules.add(new AclRule(actor.actor(),action,Effect.DENY));
        db.collections.put(collectionId,new Collection(collectionId,old.tenant(),old.revision(),old.metadata(),old.status(),old.membershipRevision(),old.audience(),rules,old.creator(),old.createdAt()));
    }
    @Test void bothEligibilityConflictPhasesRefreshLocalAuthorityBeforeRevealingDrift() {
        member(documentId,State.ACTIVE);
        for(int phase:List.of(1,2)) {
            calls=0; db.collections.put(collectionId,collection(collectionId)); eligibilityConflictPhase=phase; beforeEligibilityConflict=()->revokeAcl(Action.INSPECT);
            failure(404,()->service.members(actor,collectionId,null,deadline()));
            calls=0; db.collections.put(collectionId,collection(collectionId)); beforeEligibilityConflict=()->grants.put(actor.actor().subject()+Action.INSPECT,KnowledgeAuthorityPort.Result.UNAVAILABLE);
            failure(503,()->service.members(actor,collectionId,null,deadline())); grants.clear();
            calls=0; beforeEligibilityConflict=()->{}; failure(409,()->service.members(actor,collectionId,null,deadline()));
        }
    }
    @Test void occupiedActorRejectsEveryPotentialBatchRouteWithoutOneSqlRead() {
        member(documentId,State.ACTIVE);
        try(var occupied=admission.acquire(actor.actor())) {
            List<Change> requests=List.of(new AddMembership(0,documentId,0,"add"),new EditMembership(0,db.members.values().iterator().next().membershipId(),0,0,State.INACTIVE,"off"),new EditAudience(0,Set.of(),"audience"),new EditStatus(0,Status.ACTIVE,"active"),new EditStatus(0,Status.ARCHIVED,"archive"));
            for(Change request:requests) failure(503,()->change(request,"occupied"));
            failure(503,()->service.members(actor,collectionId,null,deadline()));
            failure(503,()->service.snapshot(actor,documentId,0,deadline()));
            assertEquals(0,db.reads); assertEquals(0,db.executions); assertEquals(0,calls);
            assertEquals(collectionId,service.inspect(actor,collectionId,deadline()).collectionId());
            assertEquals(1,service.list(actor,null,deadline()).items().size());
            assertEquals(1,change(new EditMetadata(0,new Metadata("Nonbatch","")),"metadata").receipt().revision());
            assertEquals(2,change(new EditAcl(1,Mode.RESTRICT,List.of(new AclChange(Delta.ADD_DENY,actor.actor(),Action.UPDATE)),"nonbatch"),"acl").receipt().revision());
        }
    }
    @Test void occupiedGlobalCapacityRejectsWithoutSqlAndEmptyLocalSetsRemainNoDmCalls() {
        List<RequestAdmission.Permit> occupied=new ArrayList<>();
        for(int i=0;i<16;i++) occupied.add(admission.acquire(new Principal("other","actor"+i)));
        try {
            failure(503,()->change(new EditAudience(0,Set.of(),"policy"),"occupied"));
            failure(503,()->service.members(actor,collectionId,null,deadline()));
            assertEquals(0,db.reads); assertEquals(0,db.executions); assertEquals(0,calls);
        } finally { occupied.forEach(RequestAdmission.Permit::close); }
        assertEquals(List.of(),service.members(actor,collectionId,null,deadline()).items());
        change(new EditStatus(0,Status.ACTIVE,"empty"),"empty"); assertEquals(0,calls);
    }
    @Test void allFourDelegationDeltasNeedOnlyDelegateAclExplicitCeilingAndTargetGrant() {
        Collection old=db.collections.get(collectionId);
        db.collections.put(collectionId,new Collection(collectionId,old.tenant(),0,old.metadata(),old.status(),0,old.audience(),Set.of(new AclRule(actor.actor(),Action.DELEGATE,Effect.ALLOW)),old.creator(),old.createdAt()));
        long expected=0;
        for(Delta operation:Delta.values()) {
            var result=change(new EditAcl(expected++,Mode.DELEGATE,List.of(new AclChange(operation,target,Action.UPDATE)),"explicit-ceiling"),operation.name());
            assertEquals(expected,result.receipt().revision());
            assertFalse(db.collections.get(collectionId).permits(actor.actor(),Action.UPDATE));
            assertTrue(db.collections.get(collectionId).permits(actor.actor(),Action.DELEGATE));
        }
        assertEquals(4,db.audit.size());
    }
    @Test void collectionLimitsOneTwentyAndFiftyNeverDropOrDuplicateAuthorizedRows() {
        for(int i=0;i<100;i++) {
            UUID id=new UUID(0,100+i); Collection value=collection(id);
            if(i%4==0) value=new Collection(id,value.tenant(),0,value.metadata(),value.status(),0,value.audience(),Set.of(),value.creator(),value.createdAt());
            db.collections.put(id,value);
        }
        List<UUID> expected=db.collections.values().stream().filter(k->k.permits(actor.actor(),Action.INSPECT)).map(Collection::collectionId).sorted(Comparator.comparing(UUID::toString)).toList();
        assertEquals(20,service.list(actor,null,deadline()).items().size());
        for(int limit:List.of(1,20,50)) {
            List<UUID> observed=new ArrayList<>(); String cursor=null; int pages=0;
            do {
                var page=service.list(actor,cursor,limit,deadline());
                assertFalse(page.items().isEmpty()); assertTrue(page.items().size()<=limit);
                page.items().forEach(k->observed.add(k.collectionId()));
                if(page.nextCursor()!=null) assertEquals(page.items().getLast().collectionId(),collectionCursors.get(page.nextCursor()).lastVisible());
                cursor=page.nextCursor(); assertTrue(++pages<=100);
            } while(cursor!=null);
            assertEquals(expected,observed); assertEquals(observed.size(),new HashSet<>(observed).size());
        }
        assertEquals(0,calls);
    }
    @Test void memberLimitsUseCompleteHundredEligibilityThenCursorAfterReturnedVisibleRow() {
        for(int i=0;i<100;i++) { UUID doc=new UUID(0,100+i); member(doc,i%2==0?State.ACTIVE:State.INACTIVE); if(i<49) hidden.add(doc); }
        List<UUID> expected=db.members.values().stream().filter(m->!hidden.contains(m.documentId())).map(Membership::membershipId).sorted(Comparator.comparing(UUID::toString)).toList();
        assertEquals(20,service.members(actor,collectionId,null,deadline()).items().size());
        for(int limit:List.of(1,20,50)) {
            List<UUID> observed=new ArrayList<>(); String cursor=null; int pages=0;
            do {
                int before=calls; var page=service.members(actor,collectionId,cursor,limit,deadline()); assertEquals(2,calls-before);
                assertFalse(page.items().isEmpty()); assertTrue(page.items().size()<=limit);
                page.items().forEach(m->observed.add(m.membershipId()));
                if(page.nextCursor()!=null) assertEquals(page.items().getLast().membershipId(),memberCursors.get(page.nextCursor()).lastVisible());
                cursor=page.nextCursor(); assertTrue(++pages<=51);
            } while(cursor!=null);
            assertEquals(expected,observed); assertEquals(51,new HashSet<>(observed).size());
        }
    }
    @Test void invalidLimitsFailBeforeSqlOrAuthorityAndAllHiddenPagesHaveNoContinuation() {
        authorizationRead=(c,a)->fail("Invalid structural limit must fail before authority");
        for(int limit:List.of(-1,0,51,Integer.MAX_VALUE)) {
            failure(400,()->service.list(actor,null,limit,deadline()));
            failure(400,()->service.members(actor,collectionId,null,limit,deadline()));
        }
        assertEquals(0,db.reads); assertEquals(0,db.executions); assertEquals(0,calls);
        authorizationRead=(c,a)->{};
        for(int i=0;i<100;i++) { UUID doc=new UUID(0,100+i); member(doc,State.ACTIVE); hidden.add(doc); }
        for(int limit:List.of(1,20,50)) { var page=service.members(actor,collectionId,null,limit,deadline()); assertTrue(page.items().isEmpty()); assertNull(page.nextCursor()); }
    }
    final class Memory implements KBUnitOfWork,CollectionRepository {
        Map<UUID,Collection> collections=new HashMap<>(); Map<UUID,Membership> members=new HashMap<>(); Map<UUID,Scope> scopes=new HashMap<>(); Map<CommandKey,CommandOutcome> outcomes=new HashMap<>(); Map<String,Snapshot> snapshots=new HashMap<>();
        List<Fact> audit=new ArrayList<>(); long fence; int executions,histories,reads; boolean inTransaction,failBeforeCommit,unknownCommit;
        public <T>T read(Deadline d,Function<CollectionRepository,T> f) { reads++; assertFalse(inTransaction); d.check(); inTransaction=true; try { return f.apply(this); } finally { inTransaction=false; } }
        public <T>T execute(LockPlan plan,Deadline d,Function<CollectionRepository,T> f) {
            assertFalse(inTransaction); d.check(); executions++; var oldC=new HashMap<>(collections); var oldM=new HashMap<>(members); var oldS=new HashMap<>(scopes); var oldO=new HashMap<>(outcomes); var oldP=new HashMap<>(snapshots); var oldA=new ArrayList<>(audit); long oldF=fence; int oldH=histories;
            inTransaction=true; T result;
            try { result=f.apply(this); d.check(); if(failBeforeCommit) throw KbFailure.unavailable(); }
            catch(RuntimeException failure) { collections=oldC; members=oldM; scopes=oldS; outcomes=oldO; snapshots=oldP; audit=oldA; fence=oldF; histories=oldH; throw failure; }
            finally { inTransaction=false; }
            if(unknownCommit) throw KbFailure.unavailable(); return result;
        }
        public long tenantFence(String tenant) { return fence; }
        public Optional<Collection> collection(String tenant,UUID id) { return Optional.ofNullable(collections.get(id)).filter(c->c.tenant().equals(tenant)); }
        public List<Collection> visibleCollections(Context c,UUID after,int limit) { return collections.values().stream().filter(k->k.tenant().equals(c.tenant())&&k.permits(c.actor(),Action.INSPECT)&&(after==null||k.collectionId().toString().compareTo(after.toString())>0)).sorted(Comparator.comparing(k->k.collectionId().toString())).limit(limit).toList(); }
        public List<Membership> members(String tenant,UUID id) { return members.values().stream().filter(m->m.tenant().equals(tenant)&&m.collectionId().equals(id)).sorted(Comparator.comparing(m->m.membershipId().toString())).toList(); }
        public List<Membership> memberships(String tenant,UUID document) { return members.values().stream().filter(m->m.tenant().equals(tenant)&&m.documentId().equals(document)).sorted(Comparator.comparing(m->m.collectionId().toString())).toList(); }
        public Scope scope(String tenant,UUID doc) { return scopes.getOrDefault(doc,new Scope(tenant,doc,0)); }
        public Optional<CommandOutcome> command(CommandKey key) { return Optional.ofNullable(outcomes.get(key)); }
        public Optional<Snapshot> snapshot(String hash,Context c,UUID doc) { return Optional.ofNullable(snapshots.get(hash)).filter(s->s.context().equals(c)&&s.documentId().equals(doc)); }
        public void insertCollection(Collection c) { assertNull(collections.putIfAbsent(c.collectionId(),c)); }
        public void saveCollection(Collection c,long expected) { assertEquals(expected,collections.get(c.collectionId()).revision()); collections.put(c.collectionId(),c); }
        public void saveMembership(Membership m,Long expected,Principal current,Instant at) { if(expected!=null) assertEquals(expected.longValue(),members.get(m.membershipId()).revision()); assertEquals(actor.actor(),current); members.put(m.membershipId(),m); histories++; }
        public void saveScope(Scope s,long expected) { assertEquals(expected,scope(s.tenant(),s.documentId()).revision()); scopes.put(s.documentId(),s); }
        public void advanceTenantFence(String tenant,long expected) { assertEquals(expected,fence); fence++; }
        public void saveSnapshot(Snapshot s) { snapshots.put(s.tokenHash(),s); }
        public void record(CommandOutcome o,Fact a,Fact outbox) { assertNull(outcomes.putIfAbsent(o.key(),o)); assertNotEquals(a.eventId(),outbox.eventId()); audit.add(a); }
    }
}
