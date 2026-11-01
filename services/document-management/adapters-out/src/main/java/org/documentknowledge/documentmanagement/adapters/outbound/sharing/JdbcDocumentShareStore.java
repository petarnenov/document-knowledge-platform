package org.documentknowledge.documentmanagement.adapters.outbound.sharing;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.sql.DataSource;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.application.sharing.*;
import org.documentknowledge.documentmanagement.application.sharing.SharingModels.Document;
import org.documentknowledge.documentmanagement.domain.*;

/** DM-owned projection only. Every existing aggregate writer takes the same owning document lock. */
public final class JdbcDocumentShareStore implements DocumentShareStore {
    private final DataSource source;
    public JdbcDocumentShareStore(DataSource source){this.source=Objects.requireNonNull(source);}
    @Override public <T>T locked(String tenant,List<UUID> documents,SharingDeadline request,Function<List<Document>,T> work){
        SharingDeadline deadline=request.child(Duration.ofMillis(500));
        if(documents.isEmpty()||documents.size()>100||new HashSet<>(documents).size()!=documents.size())throw unavailable();
        List<UUID> sorted=documents.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        deadline.check();
        // Production factory supplies a dedicated <=250ms Hikari acquisition pool. No arbitrary DS timing claim.
        try(Connection connection=source.getConnection()){
            deadline.check();connection.setAutoCommit(false);
            try{
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                limits(connection,deadline);
                List<Document> rows=new ArrayList<>();
                String placeholders=String.join(",",Collections.nCopies(sorted.size(),"?"));
                try(PreparedStatement query=connection.prepareStatement("SELECT document_id,revision,classification,classification_revision FROM dm.documents WHERE tenant_id=? AND document_id IN ("+placeholders+") ORDER BY document_id FOR UPDATE")){
                    query.setString(1,tenant);for(int i=0;i<sorted.size();i++)query.setObject(i+2,sorted.get(i));
                    try(ResultSet result=query.executeQuery()){while(result.next())rows.add(new Document(result.getObject(1,UUID.class),tenant,result.getLong(2),result.getString(3),result.getLong(4),Set.of()));}
                }
                limits(connection,deadline);
                Map<UUID,Set<AclRule>> rules=new HashMap<>();
                try(PreparedStatement query=connection.prepareStatement("SELECT document_id,principal_issuer,principal_subject,action,effect FROM dm.acl_rules WHERE tenant_id=? AND document_id IN ("+placeholders+") AND action=? LIMIT 10001")){
                    query.setString(1,tenant);for(int i=0;i<sorted.size();i++)query.setObject(i+2,sorted.get(i));query.setString(sorted.size()+2,Action.SHARE_TO_COLLECTION.wire());
                    try(ResultSet result=query.executeQuery()){while(result.next()){var rule=new AclRule(new Principal(result.getString(2),result.getString(3)),Action.parse(result.getString(4)),AclRule.Effect.valueOf(result.getString(5)));Set<AclRule> target=rules.computeIfAbsent(result.getObject(1,UUID.class),unused->new HashSet<>());target.add(rule);if(target.size()>100)throw unavailable();}}
                }
                deadline.check();
                List<Document> projected=rows.stream().map(row->new Document(row.documentId(),row.tenant(),row.revision(),row.classification(),row.classificationPolicyRevision(),rules.getOrDefault(row.documentId(),Set.of()))).toList();
                T result=work.apply(projected);limits(connection,deadline);connection.commit();deadline.check();return result;
            }catch(SQLException|RuntimeException failure){try{connection.rollback();}catch(SQLException suppressed){/* No mutation is issued by this projection. */}if(failure instanceof DmFailure domain)throw domain;throw unavailable();}
        }catch(SQLException failure){throw unavailable();}
    }
    private static void limits(Connection connection,SharingDeadline deadline)throws SQLException{
        long millis=Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline.remainingNanos()));
        connection.setNetworkTimeout(Runnable::run,(int)Math.min(500,millis));
        try(PreparedStatement statement=connection.prepareStatement("SELECT set_config('statement_timeout',?,true),set_config('lock_timeout',?,true),set_config('transaction_timeout',?,true)")){
            statement.setString(1,Long.toString(Math.min(500,millis)));statement.setString(2,Long.toString(Math.min(250,millis)));statement.setString(3,Long.toString(Math.min(500,millis)));statement.execute();
        }
        deadline.check();
    }
    private static DmFailure unavailable(){return new DmFailure(503,"UNAVAILABLE");}
}
