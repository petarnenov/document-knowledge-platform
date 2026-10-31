package org.documentknowledge.documentmanagement.domain;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
public record DocumentAggregate(UUID documentId,String tenant,Principal creator,Instant createdAt,long revision,Metadata metadata,UUID activeVersionId,Set<AclRule> rules,List<LegalHold> holds,List<DocumentVersion> versions) {
 public DocumentAggregate {java.util.Objects.requireNonNull(documentId);Validation.text(tenant,256,false);java.util.Objects.requireNonNull(creator);java.util.Objects.requireNonNull(createdAt);java.util.Objects.requireNonNull(metadata);if(revision<0 || revision>9007199254740991L)throw new IllegalArgumentException("Invalid revision");rules=Set.copyOf(rules);holds=List.copyOf(holds);versions=List.copyOf(versions);if(rules.size()>100 || holds.size()>100)throw new IllegalArgumentException("Aggregate limit");for(int i=0;i<versions.size();i++){var v=versions.get(i);if(!documentId.equals(v.documentId()) || v.versionNumber()!=i+1)throw new IllegalArgumentException("Version ownership/ordinal");}if(activeVersionId!=null && versions.stream().noneMatch(v->activeVersionId.equals(v.versionId())))throw new IllegalArgumentException("Active version ownership");}
 public boolean permits(Principal actor,Action action){return rules.contains(new AclRule(actor,action,AclRule.Effect.ALLOW)) && !rules.contains(new AclRule(actor,action,AclRule.Effect.DENY));}
 public String lifecycle(){return "DRAFT";}
 public String classification(){return "development-restricted";}
 public int classificationPolicyRevision(){return 1;}
 public DocumentAggregate(UUID id,String tenant,Principal creator,Instant createdAt,long revision,Metadata metadata,Set<AclRule> rules,List<LegalHold> holds,List<DocumentVersion> versions){this(id,tenant,creator,createdAt,revision,metadata,null,rules,holds,versions);}
 public int retentionPolicyRevision(){return 1;}
 public String retentionResolution(){return "UNRESOLVED";}
 public String retentionPolicyRef(){return "development-unresolved";}
 public Instant retainUntil(){return null;}
 public boolean destructiveEligible(){return false;}
 public DocumentAggregate next(Metadata nextMetadata,Set<AclRule> nextRules,List<LegalHold> nextHolds,List<DocumentVersion> nextVersions){return new DocumentAggregate(documentId,tenant,creator,createdAt,Math.addExact(revision,1),nextMetadata,activeVersionId,nextRules,nextHolds,nextVersions);}
}
