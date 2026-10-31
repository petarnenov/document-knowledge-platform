package org.documentknowledge.documentmanagement.domain;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DocumentModelTest {
 final UUID id=UUID.randomUUID();final Principal actor=new Principal("https://issuer.example","staff");final Instant time=Instant.parse("2026-10-31T12:00:00Z");
 DocumentVersion version(UUID documentId,int n){return new DocumentVersion(UUID.randomUUID(),documentId,n,"a".repeat(64),"application/pdf","test-only:reference","fixture",actor,time);}
 DocumentAggregate document(long rev,UUID active,Set<AclRule> rules,List<LegalHold> holds,List<DocumentVersion> versions){return new DocumentAggregate(id,"tenant",actor,time,rev,new Metadata("Title",""),active,rules,holds,versions);}
 @Test void metadataIsBoundedUntrustedTextAndPrincipalNamespaced(){
  assertEquals("<script>",new Metadata("<script>","literal").title());assertEquals(256,new Metadata("a".repeat(256),"a".repeat(4096)).title().length());
  for(String invalid:Arrays.asList(null,""," ","a".repeat(257),"control\n"))assertThrows(IllegalArgumentException.class,()->new Metadata(invalid,""));
  assertThrows(IllegalArgumentException.class,()->new Metadata("Title",null));assertThrows(IllegalArgumentException.class,()->new Metadata("Title","a".repeat(4097)));
  assertNotEquals(actor,new Principal("https://other.example",actor.subject()));
  assertThrows(IllegalArgumentException.class,()->new Principal(null,"staff"));assertThrows(IllegalArgumentException.class,()->new Principal("issuer",""));
  for(Action action:Action.values())assertEquals(action,Action.parse(action.wire()));assertThrows(IllegalArgumentException.class,()->Action.parse("admin"));assertThrows(IllegalArgumentException.class,()->Action.parse(null));
 }
 @Test void aggregateCopiesCollectionsAndPreservesActiveVersion(){
  var v=version(id,1);var rules=new HashSet<AclRule>();rules.add(new AclRule(actor,Action.INSPECT_DRAFT,AclRule.Effect.ALLOW));var holds=new ArrayList<LegalHold>();holds.add(new LegalHold(UUID.randomUUID(),"Reason",actor,time));var versions=new ArrayList<>(List.of(v));
  var d=document(0,v.versionId(),rules,holds,versions);rules.clear();holds.clear();versions.clear();assertEquals(1,d.rules().size());assertEquals(1,d.holds().size());assertEquals(1,d.versions().size());assertTrue(d.permits(actor,Action.INSPECT_DRAFT));assertFalse(d.permits(actor,Action.READ));
  assertThrows(UnsupportedOperationException.class,()->d.rules().clear());assertThrows(UnsupportedOperationException.class,()->d.holds().clear());assertThrows(UnsupportedOperationException.class,()->d.versions().clear());
  var next=d.next(new Metadata("Changed",""),d.rules(),d.holds(),d.versions());assertEquals(1,next.revision());assertEquals(v.versionId(),next.activeVersionId());assertEquals(v,d.versions().getFirst());assertEquals("Title",d.metadata().title());assertEquals("DRAFT",next.lifecycle());assertNull(next.retainUntil());assertFalse(next.destructiveEligible());
  var denied=new HashSet<>(d.rules());denied.add(new AclRule(actor,Action.INSPECT_DRAFT,AclRule.Effect.DENY));assertFalse(d.next(d.metadata(),denied,d.holds(),d.versions()).permits(actor,Action.INSPECT_DRAFT));
 }
 @Test void relationalInvariantsCannotBeConstructedInDomain(){
  assertThrows(IllegalArgumentException.class,()->document(-1,null,Set.of(),List.of(),List.of()));assertThrows(IllegalArgumentException.class,()->document(9007199254740992L,null,Set.of(),List.of(),List.of()));
  assertThrows(IllegalArgumentException.class,()->document(0,UUID.randomUUID(),Set.of(),List.of(),List.of()));assertThrows(IllegalArgumentException.class,()->document(0,null,Set.of(),List.of(),List.of(version(UUID.randomUUID(),1))));assertThrows(IllegalArgumentException.class,()->document(0,null,Set.of(),List.of(),List.of(version(id,2))));
  Set<AclRule> rules=new HashSet<>();for(int i=0;i<101;i++)rules.add(new AclRule(new Principal("issuer","actor"+i),Action.READ,AclRule.Effect.ALLOW));assertThrows(IllegalArgumentException.class,()->document(0,null,rules,List.of(),List.of()));
  var hold=new LegalHold(UUID.randomUUID(),"Reason",actor,time);assertThrows(IllegalArgumentException.class,()->document(0,null,Set.of(),Collections.nCopies(101,hold),List.of()));
  assertThrows(NullPointerException.class,()->new AclRule(null,Action.READ,AclRule.Effect.ALLOW));assertThrows(NullPointerException.class,()->new AclRule(actor,null,AclRule.Effect.ALLOW));assertThrows(NullPointerException.class,()->new AclRule(actor,Action.READ,null));
 }
 @Test void versionsHaveVerifiedImmutableContentAndClassification(){
  var v=version(id,1);assertEquals("development-restricted",v.submittedClassification());assertEquals("UNREVIEWED",v.initialReviewState());
  for(String hash:Arrays.asList(null,"short","A".repeat(64)))assertThrows(IllegalArgumentException.class,()->new DocumentVersion(UUID.randomUUID(),id,1,hash,"mime","ref","producer",actor,time));
  assertThrows(IllegalArgumentException.class,()->version(id,0));assertThrows(IllegalArgumentException.class,()->new DocumentVersion(UUID.randomUUID(),id,1,"a".repeat(64),"mime","ref","producer",actor,time,"Public"));
  assertThrows(IllegalArgumentException.class,()->new LegalHold(UUID.randomUUID(),"",actor,time));assertThrows(IllegalArgumentException.class,()->new LegalHold(UUID.randomUUID(),"a".repeat(1025),actor,time));
  assertEquals("development-unresolved",document(0,null,Set.of(),List.of(),List.of()).retentionPolicyRef());assertEquals(1,document(0,null,Set.of(),List.of(),List.of()).retentionPolicyRevision());assertEquals("UNRESOLVED",document(0,null,Set.of(),List.of(),List.of()).retentionResolution());assertEquals(1,document(0,null,Set.of(),List.of(),List.of()).classificationPolicyRevision());assertEquals("development-restricted",document(0,null,Set.of(),List.of(),List.of()).classification());
 }
}
