package org.documentknowledge.documentmanagement.application.sharing;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.documentknowledge.identity.*;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.documentmanagement.domain.*;
import org.documentknowledge.documentmanagement.application.sharing.SharingModels.*;
import org.documentknowledge.documentmanagement.application.sharing.SharingModels.Document;

/** Complete immutable authority reads at BOTH boundaries; eligibility is never strict evidence. */
public final class DocumentShareService {
    public static final String ACTION="document.share-to-collection";
    private static final long MAX_REVISION=9_007_199_254_740_991L;
    private final DocumentShareStore store;
    private final RequestScopedResourceAuthorityPort authority;
    private final SharingAdmission admission;
    public DocumentShareService(DocumentShareStore store,RequestScopedResourceAuthorityPort authority,SharingAdmission admission) { this.store=store;this.authority=authority;this.admission=admission; }
    public ShareBatchAssessment assess(DocumentCaller caller,List<Expectation> expectations,SharingDeadline deadline) {
        List<UUID> ids=validate(expectations);membership(caller);deadline.check();
        try(var permit=admission.acquire(caller.actor())) {
            var first=snapshot(caller,ids,deadline);
            return store.locked(caller.tenant(),ids,deadline,documents->{
                authorized(caller,ids,documents,first,true);
                var last=snapshot(caller,ids,deadline);
                List<Assessment> current=authorized(caller,ids,documents,last,true);
                membership(caller);deadline.check();
                if(!first.fingerprint().equals(last.fingerprint()))throw new DmFailure(409,"STALE_AUTHORITY");
                for(Expectation expectation:expectations) if(expectation.expectedDocumentRevision()!=null&&current.stream().filter(a->a.documentId().equals(expectation.documentId())).findFirst().orElseThrow(DocumentShareService::unavailable).documentRevision()!=expectation.expectedDocumentRevision())throw new DmFailure(409,"STALE_POLICY");
                return new ShareBatchAssessment(caller.actor(),caller.tenant(),ACTION,current);
            });
        }
    }
    public ShareEligibilityResult discover(DocumentCaller caller,List<UUID> documents,SharingDeadline deadline) {
        if(documents==null)throw new DmFailure(400,"INVALID_REQUEST");
        List<UUID> ids=validate(documents.stream().map(id->new Expectation(id,null)).toList());membership(caller);deadline.check();
        try(var permit=admission.acquire(caller.actor())) {
            var first=snapshot(caller,ids,deadline);
            return store.locked(caller.tenant(),ids,deadline,rows->{
                authorized(caller,ids,rows,first,false);
                var last=snapshot(caller,ids,deadline);
                List<Assessment> eligible=authorized(caller,ids,rows,last,false);
                membership(caller);deadline.check();
                if(!first.fingerprint().equals(last.fingerprint()))throw new DmFailure(409,"STALE_AUTHORITY");
                return new ShareEligibilityResult(caller.actor(),caller.tenant(),ACTION,requestedSetDigest(ids),eligible);
            });
        }
    }
    private RequestScopedResourceAuthorityPort.Snapshot snapshot(DocumentCaller caller,List<UUID> ids,SharingDeadline deadline) {
        deadline.check();List<String> resources=ids.stream().map(UUID::toString).toList();
        RequestScopedResourceAuthorityPort.Snapshot snapshot;
        try { snapshot=authority.snapshot(caller.actor(),caller.tenant(),ACTION,resources); }
        catch(RuntimeException failure) { throw unavailable(); }
        deadline.check();
        if(snapshot==null||!caller.actor().equals(snapshot.actor())||!caller.tenant().equals(snapshot.tenant())||!ACTION.equals(snapshot.action())||!resources.equals(snapshot.resources())||!snapshot.decisions().keySet().equals(new HashSet<>(resources)))throw unavailable();
        decision(snapshot.route());
        if(!snapshot.fingerprint().matches("[0-9a-f]{64}"))throw unavailable();
        for(var result:snapshot.decisions().values())if(result==null||result==AuthorityDecision.UNAVAILABLE)throw unavailable();
        return snapshot;
    }
    private List<Assessment> authorized(DocumentCaller caller,List<UUID> ids,List<Document> rows,RequestScopedResourceAuthorityPort.Snapshot snapshot,boolean strict) {
        if(rows==null||rows.size()>ids.size())throw unavailable();
        Map<UUID,Document> found=new HashMap<>();
        for(Document document:rows) if(document==null||!caller.tenant().equals(document.tenant())||!ids.contains(document.documentId())||found.putIfAbsent(document.documentId(),document)!=null)throw unavailable();
        List<Document> permitted=new ArrayList<>();
        for(UUID id:ids) {
            Document document=found.get(id);
            boolean allowed=document!=null&&snapshot.decisions().get(id.toString())==AuthorityDecision.ALLOW&&document.rules().contains(new AclRule(caller.principal(),Action.SHARE_TO_COLLECTION,AclRule.Effect.ALLOW))&&!document.rules().contains(new AclRule(caller.principal(),Action.SHARE_TO_COLLECTION,AclRule.Effect.DENY));
            if(!allowed) { if(strict)throw new DmFailure(404,"NOT_FOUND"); }
            else permitted.add(document);
        }
        List<Assessment> result=new ArrayList<>();
        for(Document document:permitted) {
            if(document.revision()<0||document.revision()>MAX_REVISION||!"development-restricted".equals(document.classification())||document.classificationPolicyRevision()!=1)throw unavailable();
            result.add(new Assessment(document.documentId(),document.revision(),document.classification(),document.classificationPolicyRevision()));
        }
        return List.copyOf(result);
    }
    private static void membership(DocumentCaller caller) {
        AuthorityDecision result=caller.membership();
        if(result==AuthorityDecision.UNAVAILABLE)throw unavailable();
        if(result!=AuthorityDecision.ALLOW)throw new DmFailure(403,"TENANT_DENIED");
    }
    private static void decision(AuthorityDecision result) { if(result==AuthorityDecision.UNAVAILABLE)throw unavailable();if(result!=AuthorityDecision.ALLOW)throw new DmFailure(404,"NOT_FOUND"); }
    private static List<UUID> validate(List<Expectation> expectations) {
        if(expectations==null||expectations.isEmpty()||expectations.size()>100)throw new DmFailure(400,"INVALID_REQUEST");
        Set<UUID> ids=new HashSet<>();
        for(Expectation expectation:expectations) {
            if(expectation==null||expectation.documentId()==null||expectation.expectedDocumentRevision()!=null&&(expectation.expectedDocumentRevision()<0||expectation.expectedDocumentRevision()>MAX_REVISION))throw new DmFailure(400,"INVALID_REQUEST");
            if(!ids.add(expectation.documentId()))throw new DmFailure(422,"DUPLICATE_DOCUMENT");
        }
        return ids.stream().sorted(Comparator.comparing(UUID::toString)).toList();
    }
    public static String requestedSetDigest(List<UUID> documents) {
        String json="["+String.join(",",documents.stream().sorted(Comparator.comparing(UUID::toString)).map(id->"\""+id+"\"").toList())+"]";
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw unavailable(); }
    }
    private static DmFailure unavailable() { return new DmFailure(503,"UNAVAILABLE"); }
}
