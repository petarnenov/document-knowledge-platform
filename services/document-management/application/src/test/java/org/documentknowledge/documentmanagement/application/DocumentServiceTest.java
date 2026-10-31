package org.documentknowledge.documentmanagement.application;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.documentknowledge.documentmanagement.domain.*;
import org.documentknowledge.identity.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocumentServiceTest {
 static final Actor ACTOR=new Actor("https://issuer.example","staff");
 static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-31T12:00:00.123456789Z"),ZoneOffset.UTC);
 static final Metadata META=new Metadata("Draft title","Untrusted <script> text");
 final Memory store=new Memory();
 final EnumSet<Action> allowed=EnumSet.allOf(Action.class);
 AuthorityDecision authorityOverride;
 AuthorityDecision target=AuthorityDecision.ALLOW,ceiling=AuthorityDecision.ALLOW;
 boolean throwDelegation,throwGrants;
 int grantCalls;
 int revokeAt=Integer.MAX_VALUE;
 VerifiedArtifactAuthorityPort artifact=VerifiedArtifactAuthorityPort.unavailable();
 final DocumentCaller caller=new DocumentCaller(ACTOR,"tenant-a",()->AuthorityDecision.ALLOW);
 DocumentCursorPort cursor=new DocumentCursorPort(){public PagePosition decode(String s,Actor a,String t){if(!s.equals("cursor"))throw new IllegalArgumentException();return null;}public String encode(PagePosition p,Actor a,String t){return "cursor";}};
 ResourceAuthorizationPort grants=(actor,tenant,action,resource)->{grantCalls++;if(throwGrants)throw new IllegalStateException();if(grantCalls>=revokeAt)return AuthorityDecision.DENY;if(authorityOverride!=null)return authorityOverride;return allowed.contains(Action.parse(action))?AuthorityDecision.ALLOW:AuthorityDecision.DENY;};
 DocumentService service(){return new DocumentService(store,grants,new DelegationPolicyPort(){public AuthorityDecision targetGrant(Actor a,String t,Action x){if(throwDelegation)throw new IllegalStateException();return target;}public AuthorityDecision ceiling(Actor a,String t,Action x){return ceiling;}},(a,t,id,r)->artifact.verify(a,t,id,r),cursor,CLOCK);}
 UUID create(){return service().create(caller,"create",META).documentId();}
 void denied(int status,String code,Runnable call){DmFailure f=assertThrows(DmFailure.class,call::run);assertEquals(status,f.status());assertEquals(code,f.code());}
 AclChange change(AclChange.Mode mode,AclChange.Operation operation,Principal who,Action action){return new AclChange(mode,List.of(new AclChange.Entry(operation,who,action)));}
 void replace(UUID id,Set<AclRule> rules){var d=store.docs.get(id);store.docs.put(id,new DocumentAggregate(id,d.tenant(),d.creator(),d.createdAt(),d.revision(),d.metadata(),d.activeVersionId(),rules,d.holds(),d.versions()));}
 Set<AclRule> without(UUID id,Action action){var rules=new HashSet<>(store.docs.get(id).rules());rules.remove(new AclRule(caller.principal(),action,AclRule.Effect.ALLOW));return rules;}
 @Test void realDraftStateAndIdempotentReceiptRemainIndependentOfInspection(){
  var s=service();var receipt=s.create(caller,"create",META);UUID id=receipt.documentId();assertEquals(0,receipt.revision());
  var d=s.inspect(caller,id);assertEquals(META,d.metadata());assertEquals("DRAFT",d.lifecycle());assertEquals("development-restricted",d.classification());assertEquals(1,d.classificationPolicyRevision());assertEquals("development-unresolved",d.retentionPolicyRef());assertEquals(1,d.retentionPolicyRevision());assertEquals("UNRESOLVED",d.retentionResolution());assertNull(d.retainUntil());assertFalse(d.destructiveEligible());assertNull(d.activeVersionId());assertEquals(123456000,d.createdAt().getNano());
  assertEquals(receipt,s.create(caller,"create",META));assertEquals(1,store.audits.size());
  allowed.remove(Action.INSPECT_DRAFT);denied(404,"NOT_FOUND",()->s.inspect(caller,id));
  var updated=s.updateMetadata(caller,id,0,"update",new Metadata("Changed",""));assertEquals(1,updated.revision());assertEquals(updated,s.updateMetadata(caller,id,0,"update",new Metadata("Changed","")));
  assertEquals(receipt,s.create(caller,"create",META));assertEquals(2,store.audits.size());
 }
 @Test void createBootstrapsOnlyExplicitCatalogActions(){
  allowed.clear();allowed.add(Action.CREATE);var s=service();UUID id=s.create(caller,"new",META).documentId();assertTrue(store.docs.get(id).rules().isEmpty());denied(403,"OPERATION_DENIED",()->s.updateMetadata(caller,id,0,"x",META));
  allowed.add(Action.INSPECT_DRAFT);denied(404,"NOT_FOUND",()->s.inspect(caller,id));allowed.remove(Action.CREATE);denied(403,"OPERATION_DENIED",()->s.create(caller,"new",META));
 }
 @Test void catalogAndMembershipErrorsPrecedeLookupAndNeverAllow(){
  var s=service();UUID missing=UUID.randomUUID();
  for(AuthorityDecision decision:List.of(AuthorityDecision.DENY,AuthorityDecision.UNAVAILABLE)){
   var c=new DocumentCaller(ACTOR,"tenant-a",()->decision);denied(decision==AuthorityDecision.DENY?403:503,decision==AuthorityDecision.DENY?"TENANT_DENIED":"UNAVAILABLE",()->s.inspect(c,missing));
  }
  for(var c:List.of(new DocumentCaller(ACTOR,"tenant-a",()->null),new DocumentCaller(ACTOR,"tenant-a",()->{throw new IllegalStateException();})))denied(503,"UNAVAILABLE",()->s.create(c,"k",META));
  authorityOverride=AuthorityDecision.UNAVAILABLE;denied(503,"UNAVAILABLE",()->s.inspect(caller,missing));authorityOverride=AuthorityDecision.DENY;
  denied(404,"NOT_FOUND",()->s.inspectAcl(caller,missing));denied(404,"NOT_FOUND",()->s.inspectHolds(caller,missing));denied(403,"OPERATION_DENIED",()->s.list(caller,20,null));denied(403,"OPERATION_DENIED",()->s.updateMetadata(caller,missing,0,"k",META));assertEquals(0,store.finds);
  throwGrants=true;denied(503,"UNAVAILABLE",()->s.create(caller,"k",META));
 }
 @Test void exactTenantAndPrincipalAndExplicitDenyHideResources(){
  UUID id=create();var s=service();denied(404,"NOT_FOUND",()->s.inspect(caller,UUID.randomUUID()));
  var other=new DocumentCaller(new Actor("https://other.example",ACTOR.subject()),"tenant-a",()->AuthorityDecision.ALLOW);denied(404,"NOT_FOUND",()->s.inspect(other,id));
  var tenantB=new DocumentCaller(ACTOR,"tenant-b",()->AuthorityDecision.ALLOW);denied(404,"NOT_FOUND",()->s.inspect(tenantB,id));
  var rules=new HashSet<>(store.docs.get(id).rules());rules.add(new AclRule(caller.principal(),Action.INSPECT_DRAFT,AclRule.Effect.DENY));replace(id,rules);denied(404,"NOT_FOUND",()->s.inspect(caller,id));
  allowed.remove(Action.INSPECT_DRAFT);allowed.add(Action.READ);denied(404,"NOT_FOUND",()->s.inspect(caller,id));
 }
 @Test void staleAndConflictingKeysAreAuthorizedBeforeConflict(){
  UUID id=create();var s=service();denied(409,"REVISION_CONFLICT",()->s.updateMetadata(caller,id,8,"stale",META));assertEquals(0,store.docs.get(id).revision());
  s.updateMetadata(caller,id,0,"update",new Metadata("Changed",""));denied(409,"IDEMPOTENCY_CONFLICT",()->s.updateMetadata(caller,id,0,"update",META));denied(409,"IDEMPOTENCY_CONFLICT",()->s.create(caller,"create",new Metadata("Different","")));
  replace(id,without(id,Action.UPDATE_METADATA));denied(404,"NOT_FOUND",()->s.updateMetadata(caller,id,0,"update",META));
  for(String key:Arrays.asList(null,"","bad key","x".repeat(129)))denied(400,"INVALID_IDEMPOTENCY_KEY",()->s.create(caller,key,META));
 }
 @Test void selfRevocationCommitsReceiptThenReplayFailsCurrentAcl(){
  UUID id=create();var s=service();var delta=change(AclChange.Mode.RESTRICT,AclChange.Operation.REMOVE_ALLOW,caller.principal(),Action.ACL_RESTRICT);
  var receipt=s.changeAcl(caller,id,0,"restrict",delta);assertEquals(1,receipt.revision());assertFalse(store.docs.get(id).permits(caller.principal(),Action.ACL_RESTRICT));
  denied(404,"NOT_FOUND",()->s.changeAcl(caller,id,0,"restrict",delta));assertEquals(2,store.audits.size());
 }
 @Test void restrictionOnlyReducesOwnAuthorityAndNoopStillAudits(){
  UUID id=create();var s=service();Principal other=new Principal("https://issuer.example","other");
  for(var delta:List.of(change(AclChange.Mode.RESTRICT,AclChange.Operation.REMOVE_ALLOW,other,Action.READ),change(AclChange.Mode.RESTRICT,AclChange.Operation.ADD_ALLOW,caller.principal(),Action.READ),change(AclChange.Mode.RESTRICT,AclChange.Operation.REMOVE_DENY,caller.principal(),Action.READ)))denied(422,"RESTRICT_SCOPE",()->s.changeAcl(caller,id,0,"invalid",delta));
  var absent=change(AclChange.Mode.RESTRICT,AclChange.Operation.REMOVE_ALLOW,caller.principal(),Action.READ);assertEquals(1,s.changeAcl(caller,id,0,"noop",absent).revision());
  var deny=change(AclChange.Mode.RESTRICT,AclChange.Operation.ADD_DENY,caller.principal(),Action.READ);s.changeAcl(caller,id,1,"deny",deny);assertEquals(3,s.changeAcl(caller,id,2,"same-deny",deny).revision());assertEquals(4,store.audits.size());
 }
 @Test void delegateFourOperationsPreserveDenyAndUnrelatedRules(){
  UUID id=create();var s=service();Principal other=new Principal("https://issuer.example","other");Set<AclRule> original=store.docs.get(id).rules();long rev=0;
  for(var operation:List.of(AclChange.Operation.ADD_DENY,AclChange.Operation.ADD_ALLOW))s.changeAcl(caller,id,rev++,"d"+rev,change(AclChange.Mode.DELEGATE,operation,other,Action.INSPECT_DRAFT));
  assertFalse(store.docs.get(id).permits(other,Action.INSPECT_DRAFT));assertTrue(store.docs.get(id).rules().containsAll(original));
  s.changeAcl(caller,id,rev++,"clear-deny",change(AclChange.Mode.DELEGATE,AclChange.Operation.REMOVE_DENY,other,Action.INSPECT_DRAFT));assertTrue(store.docs.get(id).permits(other,Action.INSPECT_DRAFT));
  s.changeAcl(caller,id,rev++,"clear-allow",change(AclChange.Mode.DELEGATE,AclChange.Operation.REMOVE_ALLOW,other,Action.INSPECT_DRAFT));assertFalse(store.docs.get(id).permits(other,Action.INSPECT_DRAFT));
 }
 @Test void delegationChecksTargetCeilingOutageAndDuplicateBeforeTransaction(){
  UUID id=create();var s=service();var entry=new AclChange.Entry(AclChange.Operation.ADD_ALLOW,caller.principal(),Action.READ);var delta=new AclChange(AclChange.Mode.DELEGATE,List.of(entry));
  target=AuthorityDecision.DENY;denied(422,"TARGET_NOT_GRANTABLE",()->s.changeAcl(caller,id,0,"k",delta));target=AuthorityDecision.UNAVAILABLE;denied(503,"UNAVAILABLE",()->s.changeAcl(caller,id,0,"k",delta));target=AuthorityDecision.ALLOW;ceiling=AuthorityDecision.DENY;denied(403,"DELEGATION_CEILING",()->s.changeAcl(caller,id,0,"k",delta));ceiling=null;denied(503,"UNAVAILABLE",()->s.changeAcl(caller,id,0,"k",delta));throwDelegation=true;denied(503,"UNAVAILABLE",()->s.changeAcl(caller,id,0,"k",delta));throwDelegation=false;
  int before=store.executions;var duplicate=new AclChange(AclChange.Mode.DELEGATE,List.of(entry,new AclChange.Entry(AclChange.Operation.REMOVE_ALLOW,entry.principal(),entry.action())));denied(422,"DUPLICATE_ACL_CHANGE",()->s.changeAcl(caller,id,0,"dup",duplicate));assertEquals(before,store.executions);
  assertThrows(DmFailure.class,()->new AclChange(AclChange.Mode.RESTRICT,List.of()));assertThrows(DmFailure.class,()->new AclChange(AclChange.Mode.RESTRICT,Collections.nCopies(101,entry)));
 }
 @Test void resultingAclLimitRollsBackWholeDelta(){
  UUID id=create();var rules=new HashSet<>(store.docs.get(id).rules());for(int i=rules.size();i<100;i++)rules.add(new AclRule(new Principal("https://issuer.example","actor"+i),Action.READ,AclRule.Effect.ALLOW));replace(id,rules);
  var delta=change(AclChange.Mode.DELEGATE,AclChange.Operation.ADD_ALLOW,new Principal("https://issuer.example","overflow"),Action.READ);denied(422,"ACL_RULE_LIMIT",()->service().changeAcl(caller,id,0,"overflow",delta));assertEquals(rules,store.docs.get(id).rules());assertEquals(1,store.audits.size());
 }
 @Test void holdsAreDistinctPrivilegedAndBounded(){
  UUID id=create();var s=service();assertTrue(s.inspectAcl(caller,id).holds().isEmpty());var receipt=s.placeHold(caller,id,0,"hold","Synthetic legal hold");assertEquals(1,receipt.revision());var held=s.inspectHolds(caller,id);assertEquals(1,held.holds().size());assertFalse(held.destructiveEligible());
  allowed.remove(Action.HOLD_INSPECT);denied(404,"NOT_FOUND",()->s.inspectHolds(caller,id));
  var holds=new ArrayList<LegalHold>();for(int i=0;i<100;i++)holds.add(new LegalHold(UUID.randomUUID(),"Reason",caller.principal(),CLOCK.instant()));store.docs.put(id,new DocumentAggregate(id,held.tenant(),held.creator(),held.createdAt(),held.revision(),held.metadata(),held.rules(),holds,held.versions()));denied(422,"HOLD_LIMIT",()->s.placeHold(caller,id,1,"overflow","Reason"));assertThrows(IllegalArgumentException.class,()->s.placeHold(caller,id,1,"blank",""));
 }
 @Test void versionRuntimeUnavailableAndVerifiedFixturePreservesRealActivePointer(){
  UUID id=create();var s=service();denied(503,"UNAVAILABLE",()->s.appendVersion(caller,id,0,"append","receipt"));assertTrue(store.docs.get(id).versions().isEmpty());
  artifact=(a,t,d,r)->null;denied(503,"UNAVAILABLE",()->s.appendVersion(caller,id,0,"append","receipt"));artifact=(a,t,d,r)->new VerifiedArtifactAuthorityPort.VerifiedArtifact("a".repeat(64),"application/pdf","rustfs:test-only/reference","synthetic-test-authority");
  assertEquals(1,s.appendVersion(caller,id,0,"append","receipt").revision());var first=store.docs.get(id);UUID active=first.versions().getFirst().versionId();store.docs.put(id,new DocumentAggregate(id,first.tenant(),first.creator(),first.createdAt(),first.revision(),first.metadata(),active,first.rules(),first.holds(),first.versions()));
  s.appendVersion(caller,id,1,"append2","receipt2");var two=store.docs.get(id);assertEquals(active,two.activeVersionId());assertEquals(first.versions().getFirst(),two.versions().getFirst());assertEquals(2,two.versions().getLast().versionNumber());assertEquals("development-restricted",two.versions().getFirst().submittedClassification());assertEquals("UNREVIEWED",two.versions().getFirst().initialReviewState());
 }
 @Test void authorityRevocationDuringCommandRollsBackBeforePersistence(){
  UUID id=create();var original=store.docs.get(id);grantCalls=0;revokeAt=3;denied(403,"OPERATION_DENIED",()->service().updateMetadata(caller,id,0,"racing",new Metadata("Changed","")));assertEquals(original,store.docs.get(id));assertEquals(1,store.audits.size());
 }
 @Test void transactionFailureLeavesNoPartialDocumentCommandOrAudit(){
  UUID id=create();var original=store.docs.get(id);store.failRecord=true;assertThrows(IllegalStateException.class,()->service().updateMetadata(caller,id,0,"broken",new Metadata("Changed","")));assertEquals(original,store.docs.get(id));assertEquals(1,store.commands.size());assertEquals(1,store.audits.size());
 }
 @Test void listQueriesOnlyVisibleRowsAndBindsCursorBoundary(){
  UUID id=create();var s=service();var result=s.list(caller,20,null);assertEquals(1,result.items().size());assertNull(result.nextCursor());assertEquals("cursor",s.list(caller,1,null).nextCursor());
  denied(400,"INVALID_LIMIT",()->s.list(caller,0,null));denied(400,"INVALID_LIMIT",()->s.list(caller,51,null));
  for(String invalid:List.of("","bad","cursor","x".repeat(2049)))denied(400,"INVALID_CURSOR",()->s.list(caller,20,invalid));
  cursor=new DocumentCursorPort(){public PagePosition decode(String value,Actor actor,String tenant){return new PagePosition(CLOCK.instant(),id);}public String encode(PagePosition p,Actor a,String t){return null;}};assertEquals(1,service().list(caller,20,"valid").items().size());denied(503,"UNAVAILABLE",()->service().list(caller,1,null));
  replace(id,without(id,Action.INSPECT_DRAFT));assertTrue(service().list(caller,20,null).items().isEmpty());
 }
 @Test void listFinalAuthorizationCannotReuseStaleAllow(){
  create();grantCalls=0;revokeAt=2;denied(403,"OPERATION_DENIED",()->service().list(caller,20,null));
 }
 @Test void createRechecksSeedEvenWhenCreateRemainsAllowed(){
  var inspections=new java.util.concurrent.atomic.AtomicInteger();
  ResourceAuthorizationPort changing=(actor,tenant,action,resource)->{
   if(action.equals(Action.CREATE.wire()))return AuthorityDecision.ALLOW;
   if(action.equals(Action.INSPECT_DRAFT.wire()))return inspections.incrementAndGet()==1?AuthorityDecision.ALLOW:AuthorityDecision.DENY;
   return AuthorityDecision.DENY;
  };
  var app=new DocumentService(store,changing,new DelegationPolicyPort(){public AuthorityDecision targetGrant(Actor a,String t,Action x){return AuthorityDecision.DENY;}public AuthorityDecision ceiling(Actor a,String t,Action x){return AuthorityDecision.DENY;}},artifact,cursor,CLOCK);
  var receipt=app.create(caller,"changed-seed",META);
  assertEquals(0,receipt.revision());assertEquals(2,inspections.get());assertTrue(store.docs.get(receipt.documentId()).rules().isEmpty());assertEquals(1,store.audits.size());
 }
 @Test void unavailableFinalSeedAbortsCreateAtomically(){
  var inspections=new java.util.concurrent.atomic.AtomicInteger();
  ResourceAuthorizationPort changing=(actor,tenant,action,resource)->{
   if(action.equals(Action.CREATE.wire()))return AuthorityDecision.ALLOW;
   if(action.equals(Action.INSPECT_DRAFT.wire()))return inspections.incrementAndGet()==1?AuthorityDecision.ALLOW:AuthorityDecision.UNAVAILABLE;
   return AuthorityDecision.DENY;
  };
  var app=new DocumentService(store,changing,new DelegationPolicyPort(){public AuthorityDecision targetGrant(Actor a,String t,Action x){return AuthorityDecision.DENY;}public AuthorityDecision ceiling(Actor a,String t,Action x){return AuthorityDecision.DENY;}},artifact,cursor,CLOCK);
  denied(503,"UNAVAILABLE",()->app.create(caller,"seed-outage",META));assertTrue(store.docs.isEmpty());assertTrue(store.commands.isEmpty());assertTrue(store.audits.isEmpty());
 }
 @Test void finalDelegationTargetAndCeilingRevocationAbortEveryEntryAtomically(){
  UUID id=create();var before=store.docs.get(id);var entries=List.of(new AclChange.Entry(AclChange.Operation.ADD_ALLOW,new Principal("https://issuer.example","first"),Action.INSPECT_DRAFT),new AclChange.Entry(AclChange.Operation.ADD_ALLOW,new Principal("https://issuer.example","second"),Action.UPDATE_METADATA));
  for(String failure:List.of("target","ceiling","unavailable")){
   var firstTargetChecks=new java.util.concurrent.atomic.AtomicInteger();var ceilingChecks=new java.util.concurrent.atomic.AtomicInteger();
   DelegationPolicyPort changing=new DelegationPolicyPort(){
    public AuthorityDecision targetGrant(Actor a,String t,Action x){if(a.subject().equals("first") && firstTargetChecks.incrementAndGet()>1){if(failure.equals("target"))return AuthorityDecision.DENY;if(failure.equals("unavailable"))return AuthorityDecision.UNAVAILABLE;}return AuthorityDecision.ALLOW;}
    public AuthorityDecision ceiling(Actor a,String t,Action x){return failure.equals("ceiling") && ceilingChecks.incrementAndGet()>2?AuthorityDecision.DENY:AuthorityDecision.ALLOW;}
   };
   var app=new DocumentService(store,grants,changing,artifact,cursor,CLOCK);int status=failure.equals("target")?422:failure.equals("ceiling")?403:503;String code=failure.equals("target")?"TARGET_NOT_GRANTABLE":failure.equals("ceiling")?"DELEGATION_CEILING":"UNAVAILABLE";
   denied(status,code,()->app.changeAcl(caller,id,0,"final-"+failure,new AclChange(AclChange.Mode.DELEGATE,entries)));assertEquals(before,store.docs.get(id));assertEquals(1,store.commands.size());assertEquals(1,store.audits.size());
  }
 }
 static final class Memory implements DocumentUnitOfWork,DocumentRepository {
  Map<UUID,DocumentAggregate> docs=new HashMap<>();Map<CommandKey,StoredCommand> commands=new HashMap<>();List<AuditFact> audits=new ArrayList<>();int finds,executions;boolean failRecord;
  @Override public synchronized <T>T execute(Function<DocumentRepository,T> work){executions++;var oldDocs=new HashMap<>(docs);var oldCommands=new HashMap<>(commands);var oldAudits=new ArrayList<>(audits);try{return work.apply(this);}catch(RuntimeException ex){docs=oldDocs;commands=oldCommands;audits=oldAudits;throw ex;}}
  public void lockCommand(CommandKey key){}
  public Optional<StoredCommand> command(CommandKey key){return Optional.ofNullable(commands.get(key));}
  public Optional<DocumentAggregate> find(String tenant,UUID id){finds++;return Optional.ofNullable(docs.get(id)).filter(d->d.tenant().equals(tenant));}
  public List<DocumentAggregate> listVisible(String tenant,Principal principal,Action action,PagePosition after,int limit){return docs.values().stream().filter(d->d.tenant().equals(tenant)&&d.permits(principal,action)).sorted(Comparator.comparing(DocumentAggregate::createdAt).thenComparing(d->d.documentId().toString())).limit(limit).toList();}
  public void insert(DocumentAggregate document){if(docs.putIfAbsent(document.documentId(),document)!=null)throw new IllegalStateException();}
  public void update(DocumentAggregate document,long expected){if(docs.get(document.documentId()).revision()!=expected)throw new DmFailure(409,"REVISION_CONFLICT");docs.put(document.documentId(),document);}
  public void record(StoredCommand command,AuditFact audit){if(failRecord)throw new IllegalStateException();commands.put(command.key(),command);audits.add(audit);}
 }
}
