package org.documentknowledge.knowledgebase.application;

import java.util.List;
import java.util.UUID;
import org.documentknowledge.knowledgebase.domain.KbModels.Context;
import org.documentknowledge.knowledgebase.application.DocumentShareBatchAuthorityPort.Assessment;

public interface DocumentShareEligibilityPort {
    record ShareEligibilityResult(Context context,String action,String requestedSetDigest,List<Assessment> eligible) {
        public ShareEligibilityResult { eligible=List.copyOf(eligible); }
    }
    ShareEligibilityResult discover(Context context,List<UUID> documents,Deadline deadline);
}
