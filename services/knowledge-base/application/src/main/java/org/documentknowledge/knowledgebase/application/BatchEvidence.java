package org.documentknowledge.knowledgebase.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.documentknowledge.knowledgebase.domain.KbModels.Context;
import org.documentknowledge.knowledgebase.domain.KbModels;
import org.documentknowledge.knowledgebase.application.DocumentShareBatchAuthorityPort.*;
import org.documentknowledge.knowledgebase.application.DocumentShareEligibilityPort.ShareEligibilityResult;

/** Validate transport binding independently of deserialization and authority adapter implementation. */
public final class BatchEvidence {
    public static final String ACTION="document.share-to-collection";
    private BatchEvidence() { }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static String requestedDigest(List<UUID> ids) { return hash("["+String.join(",",ids.stream().sorted(Comparator.comparing(UUID::toString)).map(id->"\""+id+"\"").toList())+"]"); }
    public static String viewDigest(List<Assessment> rows) {
        return hash("["+String.join(",",rows.stream().map(a->"[\""+a.documentId()+"\","+a.documentRevision()+",\""+a.classification()+"\","+a.classificationPolicyRevision()+"]").toList())+"]");
    }
    public static List<Assessment> strict(Context context,List<Expectation> expected,ShareBatchAssessment result) {
        if(result==null || !context.equals(result.context()) || !ACTION.equals(result.action())) throw KbFailure.unavailable();
        List<Assessment> rows=validate(expected.stream().map(Expectation::documentId).toList(),result.assessments());
        if(rows.size()!=expected.size()) throw KbFailure.unavailable();
        for(Expectation e:expected) if(e.expectedDocumentRevision()!=null && rows.stream().filter(a->a.documentId().equals(e.documentId())).findFirst().orElseThrow(KbFailure::unavailable).documentRevision()!=e.expectedDocumentRevision()) throw KbFailure.stale();
        return rows;
    }
    public static List<Assessment> eligibility(Context context,List<UUID> ids,ShareEligibilityResult result) {
        if(result==null || !context.equals(result.context()) || !ACTION.equals(result.action()) || !requestedDigest(ids).equals(result.requestedSetDigest())) throw KbFailure.unavailable();
        return validate(ids,result.eligible());
    }
    private static List<Assessment> validate(List<UUID> ids,List<Assessment> rows) {
        if(ids.isEmpty()||ids.size()>100||new HashSet<>(ids).size()!=ids.size()||rows==null||rows.size()>ids.size()) throw KbFailure.unavailable();
        String previous="";
        for(Assessment a:rows) {
            if(a==null||a.documentId()==null||!ids.contains(a.documentId())||a.documentId().toString().compareTo(previous)<=0||a.documentRevision()<0||a.documentRevision()>KbModels.MAX_REVISION||!"development-restricted".equals(a.classification())||a.classificationPolicyRevision()!=1) throw KbFailure.unavailable();
            previous=a.documentId().toString();
        }
        return List.copyOf(rows);
    }
}
