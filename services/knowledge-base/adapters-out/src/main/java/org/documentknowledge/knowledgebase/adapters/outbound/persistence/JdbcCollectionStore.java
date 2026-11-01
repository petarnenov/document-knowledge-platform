package org.documentknowledge.knowledgebase.adapters.outbound.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.documentknowledge.knowledgebase.application.*;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.documentknowledge.knowledgebase.domain.KbModels.*;
import org.documentknowledge.knowledgebase.domain.KbModels.Collection;

/** Only the KB runtime datasource belongs here. No network authority or migration credentials. */
public final class JdbcCollectionStore implements KBUnitOfWork {
    private final DataSource source;
    public JdbcCollectionStore(DataSource source){this.source=Objects.requireNonNull(source);}
    @Override public <T>T read(Deadline deadline,Function<CollectionRepository,T> operation){return transaction(null,deadline,operation);}
    @Override public <T>T execute(LockPlan plan,Deadline deadline,Function<CollectionRepository,T> operation){return transaction(Objects.requireNonNull(plan),deadline,operation);}
    private <T>T transaction(LockPlan plan,Deadline supplied,Function<CollectionRepository,T> operation){
        Deadline deadline=Objects.requireNonNull(supplied).child(Duration.ofMillis(500));deadline.check();
        try(Connection connection=source.getConnection()){
            deadline.check();connection.setAutoCommit(false);
            connection.setTransactionIsolation(plan==null?Connection.TRANSACTION_REPEATABLE_READ:Connection.TRANSACTION_READ_COMMITTED);
            connection.setReadOnly(plan==null);
            try{
                var repository=new Repository(connection,deadline,plan==null?null:plan.tenant());
                if(plan!=null)repository.lock(plan);
                T result=operation.apply(repository);repository.budget();deadline.check();
                // A lost COMMIT response is UNKNOWN. Never retry with a new key or erase durable facts.
                connection.commit();deadline.check();return result;
            }catch(RuntimeException|SQLException failure){
                try{connection.rollback();}catch(SQLException ignored){/* Rollback failure does not prove an unknown commit absent. */}
                if(failure instanceof KbFailure known)throw known;
                throw KbFailure.unavailable();
            }
        }catch(SQLException failure){throw KbFailure.unavailable();}
    }
    private static final class Repository implements CollectionRepository {
        private final Connection connection;private final Deadline deadline;private final boolean readOnly;private final String lockedTenant;
        Repository(Connection connection,Deadline deadline,String lockedTenant){this.connection=connection;this.deadline=deadline;this.lockedTenant=lockedTenant;this.readOnly=lockedTenant==null;}
        private void writeTenant(String tenant){if(readOnly||!lockedTenant.equals(tenant))throw KbFailure.unavailable();}
        void budget()throws SQLException{
            long remaining=Math.max(1,deadline.remainingNanos()/1_000_000);int milliseconds=(int)Math.min(500,remaining);
            connection.setNetworkTimeout(Runnable::run,milliseconds);
            try(var statement=connection.prepareStatement("SELECT set_config('statement_timeout',?,true),set_config('lock_timeout',?,true)")){
                statement.setString(1,milliseconds+"ms");statement.setString(2,Math.min(250,milliseconds)+"ms");statement.execute();
            }
            deadline.check();
        }
        private PreparedStatement prepare(String sql,Object...values)throws SQLException{
            budget();var statement=connection.prepareStatement(sql);
            try{for(int i=0;i<values.length;i++){var value=values[i];if(value instanceof Instant instant)statement.setTimestamp(i+1,Timestamp.from(instant));else if(value instanceof byte[] bytes)statement.setBytes(i+1,bytes);else statement.setObject(i+1,value);}return statement;}
            catch(SQLException failure){statement.close();throw failure;}
        }
        private int change(String sql,Object...values){
            if(readOnly)throw KbFailure.unavailable();
            try(var statement=prepare(sql,values)){return statement.executeUpdate();}catch(SQLException failure){throw KbFailure.unavailable();}
        }
        private long number(String sql,Object...values){try(var statement=prepare(sql,values);var rows=statement.executeQuery()){return rows.next()?rows.getLong(1):0;}catch(SQLException failure){throw KbFailure.unavailable();}}
        void lock(LockPlan plan){
            if(plan.collectionIds().size()>100||plan.documentIds().size()>100)throw KbFailure.unavailable();
            if(plan.command()!=null&&!plan.tenant().equals(plan.command().context().tenant()))throw KbFailure.unavailable();
            change("INSERT INTO kb.tenant_policy_fences VALUES (?,0) ON CONFLICT DO NOTHING",plan.tenant());
            number("SELECT revision FROM kb.tenant_policy_fences WHERE tenant_id=? FOR UPDATE",plan.tenant());
            lockIds("knowledge_collections","collection_id",plan.tenant(),plan.collectionIds());
            if(!plan.documentIds().isEmpty()){
                var values=new ArrayList<Object>();StringJoiner tuples=new StringJoiner(",");
                for(UUID id:plan.documentIds()){tuples.add("(?,?,0)");values.add(plan.tenant());values.add(id);}
                change("INSERT INTO kb.document_membership_scopes VALUES "+tuples+" ON CONFLICT DO NOTHING",values.toArray());
                lockIds("document_membership_scopes","document_id",plan.tenant(),plan.documentIds());
            }
            if(plan.command()!=null){
                StringBuilder key=new StringBuilder();for(Object value:key(plan.command())){String text=value.toString();key.append(text.length()).append(':').append(text);}
                try(var statement=prepare("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",key.toString())){statement.execute();}catch(SQLException failure){throw KbFailure.unavailable();}
            }
        }
        private void lockIds(String table,String column,String tenant,List<UUID> ids){
            if(ids.isEmpty())return;
            var args=new ArrayList<Object>();args.add(tenant);args.addAll(ids);
            String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
            try(var statement=prepare("SELECT "+column+" FROM kb."+table+" WHERE tenant_id=? AND "+column+" IN ("+placeholders+") ORDER BY "+column+" FOR UPDATE",args.toArray());var rows=statement.executeQuery()){while(rows.next())deadline.check();}catch(SQLException failure){throw KbFailure.unavailable();}
        }
        @Override public long tenantFence(String tenant){return number("SELECT revision FROM kb.tenant_policy_fences WHERE tenant_id=?",tenant);}
        @Override public Optional<Collection> collection(String tenant,UUID id){
            try(var statement=prepare("SELECT * FROM kb.knowledge_collections WHERE tenant_id=? AND collection_id=?",tenant,id);var rows=statement.executeQuery()){return rows.next()?Optional.of(collection(rows)):Optional.empty();}catch(SQLException failure){throw KbFailure.unavailable();}
        }
        @Override public List<Collection> visibleCollections(Context context,UUID after,int limit){
            if(limit<1||limit>51)throw new IllegalArgumentException("Invalid collection page");
            String sql="SELECT c.* FROM kb.knowledge_collections c WHERE c.tenant_id=? AND EXISTS (SELECT 1 FROM kb.collection_acl_rules a WHERE a.tenant_id=c.tenant_id AND a.collection_id=c.collection_id AND a.principal_issuer=? AND a.principal_subject=? AND a.action='knowledge.collection.inspect' AND a.effect='ALLOW') AND NOT EXISTS (SELECT 1 FROM kb.collection_acl_rules a WHERE a.tenant_id=c.tenant_id AND a.collection_id=c.collection_id AND a.principal_issuer=? AND a.principal_subject=? AND a.action='knowledge.collection.inspect' AND a.effect='DENY')";
            var args=new ArrayList<Object>(List.of(context.tenant(),context.actor().issuer(),context.actor().subject(),context.actor().issuer(),context.actor().subject()));
            if(after!=null){sql+=" AND c.collection_id>?";args.add(after);}args.add(limit);
            var result=new ArrayList<Collection>();
            try(var statement=prepare(sql+" ORDER BY c.collection_id LIMIT ?",args.toArray());var rows=statement.executeQuery()){while(rows.next())result.add(collection(rows));return List.copyOf(result);}catch(SQLException failure){throw KbFailure.unavailable();}
        }
        private Collection collection(ResultSet row)throws SQLException{
            classification(row);
            String tenant=row.getString("tenant_id");UUID id=row.getObject("collection_id",UUID.class);long policyRevision=row.getLong("audience_policy_revision");
            var rules=new HashSet<AclRule>();try(var statement=prepare("SELECT * FROM kb.collection_acl_rules WHERE tenant_id=? AND collection_id=?",tenant,id);var rows=statement.executeQuery()){while(rows.next()){if(rules.size()>=100)throw KbFailure.unavailable();rules.add(new AclRule(new Principal(rows.getString("principal_issuer"),rows.getString("principal_subject")),action(rows.getString("action")),Effect.valueOf(rows.getString("effect"))));}}
            Instant effective;try(var statement=prepare("SELECT effective_at,classification,classification_policy_revision FROM kb.audience_policies WHERE tenant_id=? AND collection_id=? AND policy_revision=?",tenant,id,policyRevision);var rows=statement.executeQuery()){if(!rows.next())throw KbFailure.unavailable();classification(rows);effective=rows.getTimestamp(1).toInstant();}
            var audience=new HashSet<AudienceRule>();try(var statement=prepare("SELECT * FROM kb.audience_rules WHERE tenant_id=? AND collection_id=? AND policy_revision=? ORDER BY rule_number",tenant,id,policyRevision);var rows=statement.executeQuery()){while(rows.next()){if(audience.size()>=100)throw KbFailure.unavailable();var kind=SelectorKind.valueOf(rows.getString("selector_kind"));audience.add(new AudienceRule(Effect.valueOf(rows.getString("effect")),new Selector(kind,rows.getString("selector_issuer"),rows.getString(kind==SelectorKind.PRINCIPAL?"selector_subject":"selector_id"))));}}
            return new Collection(id,tenant,row.getLong("aggregate_revision"),new Metadata(row.getString("title"),row.getString("description")),Status.valueOf(row.getString("status")),row.getLong("membership_revision"),new AudiencePolicy(id,policyRevision,audience,effective),rules,new Principal(row.getString("creator_issuer"),row.getString("creator_subject")),row.getTimestamp("created_at").toInstant());
        }
        private static Action action(String text){return Arrays.stream(Action.values()).filter(a->a.wire().equals(text)).findFirst().orElseThrow(KbFailure::unavailable);}
        @Override public List<Membership> members(String tenant,UUID id){return memberships("collection_id",tenant,id,100,"membership_id");}
        @Override public List<Membership> memberships(String tenant,UUID id){return memberships("document_id",tenant,id,50,"collection_id");}
        private List<Membership> memberships(String column,String tenant,UUID id,int bound,String order){
            var result=new ArrayList<Membership>();try(var statement=prepare("SELECT * FROM kb.collection_memberships WHERE tenant_id=? AND "+column+"=? ORDER BY "+order+" LIMIT ?",tenant,id,bound+1);var rows=statement.executeQuery()){
                while(rows.next()){if(result.size()>=bound)throw KbFailure.unavailable();result.add(new Membership(rows.getObject("membership_id",UUID.class),tenant,rows.getObject("collection_id",UUID.class),rows.getObject("document_id",UUID.class),rows.getLong("revision"),State.valueOf(rows.getString("state")),new Principal(rows.getString("creator_issuer"),rows.getString("creator_subject")),rows.getTimestamp("created_at").toInstant(),rows.getString("reason")));}return List.copyOf(result);
            }catch(SQLException failure){throw KbFailure.unavailable();}
        }
        @Override public Scope scope(String tenant,UUID id){return new Scope(tenant,id,number("SELECT set_revision FROM kb.document_membership_scopes WHERE tenant_id=? AND document_id=?",tenant,id));}
        private static Object[] key(CommandKey key){return new Object[]{key.context().tenant(),key.context().actor().issuer(),key.context().actor().subject(),key.operation(),key.resourceKey(),key.idempotencyKey()};}
        private static final String KEY="tenant_id=? AND actor_issuer=? AND actor_subject=? AND operation=? AND resource_scope=? AND idempotency_key=?";
        @Override public Optional<CommandOutcome> command(CommandKey key){
            try(var statement=prepare("SELECT body_hash,status,collection_id,membership_id,revision,active_member_guard FROM kb.command_outcomes WHERE "+KEY,key(key));var rows=statement.executeQuery()){return rows.next()?Optional.of(new CommandOutcome(key,rows.getString(1),rows.getInt(2),new Receipt(rows.getObject(3,UUID.class),rows.getObject(4,UUID.class),rows.getLong(5)),rows.getBoolean(6))):Optional.empty();}catch(SQLException failure){throw KbFailure.unavailable();}
        }
        @Override public Optional<Snapshot> snapshot(String hash,Context context,UUID document){
            try(var statement=prepare("SELECT * FROM kb.policy_snapshots WHERE token_hash=? AND tenant_id=? AND actor_issuer=? AND actor_subject=? AND document_id=?",hash,context.tenant(),context.actor().issuer(),context.actor().subject(),document);var rows=statement.executeQuery()){
                if(!rows.next())return Optional.empty();classification(rows);return Optional.of(new Snapshot(hash,context,document,rows.getLong("document_revision"),rows.getLong("tenant_fence"),rows.getLong("set_revision"),SnapshotCodec.decode(rows.getInt("payload_version"),rows.getBytes("payload")),Decision.valueOf(rows.getString("decision")),rows.getTimestamp("created_at").toInstant()));
            }catch(SQLException failure){throw KbFailure.unavailable();}
        }
        private static void classification(ResultSet row)throws SQLException{if(!"development-restricted".equals(row.getString("classification"))||row.getLong("classification_policy_revision")!=1)throw KbFailure.unavailable();}
        @Override public void insertCollection(Collection c){
            writeTenant(c.tenant());
            if(c.revision()!=0||c.membershipRevision()!=0||c.audience().revision()!=1)throw KbFailure.unavailable();
            change("INSERT INTO kb.knowledge_collections VALUES (?,?,?,?,?,?,?,?,'development-restricted',1,?,?,?)",c.tenant(),c.collectionId(),c.revision(),c.membershipRevision(),c.audience().revision(),c.metadata().title(),c.metadata().description(),c.status().name(),c.creator().issuer(),c.creator().subject(),c.createdAt());
            policy(c);acl(c);revision(c);
        }
        @Override public void saveCollection(Collection c,long expected){
            writeTenant(c.tenant());
            Collection old=collection(c.tenant(),c.collectionId()).orElseThrow(()->new KbFailure(404,"NOT_FOUND"));
            if(old.revision()!=expected||c.revision()!=expected+1)throw conflict();
            if(!old.creator().equals(c.creator())||!old.createdAt().equals(c.createdAt()))throw KbFailure.unavailable();
            if(c.audience().revision()==old.audience().revision()){if(!c.audience().equals(old.audience()))throw KbFailure.unavailable();}
            else{if(c.audience().revision()!=old.audience().revision()+1)throw KbFailure.unavailable();policy(c);}
            if(change("UPDATE kb.knowledge_collections SET aggregate_revision=?,membership_revision=?,audience_policy_revision=?,title=?,description=?,status=? WHERE tenant_id=? AND collection_id=? AND aggregate_revision=?",c.revision(),c.membershipRevision(),c.audience().revision(),c.metadata().title(),c.metadata().description(),c.status().name(),c.tenant(),c.collectionId(),expected)!=1)throw conflict();
            change("DELETE FROM kb.collection_acl_rules WHERE tenant_id=? AND collection_id=?",c.tenant(),c.collectionId());acl(c);revision(c);
        }
        private void acl(Collection c){for(var rule:c.rules())change("INSERT INTO kb.collection_acl_rules VALUES (?,?,?,?,?,?)",c.tenant(),c.collectionId(),rule.principal().issuer(),rule.principal().subject(),rule.action().wire(),rule.effect().name());}
        private void policy(Collection c){
            var p=c.audience();change("INSERT INTO kb.audience_policies(tenant_id,collection_id,policy_revision,effective_at,classification,classification_policy_revision) VALUES (?,?,?,?,'development-restricted',1)",c.tenant(),c.collectionId(),p.revision(),p.effectiveAt());
            int index=0;var rules=p.rules().stream().sorted(Comparator.comparing(r->r.selector().kind().name()+":"+r.selector().issuer()+":"+r.selector().value()+":"+r.effect())).toList();
            for(var rule:rules){var s=rule.selector();change("INSERT INTO kb.audience_rules VALUES (?,?,?,?,?,?,?,?,?)",c.tenant(),c.collectionId(),p.revision(),index++,s.kind().name(),s.issuer(),s.kind()==SelectorKind.PRINCIPAL?s.value():null,s.kind()!=SelectorKind.PRINCIPAL?s.value():null,rule.effect().name());}
        }
        private void revision(Collection c){
            StringBuilder canonical=new StringBuilder(tuple(c.tenant(),c.collectionId(),c.revision(),c.membershipRevision(),c.metadata().title(),c.metadata().description(),c.status(),c.creator().issuer(),c.creator().subject(),c.createdAt(),c.audience().revision(),c.audience().effectiveAt()));
            canonical.append(tuple("acl",c.rules().size()));c.rules().stream().map(r->tuple(r.principal().issuer(),r.principal().subject(),r.action().wire(),r.effect())).sorted().forEach(canonical::append);
            canonical.append(tuple("audience",c.audience().rules().size()));c.audience().rules().stream().map(r->tuple(r.selector().kind(),r.selector().issuer(),r.selector().value(),r.effect())).sorted().forEach(canonical::append);
            change("INSERT INTO kb.policy_revisions VALUES (?,?,?,?,?,?,?)",c.tenant(),c.collectionId(),c.revision(),c.membershipRevision(),c.audience().revision(),digest(canonical.toString()),Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        }
        private static String tuple(Object...values){StringBuilder result=new StringBuilder();for(Object value:values){if(value==null)result.append("-1:");else{String text=value.toString();result.append(text.length()).append(':').append(text);}}return result.toString();}
        @Override public void saveMembership(Membership m,Long expected,Principal actor,Instant at){
            writeTenant(m.tenant());
            if(expected==null){if(m.revision()!=0)throw KbFailure.unavailable();change("INSERT INTO kb.collection_memberships VALUES (?,?,?,?,?,?,'SELECTED_TENANT',?,?,?,?)",m.tenant(),m.membershipId(),m.collectionId(),m.documentId(),m.revision(),m.state().name(),m.creator().issuer(),m.creator().subject(),m.createdAt(),m.reason());}
            else{if(m.revision()!=expected+1)throw conflict();if(change("UPDATE kb.collection_memberships SET revision=?,state=?,reason=? WHERE tenant_id=? AND membership_id=? AND collection_id=? AND document_id=? AND creator_issuer=? AND creator_subject=? AND created_at=? AND revision=?",m.revision(),m.state().name(),m.reason(),m.tenant(),m.membershipId(),m.collectionId(),m.documentId(),m.creator().issuer(),m.creator().subject(),m.createdAt(),expected)!=1)throw conflict();}
            change("INSERT INTO kb.membership_history VALUES (?,?,?,?,?,?,?,?)",m.tenant(),m.membershipId(),m.revision(),m.state().name(),actor.issuer(),actor.subject(),m.reason(),at);
        }
        @Override public void saveScope(Scope scope,long expected){writeTenant(scope.tenant());if(scope.revision()!=expected+1)throw conflict();if(change("UPDATE kb.document_membership_scopes SET set_revision=? WHERE tenant_id=? AND document_id=? AND set_revision=?",scope.revision(),scope.tenant(),scope.documentId(),expected)!=1)throw conflict();}
        @Override public void advanceTenantFence(String tenant,long expected){writeTenant(tenant);if(expected>=9_007_199_254_740_991L)throw KbFailure.unavailable();if(change("UPDATE kb.tenant_policy_fences SET revision=revision+1 WHERE tenant_id=? AND revision=?",tenant,expected)!=1)throw conflict();}
        @Override public void saveSnapshot(Snapshot s){
            writeTenant(s.context().tenant());
            if(number("SELECT count(*) FROM kb.policy_snapshots WHERE tenant_id=?",s.context().tenant())>=10000)throw new KbFailure(429,"LIMIT_EXCEEDED");
            change("INSERT INTO kb.policy_snapshots VALUES (?,?,?,?,?,?,'development-restricted',1,?,?,1,?,?,?)",s.tokenHash(),s.context().tenant(),s.context().actor().issuer(),s.context().actor().subject(),s.documentId(),s.documentRevision(),s.tenantFence(),s.scopeRevision(),SnapshotCodec.encode(s.entries()),s.decision().name(),s.createdAt());
        }
        @Override public void record(CommandOutcome outcome,Fact audit,Fact outbox){
            writeTenant(outcome.key().context().tenant());
            if(!outcome.key().context().equals(audit.context())||!audit.context().equals(outbox.context())||!outcome.receipt().collectionId().equals(audit.collectionId())||!audit.collectionId().equals(outbox.collectionId())||outcome.receipt().revision()!=audit.revision()||audit.revision()!=outbox.revision()||audit.membershipRevision()!=outbox.membershipRevision()||audit.audiencePolicyRevision()!=outbox.audiencePolicyRevision()||!audit.correlationId().equals(outbox.correlationId())||!outcome.key().operation().equals(audit.operation())||!audit.operation().equals(outbox.operation()))throw KbFailure.unavailable();
            var key=outcome.key();change("INSERT INTO kb.command_outcomes VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",key.context().tenant(),key.context().actor().issuer(),key.context().actor().subject(),key.operation(),key.resourceKey(),key.idempotencyKey(),outcome.requestHash(),outcome.status(),outcome.receipt().collectionId(),outcome.receipt().membershipId(),outcome.receipt().revision(),outcome.activeMemberGuard());
            change("INSERT INTO kb.audit_facts VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",audit.eventId(),audit.context().tenant(),audit.collectionId(),audit.revision(),audit.context().actor().issuer(),audit.context().actor().subject(),audit.operation(),audit.reason(),audit.occurredAt(),audit.correlationId(),audit.membershipRevision(),audit.audiencePolicyRevision());
            String causation=digest(tuple(key.context().tenant(),key.context().actor().issuer(),key.context().actor().subject(),key.operation(),key.resourceKey(),key.idempotencyKey(),outcome.requestHash()));
            change("INSERT INTO kb.outbox_events VALUES (?,?,?,?,?,?,?,1,?,?,?,?,?,?)",outbox.eventId(),outbox.context().tenant(),outbox.collectionId(),outbox.revision(),outbox.membershipRevision(),outbox.audiencePolicyRevision(),outbox.operation(),outbox.occurredAt(),outbox.context().actor().issuer(),outbox.context().actor().subject(),outbox.correlationId(),causation,outboxPayload(outbox));
        }
        private static byte[] outboxPayload(Fact fact){String value="kb-invalidation-v1\n"+fact.collectionId()+"\n"+fact.revision()+"\n"+fact.membershipRevision()+"\n"+fact.audiencePolicyRevision()+"\n"+fact.correlationId()+"\n"+fact.operation();return value.getBytes(StandardCharsets.UTF_8);}
        private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw KbFailure.unavailable();}}
        private static KbFailure conflict(){return new KbFailure(409,"STALE_REVISION");}
    }
}
