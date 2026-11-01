package org.documentknowledge.knowledgebase.bootstrap;

import com.zaxxer.hikari.HikariDataSource;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.env.Environment;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.net.URI;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.documentknowledge.knowledgebase.adapters.outbound.catalog.FileKnowledgeAuthority;
import org.documentknowledge.knowledgebase.adapters.outbound.cursor.CursorKeyReadiness;
import org.documentknowledge.knowledgebase.adapters.outbound.remote.FixedDocumentShareReadiness;
import org.documentknowledge.knowledgebase.application.Deadline;

/** Operational dependencies only. No actor, sharing grant or policy decision is synthesized. */
@Factory
public final class KnowledgeReadinessFactory {
    private static final String DATABASE_QUERY="""
        SELECT current_database()='knowledge_base' AND current_user='dkp_kb_runtime'
          AND (SELECT count(*) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
               WHERE n.nspname='kb' AND c.relkind='r' AND c.relname IN
                ('knowledge_collections','collection_acl_rules','audience_policies','audience_rules',
                 'collection_memberships','membership_history','policy_revisions','document_membership_scopes',
                 'tenant_policy_fences','policy_snapshots','command_outcomes','audit_facts','outbox_events')
               AND pg_catalog.has_table_privilege(current_user,c.oid,'SELECT')
               AND pg_catalog.has_table_privilege(current_user,c.oid,'INSERT'))=13
          AND (SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolinherit AND NOT rolreplication AND NOT rolbypassrls
               FROM pg_catalog.pg_roles WHERE rolname=current_user)
          AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members WHERE member=(SELECT oid FROM pg_catalog.pg_roles WHERE rolname=current_user))
          AND NOT pg_catalog.has_schema_privilege(current_user,'public','CREATE')
          AND NOT pg_catalog.has_database_privilege(current_user,'knowledge_base','CREATE,TEMP')
        """;
    @Singleton @Named("kb-readiness")
    BooleanSupplier dependencies(Environment environment,BeanProvider<HikariDataSource> pools){
        return ()->{
            if(!environment.getProperty("dkp.kb.enabled",Boolean.class).orElse(false))return true;
            try{
                if(!database(pools.get()))return false;
                if(!new FileKnowledgeAuthority(Path.of(property(environment,"dkp.kb.catalog.path"))).ready())return false;
                if(!new CursorKeyReadiness(Path.of(property(environment,"dkp.kb.cursor.key-path"))).ready())return false;
                return FixedDocumentShareReadiness.connect(URI.create(property(environment,"dkp.kb.dm.base-uri")),Path.of(property(environment,"dkp.kb.dm.ca-path"))).ready();
            }catch(RuntimeException unavailable){return false;}
        };
    }
    static boolean database(javax.sql.DataSource pool){
        Deadline deadline=Deadline.request().child(Duration.ofMillis(500));
        try(Connection connection=pool.getConnection()){
            deadline.check();connection.setNetworkTimeout(Runnable::run,(int)Math.max(1,deadline.remainingNanos()/1_000_000));
            try(Statement statement=connection.createStatement()){
                statement.setQueryTimeout(1);
                try(ResultSet result=statement.executeQuery(DATABASE_QUERY)){boolean available=result.next()&&result.getBoolean(1);deadline.check();return available;}
            }
        }catch(SQLException|RuntimeException unavailable){return false;}
    }
    private static String property(Environment environment,String name){return environment.getProperty(name,String.class).orElse("");}
}
