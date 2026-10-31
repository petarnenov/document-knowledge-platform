package org.documentknowledge.documentmanagement.application;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.BiConsumer;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.documentmanagement.domain.upload.*;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;
import org.documentknowledge.documentmanagement.domain.*;
import org.documentknowledge.identity.Actor;
import org.documentknowledge.identity.AuthorityDecision;
import org.documentknowledge.identity.ResourceAuthorizationPort;

/** The application owns conjunction, denial ordering and command transactions; adapters supply authority, never policy shortcuts. */
public final class DocumentService {
    private final DocumentUnitOfWork transactions;
    private final ResourceAuthorizationPort grants;
    private final DelegationPolicyPort delegation;
    private final VerifiedArtifactAuthorityPort artifacts;
    private final DocumentCursorPort cursors;
    private final Clock clock;
    public DocumentService(DocumentUnitOfWork transactions, ResourceAuthorizationPort grants, DelegationPolicyPort delegation,
            VerifiedArtifactAuthorityPort artifacts, DocumentCursorPort cursors, Clock clock) {
        this.transactions=Objects.requireNonNull(transactions);this.grants=Objects.requireNonNull(grants);
        this.delegation=Objects.requireNonNull(delegation);this.artifacts=Objects.requireNonNull(artifacts);
        this.cursors=Objects.requireNonNull(cursors);this.clock=Objects.requireNonNull(clock);
    }
    public CommandReceipt create(DocumentCaller caller,String key,Metadata metadata) {
        route(caller,Action.CREATE,"",false);
        CommandKey commandKey=key(caller,Action.CREATE,"",key);
        String fingerprint=fingerprint("create",metadata.title(),metadata.description());
        return transactions.execute(repository->{
            repository.lockCommand(commandKey);
            route(caller,Action.CREATE,"",false);
            var previous=repository.command(commandKey);
            if(previous.isPresent()){route(caller,Action.CREATE,"",false);return replay(previous.get(),fingerprint);}
            Set<AclRule> rules=new HashSet<>();
            for(Action action:Action.values()) {
                if(action==Action.CREATE || action==Action.READ)continue;
                if(decision(caller,action,"")==AuthorityDecision.ALLOW)rules.add(new AclRule(caller.principal(),action,AclRule.Effect.ALLOW));
            }
            route(caller,Action.CREATE,"",false);
            // Creation permission does not freeze ancillary bootstrap grants. Recheck
            // every seeded action at the final boundary; revoked actions are omitted.
            Set<AclRule> currentRules=new HashSet<>();
            for(AclRule rule:rules) {
                if(decision(caller,rule.action(),"")==AuthorityDecision.ALLOW)currentRules.add(rule);
            }
            route(caller,Action.CREATE,"",false);
            var document=new DocumentAggregate(UUID.randomUUID(),caller.tenant(),caller.principal(),now(),0,metadata,currentRules,List.of(),List.of());
            repository.insert(document);
            return record(repository,commandKey,fingerprint,201,document,Action.CREATE);
        });
    }
    public DocumentAggregate inspect(DocumentCaller caller,UUID id){return inspectAction(caller,id,Action.INSPECT_DRAFT);}
    public DocumentAggregate inspectAcl(DocumentCaller caller,UUID id){return inspectAction(caller,id,Action.INSPECT_DRAFT);}
    public DocumentAggregate inspectHolds(DocumentCaller caller,UUID id){return inspectAction(caller,id,Action.HOLD_INSPECT);}
    private DocumentAggregate inspectAction(DocumentCaller caller,UUID id,Action action) {
        route(caller,action,id.toString(),true);
        return transactions.execute(repository->{
            DocumentAggregate document=load(repository,caller,id,action);
            route(caller,action,id.toString(),true);
            requireAcl(repository.find(caller.tenant(),id).orElseThrow(DocumentService::hidden),caller,action);
            return document;
        });
    }
    public DocumentPage list(DocumentCaller caller,int limit,String cursor) {
        route(caller,Action.INSPECT_DRAFT,"",false);
        if(limit<1 || limit>50)throw new DmFailure(400,"INVALID_LIMIT");
        PagePosition after=null;
        if(cursor!=null) {
            if(cursor.isBlank() || cursor.length()>2048)throw new DmFailure(400,"INVALID_CURSOR");
            try {after=cursors.decode(cursor,caller.actor(),caller.tenant());if(after==null)throw new IllegalArgumentException();}
            catch(RuntimeException invalid){throw new DmFailure(400,"INVALID_CURSOR");}
        }
        PagePosition position=after;
        return transactions.execute(repository->{
            List<DocumentAggregate> documents=repository.listVisible(caller.tenant(),caller.principal(),Action.INSPECT_DRAFT,position,limit);
            if(documents.size()>limit)throw new DmFailure(503,"UNAVAILABLE");
            for(DocumentAggregate document:documents) {
                route(caller,Action.INSPECT_DRAFT,document.documentId().toString(),false);
                requireAcl(repository.find(caller.tenant(),document.documentId()).orElseThrow(DocumentService::hidden),caller,Action.INSPECT_DRAFT);
            }
            route(caller,Action.INSPECT_DRAFT,"",false);
            String next=null;
            if(documents.size()==limit) {
                var last=documents.getLast();
                next=cursors.encode(new PagePosition(last.createdAt(),last.documentId()),caller.actor(),caller.tenant());
                if(next==null || next.isBlank() || next.length()>2048)throw new DmFailure(503,"UNAVAILABLE");
            }
            return new DocumentPage(documents,next);
        });
    }
    public CommandReceipt updateMetadata(DocumentCaller caller,UUID id,long expected,String key,Metadata metadata) {
        return mutate(caller,id,expected,key,Action.UPDATE_METADATA,fingerprint("metadata",expected,metadata.title(),metadata.description()),
            document->document.next(metadata,document.rules(),document.holds(),document.versions()),200);
    }
    public CommandReceipt changeAcl(DocumentCaller caller,UUID id,long expected,String key,AclChange change) {
        Action action=change.mode()==AclChange.Mode.RESTRICT?Action.ACL_RESTRICT:Action.ACL_DELEGATE;
        List<String> delta=change.changes().stream().map(e->fingerprint(e.operation(),e.principal().issuer(),e.principal().subject(),e.action().wire())).sorted().toList();
        String fingerprint=fingerprint("acl",expected,change.mode(),String.join(",",delta));
        // Duplicate principal/action is rejected before entering any transaction, regardless of operation.
        route(caller,action,id.toString(),false);
        Set<String> keys=new HashSet<>();
        for(var entry:change.changes())if(!keys.add(fingerprint(entry.principal().issuer(),entry.principal().subject(),entry.action())))throw new DmFailure(422,"DUPLICATE_ACL_CHANGE");
        return mutate(caller,id,expected,key,action,fingerprint,document->{
            Set<AclRule> next=new HashSet<>(document.rules());
            for(var entry:change.changes()) {
                if(change.mode()==AclChange.Mode.RESTRICT) {
                    if(!caller.principal().equals(entry.principal()) || !(entry.operation()==AclChange.Operation.REMOVE_ALLOW || entry.operation()==AclChange.Operation.ADD_DENY))throw new DmFailure(422,"RESTRICT_SCOPE");
                } else {
                    requireDelegation(caller,entry);
                }
                AclRule.Effect effect=entry.operation()==AclChange.Operation.ADD_ALLOW || entry.operation()==AclChange.Operation.REMOVE_ALLOW?AclRule.Effect.ALLOW:AclRule.Effect.DENY;
                AclRule rule=new AclRule(entry.principal(),entry.action(),effect);
                if(entry.operation()==AclChange.Operation.ADD_ALLOW || entry.operation()==AclChange.Operation.ADD_DENY)next.add(rule);else next.remove(rule);
            }
            if(next.size()>100)throw new DmFailure(422,"ACL_RULE_LIMIT");
            return document.next(document.metadata(),next,document.holds(),document.versions());
        },200,()-> {
            if(change.mode()==AclChange.Mode.DELEGATE) {
                for(var entry:change.changes())requireDelegation(caller,entry);
            }
        });
    }
    public CommandReceipt placeHold(DocumentCaller caller,UUID id,long expected,String key,String reason) {
        Validation.text(reason,1024,false);
        return mutate(caller,id,expected,key,Action.HOLD_PLACE,fingerprint("hold",expected,reason),document->{
            if(document.holds().size()>=100)throw new DmFailure(422,"HOLD_LIMIT");
            var next=new ArrayList<>(document.holds());next.add(new LegalHold(UUID.randomUUID(),reason,caller.principal(),now()));
            return document.next(document.metadata(),document.rules(),next,document.versions());
        },201);
    }
    public CommandReceipt appendVersion(DocumentCaller caller,UUID id,long expected,String key,String receiptId) {
        Validation.text(receiptId,256,false);route(caller,Action.VERSION_APPEND,id.toString(),false);
        String fingerprint=fingerprint("version",expected,receiptId);CommandKey commandKey=key(caller,Action.VERSION_APPEND,id.toString(),key);
        // Current authorized replay precedes expiry/preflight. No network work is performed under the document transaction.
        CommandReceipt replayed=transactions.execute(repository->{repository.lockCommand(commandKey);repository.uploads().lockAccounts(caller.tenant());
            load(repository,caller,id,Action.VERSION_APPEND);var old=repository.command(commandKey);
            if(old.isPresent()){route(caller,Action.VERSION_APPEND,id.toString(),false);requireAcl(repository.find(caller.tenant(),id).orElseThrow(DocumentService::hidden),caller,Action.VERSION_APPEND);return replay(old.get(),fingerprint);}
            receipt(repository,caller,id,receiptId);return null;});
        if(replayed!=null)return replayed;
        var artifact=artifacts.verify(caller.actor(),caller.tenant(),id,receiptId);
        if(artifact==null)throw new DmFailure(503,"UNAVAILABLE");
        return mutate(caller,id,expected,key,Action.VERSION_APPEND,fingerprint,document->{
            var next=new ArrayList<>(document.versions());
            next.add(new DocumentVersion(UUID.randomUUID(),id,next.size()+1,artifact.sha256(),artifact.mimeType(),artifact.originalArtifactRef(),artifact.producer(),caller.principal(),now()));
            return document.next(document.metadata(),document.rules(),document.holds(),next);
        },201,()->{},(repository,changed)->{
            var receipt=receipt(repository,caller,id,receiptId);
            if(!receipt.sha256().equals(artifact.sha256())||!receipt.format().mime().equals(artifact.mimeType())||!receipt.receiptId().toString().equals(artifact.originalArtifactRef()))throw new DmFailure(503,"UNAVAILABLE");
            var u=repository.uploads();var slot=u.slot(caller.tenant(),receipt.uploadId()).orElseThrow(DocumentService::hidden);
            if(slot.state()!=UploadState.VERIFIED||!slot.actor().equals(caller.principal()))throw hidden();
            var accounts=u.lockAccounts(caller.tenant());long size=receipt.sizeBytes();long transientReleased=slot.transientReservation()-(slot.quarantineCleanupConfirmed()?0:size);
            u.consume(new ReceiptConsumption(caller.tenant(),receipt.receiptId(),id,changed.versions().getLast().versionId(),caller.principal(),key,fingerprint,now()));
            u.updateSlot(slot.consumed(),slot.revision());
            u.saveAccounts(caller.tenant(),accounts,new Accounts(accounts.global().change(-1,-transientReleased,-size,size),accounts.tenant().change(-1,-transientReleased,-size,size)));
        });
    }
    private UploadReceipt receipt(DocumentRepository repository,DocumentCaller caller,UUID id,String identifier) {
        UUID receiptId;try{receiptId=UUID.fromString(identifier);}catch(IllegalArgumentException bad){throw hidden();}
        var u=repository.uploads();var receipt=u.receipt(caller.tenant(),receiptId).orElseThrow(DocumentService::hidden);
        if(!receipt.tenant().equals(caller.tenant())||!receipt.documentId().equals(id)||!receipt.actor().equals(caller.principal()))throw hidden();
        if(u.consumption(caller.tenant(),receiptId).isPresent())throw new DmFailure(409,"RECEIPT_CONSUMED");
        if(!now().isBefore(receipt.expiresAt()))throw new DmFailure(409,"UPLOAD_EXPIRED");
        if(u.unresolvedFence(caller.tenant(),id).isPresent())throw new DmFailure(503,"UNAVAILABLE");
        var evidence=u.evidence(caller.tenant(),receipt.evidenceId()).orElseThrow(()->new DmFailure(503,"UNAVAILABLE"));
        if(!evidence.uploadId().equals(receipt.uploadId())||!evidence.inspection().sha256().equals(receipt.sha256())||evidence.inspection().sizeBytes()!=receipt.sizeBytes())throw new DmFailure(503,"UNAVAILABLE");
        return receipt;
    }
    private CommandReceipt mutate(DocumentCaller caller,UUID id,long expected,String key,Action action,String fingerprint,
            Function<DocumentAggregate,DocumentAggregate> update,int status) {
        return mutate(caller,id,expected,key,action,fingerprint,update,status,()->{});
    }
    private CommandReceipt mutate(DocumentCaller caller,UUID id,long expected,String key,Action action,String fingerprint,
            Function<DocumentAggregate,DocumentAggregate> update,int status,Runnable finalAuthority) {
        return mutate(caller,id,expected,key,action,fingerprint,update,status,finalAuthority,(repository,changed)->{});
    }
    private CommandReceipt mutate(DocumentCaller caller,UUID id,long expected,String key,Action action,String fingerprint,
            Function<DocumentAggregate,DocumentAggregate> update,int status,Runnable finalAuthority,BiConsumer<DocumentRepository,DocumentAggregate> afterUpdate) {
        route(caller,action,id.toString(),false);
        CommandKey commandKey=key(caller,action,id.toString(),key);
        return transactions.execute(repository->{
            repository.lockCommand(commandKey);
            if(action==Action.HOLD_PLACE||action==Action.VERSION_APPEND)repository.uploads().lockAccounts(caller.tenant());
            route(caller,action,id.toString(),false);
            DocumentAggregate document=load(repository,caller,id,action);
            var prior=repository.command(commandKey);
            if(prior.isPresent()){route(caller,action,id.toString(),false);requireAcl(repository.find(caller.tenant(),id).orElseThrow(DocumentService::hidden),caller,action);return replay(prior.get(),fingerprint);}
            if(action==Action.HOLD_PLACE&&repository.uploads().unresolvedFence(caller.tenant(),id).isPresent())throw new DmFailure(503,"UNAVAILABLE");
            if(expected!=document.revision())throw new DmFailure(409,"REVISION_CONFLICT");
            DocumentAggregate changed=update.apply(document);
            // Recheck operation/membership and original ACL before commit, allowing an authorized self-restriction.
            route(caller,action,id.toString(),false);
            requireAcl(repository.find(caller.tenant(),id).orElseThrow(DocumentService::hidden),caller,action);
            // Targets and ceiling can change while the aggregate is being prepared.
            finalAuthority.run();
            route(caller,action,id.toString(),false);
            requireAcl(repository.find(caller.tenant(),id).orElseThrow(DocumentService::hidden),caller,action);
            repository.update(changed,expected);
            afterUpdate.accept(repository,changed);
            return record(repository,commandKey,fingerprint,status,changed,action);
        });
    }
    private void requireDelegation(DocumentCaller caller,AclChange.Entry entry) {
        Actor target=new Actor(entry.principal().issuer(),entry.principal().subject());
        requireDecision(delegationDecision(()->delegation.targetGrant(target,caller.tenant(),entry.action())),422,"TARGET_NOT_GRANTABLE");
        requireDecision(delegationDecision(()->delegation.ceiling(caller.actor(),caller.tenant(),entry.action())),403,"DELEGATION_CEILING");
    }
    private DocumentAggregate load(DocumentRepository repository,DocumentCaller caller,UUID id,Action action) {
        DocumentAggregate document=repository.find(caller.tenant(),id).orElseThrow(DocumentService::hidden);
        requireAcl(document,caller,action);return document;
    }
    private static void requireAcl(DocumentAggregate document,DocumentCaller caller,Action action) {
        if(!caller.tenant().equals(document.tenant()) || !document.permits(caller.principal(),action))throw hidden();
    }
    private void route(DocumentCaller caller,Action action,String resource,boolean inspection) {
        requireDecision(caller.membership(),403,"TENANT_DENIED");
        requireDecision(decision(caller,action,resource),inspection?404:403,inspection?"NOT_FOUND":"OPERATION_DENIED");
    }
    private AuthorityDecision decision(DocumentCaller caller,Action action,String resource) {
        try {var answer=grants.authorize(caller.actor(),caller.tenant(),action.wire(),resource);if(answer==null)throw new IllegalStateException();if(answer==AuthorityDecision.UNAVAILABLE)throw new DmFailure(503,"UNAVAILABLE");return answer;}
        catch(DmFailure unavailable){throw unavailable;}catch(RuntimeException unavailable){throw new DmFailure(503,"UNAVAILABLE");}
    }
    private static AuthorityDecision delegationDecision(java.util.function.Supplier<AuthorityDecision> check){try{return check.get();}catch(RuntimeException failure){return AuthorityDecision.UNAVAILABLE;}}
    private static void requireDecision(AuthorityDecision decision,int deniedStatus,String deniedCode) {
        if(decision==AuthorityDecision.UNAVAILABLE || decision==null)throw new DmFailure(503,"UNAVAILABLE");
        if(decision!=AuthorityDecision.ALLOW)throw new DmFailure(deniedStatus,deniedCode);
    }
    private CommandReceipt record(DocumentRepository repository,CommandKey key,String fingerprint,int status,DocumentAggregate document,Action action) {
        CommandReceipt receipt=new CommandReceipt(document.documentId(),document.revision());
        repository.record(new StoredCommand(key,fingerprint,status,receipt),new AuditFact(UUID.randomUUID(),document.documentId(),document.tenant(),key.actor(),action.wire(),document.revision(),now()));
        return receipt;
    }
    private static CommandReceipt replay(StoredCommand stored,String fingerprint) {
        if(!stored.fingerprint().equals(fingerprint))throw new DmFailure(409,"IDEMPOTENCY_CONFLICT");
        return stored.receipt();
    }
    private static CommandKey key(DocumentCaller caller,Action action,String resource,String key) {
        if(key==null || !key.matches("[!-~]{1,128}"))throw new DmFailure(400,"INVALID_IDEMPOTENCY_KEY");
        return new CommandKey(caller.actor(),caller.tenant(),action.wire(),resource,key);
    }
    private java.time.Instant now(){return clock.instant().truncatedTo(ChronoUnit.MICROS);}
    private static DmFailure hidden(){return new DmFailure(404,"NOT_FOUND");}
    private static String fingerprint(Object... fields) {
        try {
            var bytes=new ByteArrayOutputStream();
            try(var data=new DataOutputStream(bytes)) {for(Object field:fields){byte[] encoded=field.toString().getBytes(StandardCharsets.UTF_8);data.writeInt(encoded.length);data.write(encoded);}}
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        }catch(java.io.IOException | java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
