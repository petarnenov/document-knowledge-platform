package org.documentknowledge.knowledgebase.domain;

import java.time.Instant;
import java.util.*;

/** Immutable KB-owned values; external document IDs remain references, never DM entities. */
public final class KbModels {
    private KbModels() { }
    public static final long MAX_REVISION = 9_007_199_254_740_991L;
    public static String text(String value, int min, int max) {
        if (value == null || value.length() < min || value.length() > max) throw new KbFailure(400,"BAD_REQUEST");
        return value;
    }
    public static long revision(long value) {
        if (value < 0 || value > MAX_REVISION) throw new KbFailure(400,"BAD_REQUEST");
        return value;
    }
    public static long next(long value) { if (value >= MAX_REVISION) throw KbFailure.unavailable(); return revision(value + 1); }
    public enum Action {
        CREATE("knowledge.collection.create"), INSPECT("knowledge.collection.inspect"), UPDATE("knowledge.collection.update"), ARCHIVE("knowledge.collection.archive"),
        RESTRICT("knowledge.acl.restrict"), DELEGATE("knowledge.acl.delegate"), AUDIENCE("knowledge.audience.manage"), MEMBERSHIP("knowledge.membership.manage"), EVALUATE("knowledge.policy.evaluate");
        private final String wire;
        Action(String wire) { this.wire=wire; }
        public String wire() { return wire; }
    }
    public enum Effect { ALLOW, DENY }
    public enum Status { ACTIVE, ARCHIVED }
    public enum State { ACTIVE, INACTIVE }
    public enum SelectorKind { PRINCIPAL, TENANT, FIRM, RESOURCE_SCOPE }
    public enum Delta { ADD_ALLOW, REMOVE_ALLOW, ADD_DENY, REMOVE_DENY }
    public enum Mode { RESTRICT, DELEGATE }
    public enum Decision { COMPATIBLE, INCOMPATIBLE }
    public record Principal(String issuer, String subject) {
        public Principal { text(issuer,1,2048); text(subject,1,256); }
    }
    public record Context(Principal actor,String tenant) {
        public Context { Objects.requireNonNull(actor); text(tenant,1,256); }
    }
    public record Metadata(String title,String description) {
        public Metadata { text(title,1,256); text(description,0,4096); }
    }
    public record AclRule(Principal principal,Action action,Effect effect) {
        public AclRule { Objects.requireNonNull(principal); Objects.requireNonNull(action); Objects.requireNonNull(effect); }
    }
    public record AclChange(Delta operation,Principal principal,Action action) {
        public AclChange { Objects.requireNonNull(operation); Objects.requireNonNull(principal); Objects.requireNonNull(action); }
    }
    public record Selector(SelectorKind kind,String issuer,String value) {
        public Selector { Objects.requireNonNull(kind); text(value,1,256); if (kind==SelectorKind.TENANT) { if (issuer!=null) throw new KbFailure(422,"INVALID_POLICY"); } else text(issuer,1,2048); }
    }
    public record AudienceRule(Effect effect,Selector selector) {
        public AudienceRule { Objects.requireNonNull(effect); Objects.requireNonNull(selector); }
    }
    public record AudiencePolicy(UUID collectionId,long revision,Set<AudienceRule> rules,Instant effectiveAt) {
        public AudiencePolicy { Objects.requireNonNull(collectionId); KbModels.revision(revision); if(revision==0) throw new KbFailure(400,"BAD_REQUEST"); rules=Set.copyOf(rules); if(rules.size()>100) throw new KbFailure(422,"LIMIT_EXCEEDED"); Objects.requireNonNull(effectiveAt); }
    }
    public record Collection(UUID collectionId,String tenant,long revision,Metadata metadata,Status status,long membershipRevision,AudiencePolicy audience,Set<AclRule> rules,Principal creator,Instant createdAt) {
        public Collection { Objects.requireNonNull(collectionId); text(tenant,1,256); KbModels.revision(revision); Objects.requireNonNull(metadata); Objects.requireNonNull(status); KbModels.revision(membershipRevision); Objects.requireNonNull(audience); if(!collectionId.equals(audience.collectionId())) throw KbFailure.unavailable(); Objects.requireNonNull(creator); Objects.requireNonNull(createdAt); rules=Set.copyOf(rules); if(rules.size()>100) throw new KbFailure(422,"ACL_RULE_LIMIT"); }
        public boolean permits(Principal actor,Action action) { return rules.contains(new AclRule(actor,action,Effect.ALLOW)) && !rules.contains(new AclRule(actor,action,Effect.DENY)); }
        public Collection change(Metadata m,Status s,AudiencePolicy p,Set<AclRule> acl,boolean membership) { return new Collection(collectionId,tenant,next(revision),m,s,membership?next(membershipRevision):membershipRevision,p,acl,creator,createdAt); }
    }
    public record Membership(UUID membershipId,String tenant,UUID collectionId,UUID documentId,long revision,State state,Principal creator,Instant createdAt,String reason) {
        public Membership { Objects.requireNonNull(membershipId); text(tenant,1,256); Objects.requireNonNull(collectionId); Objects.requireNonNull(documentId); KbModels.revision(revision); Objects.requireNonNull(state); Objects.requireNonNull(creator); Objects.requireNonNull(createdAt); text(reason,1,1024); }
        public Membership change(State target,String why) { return new Membership(membershipId,tenant,collectionId,documentId,next(revision),target,creator,createdAt,why); }
    }
    public record Scope(String tenant,UUID documentId,long revision) { public Scope { text(tenant,1,256); Objects.requireNonNull(documentId); KbModels.revision(revision); } }
    public record ScopeEntry(UUID membershipId,UUID collectionId,long collectionRevision,long collectionMembershipRevision,long membershipRevision,long audienceRevision,State state,Status status) {
        public ScopeEntry(UUID membershipId,UUID collectionId,long collectionRevision,long membershipRevision,long audienceRevision,State state,Status status) { this(membershipId,collectionId,collectionRevision,0,membershipRevision,audienceRevision,state,status); }
        public ScopeEntry { Objects.requireNonNull(membershipId); Objects.requireNonNull(collectionId); KbModels.revision(collectionRevision); KbModels.revision(collectionMembershipRevision); KbModels.revision(membershipRevision); KbModels.revision(audienceRevision); Objects.requireNonNull(state); Objects.requireNonNull(status); }
    }
    public record Snapshot(String tokenHash,Context context,UUID documentId,long documentRevision,long tenantFence,long scopeRevision,List<ScopeEntry> entries,Decision decision,Instant createdAt) {
        public Snapshot { text(tokenHash,64,64); Objects.requireNonNull(context); Objects.requireNonNull(documentId); KbModels.revision(documentRevision); KbModels.revision(tenantFence); KbModels.revision(scopeRevision); entries=List.copyOf(entries); if(entries.size()>50) throw KbFailure.unavailable(); Objects.requireNonNull(decision); Objects.requireNonNull(createdAt); }
    }
    public record Receipt(UUID collectionId,UUID membershipId,long revision) { public Receipt { Objects.requireNonNull(collectionId); KbModels.revision(revision); } }
    public record CommandKey(Context context,String operation,String resourceKey,String idempotencyKey) {
        public CommandKey { Objects.requireNonNull(context); text(operation,1,64); text(resourceKey,1,128); text(idempotencyKey,1,128); }
    }
    public record CommandOutcome(CommandKey key,String requestHash,int status,Receipt receipt,boolean activeMemberGuard) {
        public CommandOutcome(CommandKey key,String requestHash,int status,Receipt receipt) { this(key,requestHash,status,receipt,false); }
        public CommandOutcome { Objects.requireNonNull(key); text(requestHash,64,64); if(status!=200&&status!=201) throw KbFailure.unavailable(); Objects.requireNonNull(receipt); }
    }
    public record Fact(UUID eventId,Context context,UUID collectionId,long revision,String operation,String reason,Instant occurredAt,long membershipRevision,long audiencePolicyRevision,UUID correlationId) {
        public Fact(UUID eventId,Context context,UUID collectionId,long revision,String operation,String reason,Instant occurredAt) { this(eventId,context,collectionId,revision,operation,reason,occurredAt,0,1,eventId); }
        public Fact { KbModels.revision(membershipRevision); KbModels.revision(audiencePolicyRevision); Objects.requireNonNull(correlationId); Objects.requireNonNull(eventId); Objects.requireNonNull(context); Objects.requireNonNull(collectionId); KbModels.revision(revision); text(operation,1,64); text(reason,1,1024); Objects.requireNonNull(occurredAt); }
    }
}
