package org.documentknowledge.knowledgebase.application;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.documentknowledge.knowledgebase.domain.KbModels.CommandKey;

public interface KBUnitOfWork {
    record LockPlan(String tenant,List<UUID> collectionIds,List<UUID> documentIds,CommandKey command) {
        public LockPlan { collectionIds=collectionIds.stream().distinct().sorted(java.util.Comparator.comparing(UUID::toString)).toList(); documentIds=documentIds.stream().distinct().sorted(java.util.Comparator.comparing(UUID::toString)).toList(); }
    }
    /** Coherent repeatable-read snapshot, connection released before caller performs HTTP. */
    <T> T read(Deadline deadline,Function<CollectionRepository,T> operation);
    /** Tenant fence, sorted collection/scopes, then exact command lock; no remote work inside. */
    <T> T execute(LockPlan locks,Deadline deadline,Function<CollectionRepository,T> operation);
}
