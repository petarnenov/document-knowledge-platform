package org.documentknowledge.documentmanagement.application.sharing;

import java.util.*;
import org.documentknowledge.identity.Actor;
import org.documentknowledge.documentmanagement.domain.AclRule;

public final class SharingModels {
    private SharingModels() { }
    public record Expectation(UUID documentId,Long expectedDocumentRevision) { }
    /** Actual persisted metadata, independently of legacy aggregate computed classification constants. */
    public record Document(UUID documentId,String tenant,long revision,String classification,long classificationPolicyRevision,Set<AclRule> rules) {
        public Document { rules=Set.copyOf(rules); }
    }
    public record Assessment(UUID documentId,long documentRevision,String classification,long classificationPolicyRevision) { }
    public record ShareBatchAssessment(Actor actor,String tenant,String action,List<Assessment> assessments) {
        public ShareBatchAssessment { assessments=List.copyOf(assessments); }
    }
    public record ShareEligibilityResult(Actor actor,String tenant,String action,String requestedSetDigest,List<Assessment> eligible) {
        public ShareEligibilityResult { eligible=List.copyOf(eligible); }
    }
}
