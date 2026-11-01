package org.documentknowledge.knowledgebase.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.documentknowledge.knowledgebase.domain.KbModels.*;
import org.documentknowledge.knowledgebase.domain.KbModels.Collection;

/** One bounded KB-owned transaction; every lookup and write includes selected tenant. */
public interface CollectionRepository {
    long tenantFence(String tenant);
    Optional<Collection> collection(String tenant,UUID collectionId);
    List<Collection> visibleCollections(Context context,UUID after,int limit);
    List<Membership> members(String tenant,UUID collectionId);
    List<Membership> memberships(String tenant,UUID documentId);
    Scope scope(String tenant,UUID documentId);
    Optional<CommandOutcome> command(CommandKey key);
    Optional<Snapshot> snapshot(String tokenHash,Context context,UUID documentId);
    void insertCollection(Collection collection);
    void saveCollection(Collection collection,long expectedRevision);
    void saveMembership(Membership membership,Long expectedRevision,Principal actor,java.time.Instant occurredAt);
    void saveScope(Scope scope,long expectedRevision);
    void advanceTenantFence(String tenant,long expectedRevision);
    /** INSERT only, tenant fence locked; cap10000/tenant returns429 without eviction. */
    void saveSnapshot(Snapshot snapshot);
    void record(CommandOutcome outcome,Fact audit,Fact outbox);
}
