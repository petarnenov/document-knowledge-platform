package org.documentknowledge.documentmanagement.adapters.outbound.persistence;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.documentmanagement.domain.*;

/** Runtime-only datasource. Migration credentials must never be passed to this adapter. */
public final class JdbcDocumentStore implements DocumentUnitOfWork {
    private final DataSource source;
    public JdbcDocumentStore(DataSource source) { this.source = Objects.requireNonNull(source); }
    @Override public <T> T execute(Function<DocumentRepository,T> work) {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET LOCAL statement_timeout = '10s'");
                    statement.execute("SET LOCAL lock_timeout = '10s'");
                }
                T result = work.apply(new Repository(connection));
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw unavailable((SQLException) failure);
            }
        } catch (SQLException failure) { throw unavailable(failure); }
    }
    private static DmFailure unavailable(SQLException failure) {
        // Never disclose SQL text, credentials, table names or driver diagnostics over HTTP.
        return new DmFailure(503, "UNAVAILABLE");
    }
    private static final class Repository implements DocumentRepository {
        private final Connection connection;
        Repository(Connection connection) { this.connection = connection; }
        @Override public org.documentknowledge.documentmanagement.application.upload.UploadRepository uploads() { return new JdbcUploadRepository(connection); }
        private PreparedStatement prepare(String sql, Object... values) throws SQLException {
            PreparedStatement statement = connection.prepareStatement(sql);
            try {
                for (int i=0; i<values.length; i++) {
                    Object value = values[i];
                    if (value instanceof Instant instant) statement.setTimestamp(i+1, Timestamp.from(instant));
                    else statement.setObject(i+1, value);
                }
                return statement;
            } catch (SQLException failure) { statement.close(); throw failure; }
        }
        private int change(String sql, Object... values) {
            try (PreparedStatement statement = prepare(sql, values)) { return statement.executeUpdate(); }
            catch (SQLException failure) { throw unavailable(failure); }
        }
        private Object[] key(CommandKey key) { return new Object[]{key.tenant(),key.actor().issuer(),key.actor().subject(),key.operation(),key.resource(),key.idempotencyKey()}; }
        private static final String KEY_WHERE = "tenant_id=? AND actor_issuer=? AND actor_subject=? AND operation=? AND resource_scope=? AND idempotency_key=?";
        @Override public void lockCommand(CommandKey key) {
            // Length-prefixed encoding avoids delimiter ambiguity. Hash collisions only serialize extra work.
            StringBuilder scoped = new StringBuilder();
            for (Object part : key(key)) { String text = part.toString(); scoped.append(text.length()).append(':').append(text); }
            try (PreparedStatement statement = prepare("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", scoped.toString())) { statement.execute(); }
            catch (SQLException failure) { throw unavailable(failure); }
        }
        @Override public Optional<StoredCommand> command(CommandKey key) {
            try (PreparedStatement statement = prepare("SELECT body_hash,status,document_id,revision FROM dm.command_outcomes WHERE "+KEY_WHERE,key(key)); ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(new StoredCommand(key,rows.getString(1),rows.getInt(2),new CommandReceipt(rows.getObject(3,UUID.class),rows.getLong(4)))) : Optional.empty();
            } catch (SQLException failure) { throw unavailable(failure); }
        }
        @Override public Optional<DocumentAggregate> find(String tenant, UUID id) {
            try (PreparedStatement statement = prepare("SELECT * FROM dm.documents WHERE tenant_id=? AND document_id=? FOR UPDATE",tenant,id); ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            } catch (SQLException failure) { throw unavailable(failure); }
        }
        @Override public List<DocumentAggregate> listVisible(String tenant, Principal actor, Action action, PagePosition after, int limit) {
            if (limit<1 || limit>50) throw new IllegalArgumentException("Invalid page size");
            String predicate = " FROM dm.documents d WHERE d.tenant_id=? AND EXISTS (SELECT 1 FROM dm.acl_rules a WHERE a.tenant_id=d.tenant_id AND a.document_id=d.document_id AND a.principal_issuer=? AND a.principal_subject=? AND a.action=? AND a.effect='ALLOW') AND NOT EXISTS (SELECT 1 FROM dm.acl_rules a WHERE a.tenant_id=d.tenant_id AND a.document_id=d.document_id AND a.principal_issuer=? AND a.principal_subject=? AND a.action=? AND a.effect='DENY')";
            List<Object> args = new ArrayList<>(List.of(tenant,actor.issuer(),actor.subject(),action.wire(),actor.issuer(),actor.subject(),action.wire()));
            if (after != null) { predicate += " AND (d.created_at,d.document_id)>(?,?)"; args.add(after.createdAt()); args.add(after.documentId()); }
            args.add(limit);
            List<DocumentAggregate> result = new ArrayList<>();
            try (PreparedStatement statement = prepare("SELECT d.*"+predicate+" ORDER BY d.created_at,d.document_id LIMIT ? FOR UPDATE OF d",args.toArray()); ResultSet rows = statement.executeQuery()) {
                while(rows.next()) result.add(read(rows));
                return List.copyOf(result);
            } catch (SQLException failure) { throw unavailable(failure); }
        }
        private DocumentAggregate read(ResultSet row) throws SQLException {
            String tenant = row.getString("tenant_id"); UUID id = row.getObject("document_id",UUID.class);
            Set<AclRule> rules = new HashSet<>(); List<LegalHold> holds = new ArrayList<>(); List<DocumentVersion> versions = new ArrayList<>();
            try (PreparedStatement statement=prepare("SELECT * FROM dm.acl_rules WHERE tenant_id=? AND document_id=?",tenant,id); ResultSet rows=statement.executeQuery()) {
                while(rows.next()) rules.add(new AclRule(new Principal(rows.getString("principal_issuer"),rows.getString("principal_subject")),Action.parse(rows.getString("action")),AclRule.Effect.valueOf(rows.getString("effect"))));
            }
            try (PreparedStatement statement=prepare("SELECT * FROM dm.legal_holds WHERE tenant_id=? AND document_id=? ORDER BY placed_at,hold_id",tenant,id); ResultSet rows=statement.executeQuery()) {
                while(rows.next()) holds.add(new LegalHold(rows.getObject("hold_id",UUID.class),rows.getString("reason"),new Principal(rows.getString("actor_issuer"),rows.getString("actor_subject")),rows.getTimestamp("placed_at").toInstant()));
            }
            try (PreparedStatement statement=prepare("SELECT * FROM dm.versions WHERE tenant_id=? AND document_id=? ORDER BY version_number",tenant,id); ResultSet rows=statement.executeQuery()) {
                while(rows.next()) versions.add(new DocumentVersion(rows.getObject("version_id",UUID.class),id,rows.getInt("version_number"),rows.getString("sha256"),rows.getString("mime_type"),rows.getString("artifact_reference"),rows.getString("producer"),new Principal(rows.getString("creator_issuer"),rows.getString("creator_subject")),rows.getTimestamp("created_at").toInstant(),rows.getString("classification")));
            }
            return new DocumentAggregate(id,tenant,new Principal(row.getString("creator_issuer"),row.getString("creator_subject")),row.getTimestamp("created_at").toInstant(),row.getLong("revision"),new Metadata(row.getString("title"),row.getString("description")),row.getObject("active_version_id",UUID.class),rules,holds,versions);
        }
        @Override public void insert(DocumentAggregate document) {
            change("INSERT INTO dm.documents (tenant_id,document_id,revision,created_at,creator_issuer,creator_subject,title,description,lifecycle,classification,classification_revision,active_version_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",document.tenant(),document.documentId(),document.revision(),document.createdAt(),document.creator().issuer(),document.creator().subject(),document.metadata().title(),document.metadata().description(),document.lifecycle(),document.classification(),document.classificationPolicyRevision(),document.activeVersionId());
            change("INSERT INTO dm.retention VALUES (?,?, 'development-unresolved',1,'UNRESOLVED',NULL,false)",document.tenant(),document.documentId());
            children(document, Set.of(), Set.of());
        }
        @Override public void update(DocumentAggregate document,long expectedRevision) {
            DocumentAggregate previous=find(document.tenant(),document.documentId()).orElseThrow(()->new DmFailure(404,"NOT_FOUND"));
            if(previous.revision()!=expectedRevision || document.revision()!=expectedRevision+1) throw new DmFailure(409,"REVISION_CONFLICT");
            if(!Objects.equals(previous.activeVersionId(),document.activeVersionId()) || !previous.creator().equals(document.creator()) || !previous.createdAt().equals(document.createdAt()) || !document.versions().containsAll(previous.versions()) || !document.holds().containsAll(previous.holds())) throw new IllegalArgumentException("Immutable facts changed");
            if(change("UPDATE dm.documents SET title=?,description=?,revision=? WHERE tenant_id=? AND document_id=? AND revision=?",document.metadata().title(),document.metadata().description(),document.revision(),document.tenant(),document.documentId(),expectedRevision)!=1) throw new DmFailure(409,"REVISION_CONFLICT");
            change("DELETE FROM dm.acl_rules WHERE tenant_id=? AND document_id=?",document.tenant(),document.documentId());
            children(document,new HashSet<>(previous.holds()),new HashSet<>(previous.versions()));
        }
        private void children(DocumentAggregate document,Set<LegalHold> existingHolds,Set<DocumentVersion> existingVersions) {
            for(AclRule rule:document.rules()) change("INSERT INTO dm.acl_rules VALUES (?,?,?,?,?,?)",document.tenant(),document.documentId(),rule.principal().issuer(),rule.principal().subject(),rule.action().wire(),rule.effect().name());
            for(LegalHold hold:document.holds()) if(!existingHolds.contains(hold)) change("INSERT INTO dm.legal_holds VALUES (?,?,?,?,?,?,?)",document.tenant(),document.documentId(),hold.holdId(),hold.reason(),hold.placedAt(),hold.placedBy().issuer(),hold.placedBy().subject());
            for(DocumentVersion version:document.versions()) if(!existingVersions.contains(version)) change("INSERT INTO dm.versions VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",document.tenant(),document.documentId(),version.versionId(),version.versionNumber(),version.sha256(),version.mimeType(),version.originalArtifactRef(),version.producer(),version.creator().issuer(),version.creator().subject(),version.createdAt(),version.submittedClassification(),version.initialReviewState());
        }
        @Override public void record(StoredCommand command,AuditFact audit) {
            CommandKey key=command.key();
            change("INSERT INTO dm.command_outcomes VALUES (?,?,?,?,?,?,?,?,?,?)",key.tenant(),key.actor().issuer(),key.actor().subject(),key.operation(),key.resource(),key.idempotencyKey(),command.fingerprint(),command.receipt().documentId(),command.receipt().revision(),command.status());
            change("INSERT INTO dm.audit_facts (event_id,tenant_id,document_id,revision,operation,actor_issuer,actor_subject,occurred_at) VALUES (?,?,?,?,?,?,?,?)",audit.eventId(),audit.tenant(),audit.documentId(),audit.revision(),audit.action(),audit.actor().issuer(),audit.actor().subject(),audit.occurredAt());
        }
    }
}
