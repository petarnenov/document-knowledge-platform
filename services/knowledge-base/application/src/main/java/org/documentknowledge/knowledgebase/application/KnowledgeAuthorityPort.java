package org.documentknowledge.knowledgebase.application;

import org.documentknowledge.knowledgebase.domain.KbModels.*;

/** Current trusted operation grants. No actor claims or owner exemptions. */
public interface KnowledgeAuthorityPort {
    enum Result { ALLOW, DENY, UNAVAILABLE }
    Result authorize(Context context,Action action);
    /** Explicit current delegation ceiling, separate from an ordinary operation grant.
     * Implementations used inside final local transactions must perform bounded local reads,
     * never external HTTP. Unimplemented ceiling capability fails closed.
     */
    default Result delegationCeiling(Context context,Action action) { return Result.UNAVAILABLE; }
}
