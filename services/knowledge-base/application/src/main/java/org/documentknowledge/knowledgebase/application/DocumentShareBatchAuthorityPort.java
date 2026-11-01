package org.documentknowledge.knowledgebase.application;

import java.util.List;
import java.util.UUID;
import org.documentknowledge.knowledgebase.domain.KbModels.Context;

/** Strict evidence cannot be constructed from an eligibility result. */
public interface DocumentShareBatchAuthorityPort {
    record Expectation(UUID documentId,Long expectedDocumentRevision) { }
    record Assessment(UUID documentId,long documentRevision,String classification,long classificationPolicyRevision) { }
    record ShareBatchAssessment(Context context,String action,List<Assessment> assessments) {
        public ShareBatchAssessment { assessments=List.copyOf(assessments); }
    }
    ShareBatchAssessment assess(Context context,List<Expectation> documents,Deadline deadline);
}
