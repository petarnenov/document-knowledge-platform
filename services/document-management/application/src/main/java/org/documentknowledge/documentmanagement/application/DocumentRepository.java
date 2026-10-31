package org.documentknowledge.documentmanagement.application;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.documentknowledge.documentmanagement.domain.*;
/** Each instance is bound to one transaction. Implementations must scope SQL by tenant. */
public interface DocumentRepository {
 void lockCommand(CommandKey key);
 Optional<StoredCommand> command(CommandKey key);
 Optional<DocumentAggregate> find(String tenant,UUID id);
 List<DocumentAggregate> listVisible(String tenant,Principal actor,Action action,PagePosition after,int limit);
 void insert(DocumentAggregate document);
 void update(DocumentAggregate document,long expectedRevision);
 void record(StoredCommand command,AuditFact audit);
}
