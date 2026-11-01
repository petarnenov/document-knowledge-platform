package org.documentknowledge.knowledgebase.application;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.documentknowledge.knowledgebase.domain.KbModels.*;
import org.documentknowledge.knowledgebase.domain.KbModels.Collection;
import static org.documentknowledge.knowledgebase.domain.KbModels.*;
import org.documentknowledge.knowledgebase.application.DocumentShareBatchAuthorityPort.*;

/** Live request coordinator. Network ports return data only and never receive a repository. */
public final class KnowledgeService {
    public sealed interface Change permits EditMetadata,EditStatus,EditAudience,EditAcl,AddMembership,EditMembership {
        long expectedRevision();
        String reason();
        Action action();
    }
    public record EditMetadata(long expectedRevision,Metadata metadata) implements Change {
        public String reason() { return "metadata-update"; } public Action action() { return Action.UPDATE; }
    }
    public record EditStatus(long expectedRevision,Status status,String reason) implements Change { public Action action() { return Action.ARCHIVE; } }
    public record EditAudience(long expectedRevision,Set<AudienceRule> rules,String reason) implements Change {
        public EditAudience { rules=Set.copyOf(rules); } public Action action() { return Action.AUDIENCE; }
    }
    public record EditAcl(long expectedRevision,Mode mode,List<AclChange> changes,String reason) implements Change {
        public EditAcl { changes=List.copyOf(changes); } public Action action() { return mode==Mode.RESTRICT?Action.RESTRICT:Action.DELEGATE; }
    }
    public record AddMembership(long expectedRevision,UUID documentId,long expectedDocumentRevision,String reason) implements Change { public Action action() { return Action.MEMBERSHIP; } }
    public record EditMembership(long expectedRevision,UUID membershipId,long expectedMembershipRevision,long expectedDocumentRevision,State state,String reason) implements Change { public Action action() { return Action.MEMBERSHIP; } }
    public record Page<T>(List<T> items,String nextCursor) { public Page { items=List.copyOf(items); } }
    public record AudienceEvaluation(UUID collectionId,long audiencePolicyRevision,Effect decision) { }
    public record PolicySnapshot(String snapshotToken,UUID documentId,long documentRevision,Decision decision) { }
    public record PolicyValidation(UUID documentId,Decision decision) { }
    private record View(Collection collection,List<Membership> members,long fence,CommandOutcome outcome) { }
    private record PolicyView(List<Membership> memberships,List<Collection> collections,long fence,long scopeRevision,Snapshot stored) { }
    private final KBUnitOfWork units;
    private final KnowledgeAuthorityPort authority;
    private final AudienceRelationPort relations;
    private final DocumentShareBatchAuthorityPort sharing;
    private final DocumentShareEligibilityPort eligibility;
    private final SnapshotTokenPort tokens;
    private final MemberCursorPort cursors;
    private final CollectionCursorPort collectionCursors;
    private final RequestAdmission admission;
    private final Clock clock;
    private final Supplier<UUID> ids;
    public KnowledgeService(KBUnitOfWork units,KnowledgeAuthorityPort authority,AudienceRelationPort relations,DocumentShareBatchAuthorityPort sharing,DocumentShareEligibilityPort eligibility,SnapshotTokenPort tokens,MemberCursorPort cursors,CollectionCursorPort collectionCursors,RequestAdmission admission,Clock clock,Supplier<UUID> ids) {
        this.units=units; this.authority=authority; this.relations=relations; this.sharing=sharing; this.eligibility=eligibility; this.tokens=tokens; this.cursors=cursors; this.collectionCursors=collectionCursors; this.admission=admission; this.clock=clock; this.ids=ids;
    }
    private void grant(Context c,Action a,boolean hidden) {
        var decision=authority.authorize(c,a);
        if(decision==null||decision==KnowledgeAuthorityPort.Result.UNAVAILABLE) throw KbFailure.unavailable();
        if(decision!=KnowledgeAuthorityPort.Result.ALLOW) throw hidden?KbFailure.hidden():new KbFailure(403,"FORBIDDEN");
    }
    private Collection require(CollectionRepository r,Context c,UUID id,Action a) {
        grant(c,a,true);
        Collection collection=r.collection(c.tenant(),id).orElseThrow(KbFailure::hidden);
        if(!collection.tenant().equals(c.tenant())||!collection.permits(c.actor(),a)) throw KbFailure.hidden();
        return collection;
    }
    private static CommandKey key(Context c,String operation,String resource,String key) {
        text(key,1,128); if(key.chars().anyMatch(ch->ch<33||ch>126)) throw new KbFailure(400,"BAD_REQUEST");
        return new CommandKey(c,operation,resource,key);
    }
    private CommandOutcome replay(CommandOutcome stored,String hash) {
        if(!stored.requestHash().equals(hash)) throw new KbFailure(409,"IDEMPOTENCY_CONFLICT");
        return stored;
    }
    public CommandOutcome create(Context c,Metadata metadata,String idempotencyKey,String canonicalHash,Deadline deadline) {
        grant(c,Action.CREATE,false); deadline.check();
        CommandKey key=key(c,"create","collections",idempotencyKey);
        return units.execute(new KBUnitOfWork.LockPlan(c.tenant(),List.of(),List.of(),key),deadline.child(Duration.ofMillis(500)),r->{
            grant(c,Action.CREATE,false); deadline.check();
            var prior=r.command(key); if(prior.isPresent()) return replay(prior.get(),canonicalHash);
            Set<AclRule> acl=new HashSet<>();
            for(Action action:Action.values()) if(action!=Action.CREATE) {
                var result=authority.authorize(c,action); if(result==null||result==KnowledgeAuthorityPort.Result.UNAVAILABLE) throw KbFailure.unavailable();
                if(result==KnowledgeAuthorityPort.Result.ALLOW) acl.add(new AclRule(c.actor(),action,Effect.ALLOW));
            }
            // Repeat the COMPLETE seed decision set at the final write boundary.
            // A known revoked action is omitted; unknown authority aborts the whole command.
            for(Action action:Action.values()) if(action!=Action.CREATE) {
                var result=authority.authorize(c,action);
                if(result==null||result==KnowledgeAuthorityPort.Result.UNAVAILABLE) throw KbFailure.unavailable();
                AclRule rule=new AclRule(c.actor(),action,Effect.ALLOW);
                if(result==KnowledgeAuthorityPort.Result.ALLOW) acl.add(rule); else acl.remove(rule);
            }
            grant(c,Action.CREATE,false); deadline.check();
            UUID id=ids.get(); Instant at=clock.instant(); long fence=r.tenantFence(c.tenant());
            Collection collection=new Collection(id,c.tenant(),0,metadata,Status.ACTIVE,0,new AudiencePolicy(id,1,Set.of(),at),acl,c.actor(),at);
            r.insertCollection(collection); r.advanceTenantFence(c.tenant(),fence);
            return record(r,key,canonicalHash,201,new Receipt(id,null,0),"collection-create",at,false);
        });
    }
    private CommandOutcome record(CollectionRepository r,CommandKey key,String hash,int status,Receipt receipt,String reason,Instant at,boolean activeGuard) {
        CommandOutcome outcome=new CommandOutcome(key,hash,status,receipt,activeGuard);
        Collection committed=r.collection(key.context().tenant(),receipt.collectionId()).orElseThrow(KbFailure::unavailable);
        UUID correlation=ids.get();
        Fact audit=new Fact(ids.get(),key.context(),receipt.collectionId(),receipt.revision(),key.operation(),reason,at,committed.membershipRevision(),committed.audience().revision(),correlation);
        Fact outbox=new Fact(ids.get(),key.context(),receipt.collectionId(),receipt.revision(),key.operation(),reason,at,committed.membershipRevision(),committed.audience().revision(),correlation);
        r.record(outcome,audit,outbox); return outcome;
    }
    private View view(CollectionRepository r,Context c,UUID id,Action action,CommandKey key) {
        Collection collection=require(r,c,id,action);
        List<Membership> members=List.copyOf(r.members(c.tenant(),id));
        if(members.size()>100) throw KbFailure.unavailable();
        return new View(collection,members,r.tenantFence(c.tenant()),key==null?null:r.command(key).orElse(null));
    }
    private List<UUID> affected(View view,Change change) {
        if(change instanceof AddMembership add) {
            UUID original=view.outcome()==null?null:view.outcome().receipt().membershipId();
            return List.of(original==null?add.documentId():view.members().stream().filter(m->m.membershipId().equals(original)).findFirst().orElseThrow(KbFailure::hidden).documentId());
        }
        if(change instanceof EditMembership edit) return List.of(view.members().stream().filter(m->m.membershipId().equals(edit.membershipId())).findFirst().orElseThrow(KbFailure::hidden).documentId());
        if(change instanceof EditAudience || change instanceof EditStatus status && (view.outcome()==null?status.status()==Status.ACTIVE:view.outcome().activeMemberGuard()))
            return view.members().stream().filter(m->m.state()==State.ACTIVE).map(Membership::documentId).distinct().sorted(Comparator.comparing(UUID::toString)).toList();
        return List.of();
    }
    private List<Assessment> assess(Context c,List<Expectation> expected,Deadline deadline) {
        deadline.check(); if(expected.isEmpty()) return List.of();
        List<Assessment> result=BatchEvidence.strict(c,expected,sharing.assess(c,expected,deadline)); deadline.check(); return result;
    }
    private <T> T currentBeforeConflict(Supplier<T> remote,Runnable currentGuard) {
        try { return remote.get(); }
        catch(KbFailure failure) {
            if(failure.status()==409) currentGuard.run();
            throw failure;
        }
    }
    public CommandOutcome change(Context c,UUID collectionId,Change change,String idempotencyKey,String canonicalHash,Deadline whole) {
        revision(change.expectedRevision());
        if(change instanceof AddMembership add) revision(add.expectedDocumentRevision());
        if(change instanceof EditMembership edit) { revision(edit.expectedMembershipRevision()); revision(edit.expectedDocumentRevision()); }
        text(change.reason(),1,1024); grant(c,change.action(),false);
        String resource=change instanceof EditMembership member?collectionId+"/"+member.membershipId():collectionId.toString();
        CommandKey key=key(c,change.getClass().getSimpleName(),resource,idempotencyKey);
        // These routes can require a batch even when their local set proves empty.
        // Admission must precede the first SQL read; ordinary metadata/ACL routes bypass it.
        boolean batchBearing=change instanceof AddMembership||change instanceof EditMembership||change instanceof EditAudience||change instanceof EditStatus;
        try(var permit=batchBearing?admission.acquire(c.actor()):null) {
            View initial=units.read(whole.child(Duration.ofMillis(500)),r->view(r,c,collectionId,change.action(),key));
            List<UUID> documents=affected(initial,change);
            Long expected=initial.outcome()!=null?null:change instanceof AddMembership add?add.expectedDocumentRevision():change instanceof EditMembership edit?edit.expectedDocumentRevision():null;
            Runnable guard=()->units.read(whole.child(Duration.ofMillis(500)),r->view(r,c,collectionId,change.action(),key));
            List<Assessment> first=currentBeforeConflict(()->assess(c,documents.stream().map(id->new Expectation(id,expected)).toList(),whole.child(Duration.ofSeconds(2))),guard);
            Deadline finalPhase=whole.child(Duration.ofSeconds(2));
            currentBeforeConflict(()->assess(c,first.stream().map(a->new Expectation(a.documentId(),a.documentRevision())).toList(),finalPhase.child(Duration.ofMillis(1500))),guard);
            finalPhase.check();
            // A rejected 101st member needs current coherent evidence, never an illegal 101-document write lock plan.
            // Existing members (including INACTIVE) still follow the ordinary mutation/replay path at capacity.
            if(change instanceof AddMembership add&&initial.members().size()==100&&initial.members().stream().noneMatch(m->m.documentId().equals(add.documentId()))) {
                return units.read(finalPhase.child(Duration.ofMillis(500)),r->{
                    finalPhase.check();whole.check();View current=finalChangeView(r,c,collectionId,change,key,initial,documents);
                    if(current.outcome()!=null)return replay(current.outcome(),canonicalHash);
                    throw new KbFailure(422,"LIMIT_EXCEEDED");
                });
            }
            return units.execute(new KBUnitOfWork.LockPlan(c.tenant(),List.of(collectionId),java.util.stream.Stream.concat(initial.members().stream().map(Membership::documentId),documents.stream()).distinct().toList(),key),finalPhase.child(Duration.ofMillis(500)),r->{
                finalPhase.check(); whole.check(); View current=finalChangeView(r,c,collectionId,change,key,initial,documents);
                if(current.outcome()!=null) return replay(current.outcome(),canonicalHash);
                CommandOutcome result=apply(r,c,current,change,key,canonicalHash);
                finalPhase.check(); whole.check(); return result;
            });
        }
    }
    private View finalChangeView(CollectionRepository r,Context c,UUID collectionId,Change change,CommandKey key,View initial,List<UUID> documents) {
        View current=view(r,c,collectionId,change.action(),key);
        // Current resource authority precedes drift/conflicts, including newly affected hidden scopes.
        if(!affected(current,change).equals(documents))throw KbFailure.stale();
        if(current.outcome()!=null)return current;
        if(current.fence()!=initial.fence()||!current.collection().equals(initial.collection())||!current.members().equals(initial.members()))throw KbFailure.stale();
        if(current.collection().revision()!=change.expectedRevision())throw new KbFailure(409,"STALE_REVISION");
        return current;
    }
    private CommandOutcome apply(CollectionRepository r,Context c,View view,Change change,CommandKey key,String hash) {
        Collection old=view.collection(); Collection updated; UUID membershipId=null; Instant at=clock.instant();
        List<UUID> scopes=view.members().stream().map(Membership::documentId).distinct().toList();
        if(change instanceof EditMetadata edit) updated=old.change(edit.metadata(),old.status(),old.audience(),old.rules(),false);
        else if(change instanceof EditStatus edit) updated=old.change(old.metadata(),edit.status(),old.audience(),old.rules(),false);
        else if(change instanceof EditAudience edit) {
            if(edit.rules().size()>100) throw new KbFailure(422,"LIMIT_EXCEEDED");
            for(AudienceRule rule:edit.rules()) if(rule.selector().kind()==SelectorKind.TENANT&&!c.tenant().equals(rule.selector().value())) throw new KbFailure(422,"INVALID_POLICY");
            updated=old.change(old.metadata(),old.status(),new AudiencePolicy(old.collectionId(),next(old.audience().revision()),edit.rules(),at),old.rules(),false);
        } else if(change instanceof EditAcl edit) updated=old.change(old.metadata(),old.status(),old.audience(),acl(c,old,edit),false);
        else {
            Membership membership; Long expected;
            if(change instanceof AddMembership add) {
                membership=view.members().stream().filter(m->m.documentId().equals(add.documentId())).findFirst().orElse(null);
                if(membership==null) {
                    if(view.members().size()>=100||r.memberships(c.tenant(),add.documentId()).size()>=50) throw new KbFailure(422,"LIMIT_EXCEEDED");
                    membership=new Membership(ids.get(),c.tenant(),old.collectionId(),add.documentId(),0,State.ACTIVE,c.actor(),at,add.reason()); expected=null;
                } else { expected=membership.revision(); membership=membership.change(State.ACTIVE,add.reason()); }
            } else {
                EditMembership edit=(EditMembership)change;
                membership=view.members().stream().filter(m->m.membershipId().equals(edit.membershipId())).findFirst().orElseThrow(KbFailure::hidden);
                if(membership.revision()!=edit.expectedMembershipRevision()) throw new KbFailure(409,"STALE_REVISION");
                expected=membership.revision(); membership=membership.change(edit.state(),edit.reason());
            }
            if(membership.state()==State.ACTIVE&&old.status()==Status.ARCHIVED) throw new KbFailure(422,"INVALID_POLICY");
            r.saveMembership(membership,expected,c.actor(),at); membershipId=membership.membershipId(); scopes=List.of(membership.documentId());
            updated=old.change(old.metadata(),old.status(),old.audience(),old.rules(),true);
        }
        r.saveCollection(updated,old.revision());
        for(UUID document:scopes) { Scope scope=r.scope(c.tenant(),document); r.saveScope(new Scope(c.tenant(),document,next(scope.revision())),scope.revision()); }
        r.advanceTenantFence(c.tenant(),view.fence());
        return record(r,key,hash,change instanceof AddMembership?201:200,new Receipt(old.collectionId(),membershipId,updated.revision()),change.reason(),at,change instanceof EditStatus status && status.status()==Status.ACTIVE);
    }
    private Set<AclRule> acl(Context c,Collection collection,EditAcl edit) {
        if(edit.changes().isEmpty()||edit.changes().size()>100) throw new KbFailure(400,"BAD_REQUEST");
        Set<List<Object>> keys=new HashSet<>(); Set<AclRule> result=new HashSet<>(collection.rules());
        for(AclChange delta:edit.changes()) {
            if(!keys.add(List.of(delta.principal(),delta.action()))) throw new KbFailure(422,"DUPLICATE_ACL_CHANGE");
            if(edit.mode()==Mode.RESTRICT) {
                if(!delta.principal().equals(c.actor())||(delta.operation()!=Delta.REMOVE_ALLOW&&delta.operation()!=Delta.ADD_DENY)) throw new KbFailure(422,"RESTRICT_SCOPE");
            } else validateDelegation(c,collection,delta);
            Effect effect=delta.operation()==Delta.ADD_ALLOW||delta.operation()==Delta.REMOVE_ALLOW?Effect.ALLOW:Effect.DENY;
            AclRule rule=new AclRule(delta.principal(),delta.action(),effect);
            if(delta.operation()==Delta.ADD_ALLOW||delta.operation()==Delta.ADD_DENY) result.add(rule); else result.remove(rule);
        }
        if(result.size()>100) throw new KbFailure(422,"ACL_RULE_LIMIT");
        if(edit.mode()==Mode.DELEGATE) for(AclChange delta:edit.changes()) validateDelegation(c,collection,delta);
        // The original operation is checked AFTER all target/ceiling reads as well.
        grant(c,edit.action(),true);
        if(!collection.permits(c.actor(),edit.action())) throw KbFailure.hidden();
        return Set.copyOf(result);
    }
    private void validateDelegation(Context c,Collection collection,AclChange delta) {
        var ceiling=authority.delegationCeiling(c,delta.action());
        if(ceiling==null||ceiling==KnowledgeAuthorityPort.Result.UNAVAILABLE) throw KbFailure.unavailable();
        if(ceiling!=KnowledgeAuthorityPort.Result.ALLOW) throw new KbFailure(403,"DELEGATION_CEILING");
        var target=authority.authorize(new Context(delta.principal(),c.tenant()),delta.action());
        if(target==null||target==KnowledgeAuthorityPort.Result.UNAVAILABLE) throw KbFailure.unavailable();
        if(target!=KnowledgeAuthorityPort.Result.ALLOW) throw new KbFailure(422,"TARGET_NOT_GRANTABLE");
    }
    public Collection inspect(Context c,UUID id,Deadline whole) {
        return units.read(whole.child(Duration.ofMillis(500)),r->{ Collection result=require(r,c,id,Action.INSPECT); whole.check(); grant(c,Action.INSPECT,true); return result; });
    }
    private static int pageLimit(int limit) {
        if(limit<1||limit>50) throw new KbFailure(400,"BAD_REQUEST");
        return limit;
    }
    public Page<Collection> list(Context c,String cursor,Deadline whole) { return list(c,cursor,20,whole); }
    public Page<Collection> list(Context c,String cursor,int limit,Deadline whole) {
        pageLimit(limit);
        grant(c,Action.INSPECT,false);
        CollectionCursorPort.Position position=cursor==null?null:collectionCursors.decode(cursor);
        if(position!=null&&!c.equals(position.context())) throw new KbFailure(400,"BAD_REQUEST");
        return units.read(whole.child(Duration.ofMillis(500)),r->{
            long fence=r.tenantFence(c.tenant());
            if(position!=null&&position.fence()!=fence) throw KbFailure.stale();
            List<Collection> rows=r.visibleCollections(c,position==null?null:position.lastVisible(),limit+1);
            if(rows.size()>limit+1) throw KbFailure.unavailable();
            for(Collection item:rows) if(!c.tenant().equals(item.tenant())||!item.permits(c.actor(),Action.INSPECT)) throw KbFailure.unavailable();
            grant(c,Action.INSPECT,false); whole.check();
            List<Collection> page=rows.stream().limit(limit).toList();
            return new Page<>(page,rows.size()>limit?collectionCursors.encode(new CollectionCursorPort.Position(c,fence,page.getLast().collectionId())):null);
        });
    }
    private List<Assessment> discover(Context c,List<UUID> documents,Deadline deadline) {
        deadline.check(); if(documents.isEmpty()) return List.of();
        List<Assessment> result=BatchEvidence.eligibility(c,documents,eligibility.discover(c,documents,deadline)); deadline.check(); return result;
    }
    public Page<Membership> members(Context c,UUID id,String cursor,Deadline whole) { return members(c,id,cursor,20,whole); }
    public Page<Membership> members(Context c,UUID id,String cursor,int limit,Deadline whole) {
        pageLimit(limit);
        grant(c,Action.INSPECT,true);
        MemberCursorPort.Position position=cursor==null?null:cursors.decode(cursor);
        if(position!=null&&(!position.context().equals(c)||!position.collectionId().equals(id))) throw new KbFailure(400,"BAD_REQUEST");
        try(var permit=admission.acquire(c.actor())) {
            View initial=units.read(whole.child(Duration.ofMillis(500)),r->view(r,c,id,Action.INSPECT,null));
            List<UUID> documents=initial.members().stream().map(Membership::documentId).distinct().sorted(Comparator.comparing(UUID::toString)).toList();
            Runnable guard=()->units.read(whole.child(Duration.ofMillis(500)),r->view(r,c,id,Action.INSPECT,null));
            List<Assessment> first=currentBeforeConflict(()->discover(c,documents,whole.child(Duration.ofSeconds(2))),guard);
            Deadline finalPhase=whole.child(Duration.ofSeconds(2));
            List<Assessment> last=currentBeforeConflict(()->discover(c,documents,finalPhase.child(Duration.ofMillis(1500))),guard);
            String candidate=BatchEvidence.requestedDigest(documents), eligible=BatchEvidence.viewDigest(last);
            return units.execute(new KBUnitOfWork.LockPlan(c.tenant(),List.of(id),documents,null),finalPhase.child(Duration.ofMillis(500)),r->{
                View current=view(r,c,id,Action.INSPECT,null);
                if(!current.collection().equals(initial.collection())||!current.members().equals(initial.members())||current.fence()!=initial.fence()||!BatchEvidence.viewDigest(first).equals(eligible)) throw KbFailure.stale();
                if(position!=null&&(position.fence()!=current.fence()||position.collectionRevision()!=current.collection().revision()||!position.candidateDigest().equals(candidate)||!position.eligibleDigest().equals(eligible))) throw KbFailure.stale();
                Set<UUID> visible=new HashSet<>(last.stream().map(Assessment::documentId).toList());
                List<Membership> complete=current.members().stream().filter(m->visible.contains(m.documentId())).sorted(Comparator.comparing(m->m.membershipId().toString())).toList();
                if(position!=null&&complete.stream().noneMatch(m->m.membershipId().equals(position.lastVisible()))) throw KbFailure.stale();
                List<Membership> after=complete.stream().filter(m->position==null||m.membershipId().toString().compareTo(position.lastVisible().toString())>0).toList();
                List<Membership> page=after.stream().limit(limit).toList();
                finalPhase.check(); whole.check(); grant(c,Action.INSPECT,true);
                String next=after.size()>limit?cursors.encode(new MemberCursorPort.Position(c,id,current.fence(),current.collection().revision(),candidate,eligible,page.getLast().membershipId())):null;
                return new Page<>(page,next);
            });
        }
    }
    private boolean audience(Context c,AudiencePolicy policy) {
        boolean allow=false,deny=false;
        for(AudienceRule rule:policy.rules()) {
            Selector selector=rule.selector(); KnowledgeAuthorityPort.Result match;
            if(selector.kind()==SelectorKind.PRINCIPAL) match=selector.issuer().equals(c.actor().issuer())&&selector.value().equals(c.actor().subject())?KnowledgeAuthorityPort.Result.ALLOW:KnowledgeAuthorityPort.Result.DENY;
            else if(selector.kind()==SelectorKind.TENANT) match=selector.value().equals(c.tenant())?KnowledgeAuthorityPort.Result.ALLOW:KnowledgeAuthorityPort.Result.DENY;
            else match=relations.matches(c,selector);
            if(match==null||match==KnowledgeAuthorityPort.Result.UNAVAILABLE) throw KbFailure.unavailable();
            if(match==KnowledgeAuthorityPort.Result.ALLOW) { if(rule.effect()==Effect.DENY) deny=true; else allow=true; }
        }
        return allow&&!deny;
    }
    public AudienceEvaluation evaluate(Context c,UUID id,Deadline whole) {
        return units.read(whole.child(Duration.ofMillis(500)),r->{
            Collection collection=require(r,c,id,Action.EVALUATE); audience(c,collection.audience());
            collection=require(r,c,id,Action.EVALUATE); boolean allowed=audience(c,collection.audience());
            Collection current=require(r,c,id,Action.EVALUATE); whole.check();
            if(!current.equals(collection)) throw KbFailure.stale();
            return new AudienceEvaluation(id,collection.audience().revision(),allowed?Effect.ALLOW:Effect.DENY);
        });
    }
    private PolicyView policyView(CollectionRepository r,Context c,UUID document,String hash) {
        grant(c,Action.EVALUATE,true);
        List<Membership> memberships=List.copyOf(r.memberships(c.tenant(),document));
        if(memberships.size()>50) throw KbFailure.unavailable();
        List<Collection> collections=new ArrayList<>();
        for(Membership member:memberships) collections.add(require(r,c,member.collectionId(),Action.EVALUATE));
        return new PolicyView(memberships,List.copyOf(collections),r.tenantFence(c.tenant()),r.scope(c.tenant(),document).revision(),hash==null?null:r.snapshot(hash,c,document).orElseThrow(KbFailure::hidden));
    }
    private List<ScopeEntry> entries(PolicyView view) {
        List<ScopeEntry> result=new ArrayList<>();
        for(Membership m:view.memberships()) {
            Collection collection=view.collections().stream().filter(k->k.collectionId().equals(m.collectionId())).findFirst().orElseThrow(KbFailure::unavailable);
            result.add(new ScopeEntry(m.membershipId(),m.collectionId(),collection.revision(),collection.membershipRevision(),m.revision(),collection.audience().revision(),m.state(),collection.status()));
        }
        return result.stream().sorted(Comparator.comparing(e->e.collectionId().toString())).toList();
    }
    private Decision compatible(Context c,PolicyView view) {
        boolean any=false;
        for(Membership m:view.memberships()) {
            Collection collection=view.collections().stream().filter(k->k.collectionId().equals(m.collectionId())).findFirst().orElseThrow(KbFailure::unavailable);
            audience(c,collection.audience()); // Resolve syntax/current relations; compatibility is NOT actor audience permission.
            if(m.state()==State.ACTIVE&&collection.status()==Status.ACTIVE) any=true;
        }
        return any?Decision.COMPATIBLE:Decision.INCOMPATIBLE;
    }
    public PolicySnapshot snapshot(Context c,UUID document,long expectedDocumentRevision,Deadline whole) {
        return policy(c,document,expectedDocumentRevision,null,whole).snapshot();
    }
    public PolicyValidation validate(Context c,UUID document,long expectedDocumentRevision,String token,Deadline whole) {
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}")) throw new KbFailure(400,"BAD_REQUEST");
        return policy(c,document,expectedDocumentRevision,token,whole).validation();
    }
    private record PolicyResult(PolicySnapshot snapshot,PolicyValidation validation) { }
    private PolicyResult policy(Context c,UUID document,long expected,String token,Deadline whole) {
        revision(expected); grant(c,Action.EVALUATE,true); String tokenHash=token==null?null:BatchEvidence.hash(token);
        try(var permit=admission.acquire(c.actor())) {
            PolicyView initial=units.read(whole.child(Duration.ofMillis(500)),r->policyView(r,c,document,tokenHash));
            Runnable guard=()->units.read(whole.child(Duration.ofMillis(500)),r->policyView(r,c,document,tokenHash));
            List<Assessment> first=currentBeforeConflict(()->assess(c,List.of(new Expectation(document,expected)),whole.child(Duration.ofSeconds(2))),guard);
            compatible(c,initial);
            Deadline finalPhase=whole.child(Duration.ofSeconds(2));
            List<Assessment> last=currentBeforeConflict(()->assess(c,List.of(new Expectation(document,first.getFirst().documentRevision())),finalPhase.child(Duration.ofMillis(1500))),guard);
            return units.execute(new KBUnitOfWork.LockPlan(c.tenant(),initial.collections().stream().map(Collection::collectionId).toList(),List.of(document),null),finalPhase.child(Duration.ofMillis(500)),r->{
                PolicyView current=policyView(r,c,document,tokenHash); // includes all newly hidden scopes BEFORE drift
                Decision decision=compatible(c,current);
                if(current.fence()!=initial.fence()||current.scopeRevision()!=initial.scopeRevision()||!entries(current).equals(entries(initial))) throw KbFailure.stale();
                if(token!=null) {
                    Snapshot stored=current.stored();
                    if(!stored.context().equals(c)||!stored.documentId().equals(document)) throw KbFailure.hidden();
                    if(stored.documentRevision()!=last.getFirst().documentRevision()||stored.tenantFence()!=current.fence()||stored.scopeRevision()!=current.scopeRevision()||!stored.entries().equals(entries(current))) throw KbFailure.stale();
                    finalPhase.check(); whole.check(); grant(c,Action.EVALUATE,true);
                    return new PolicyResult(null,new PolicyValidation(document,decision));
                }
                String reference=tokens.issue(); if(reference==null||!reference.matches("[A-Za-z0-9_-]{43}")) throw KbFailure.unavailable();
                Snapshot snapshot=new Snapshot(BatchEvidence.hash(reference),c,document,last.getFirst().documentRevision(),current.fence(),current.scopeRevision(),entries(current),decision,clock.instant());
                r.saveSnapshot(snapshot); finalPhase.check(); whole.check(); grant(c,Action.EVALUATE,true);
                return new PolicyResult(new PolicySnapshot(reference,document,snapshot.documentRevision(),decision),null);
            });
        }
    }
}
