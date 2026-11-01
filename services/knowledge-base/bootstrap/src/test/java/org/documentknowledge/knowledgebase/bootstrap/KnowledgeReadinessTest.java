package org.documentknowledge.knowledgebase.bootstrap;

import io.micronaut.context.ApplicationContext;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeReadinessTest {
    static PGSimpleDataSource source(String url,String user,String password){var value=new PGSimpleDataSource();value.setURL(url);value.setUser(user);value.setPassword(password);value.setConnectTimeout(1);value.setSocketTimeout(1);return value;}
    static void sql(PGSimpleDataSource ds,String query)throws SQLException{try(var connection=ds.getConnection();var statement=connection.createStatement()){statement.execute(query);}}
    @Test void actualRuntimeRoleAndAllThirteenTablesAreRequiredWithoutAnyAuthorityMutation()throws Exception{
        try(var pg=new PostgreSQLContainer(DockerImageName.parse("postgres:18.6@sha256:979bccfde35445db618c65107d27d08c6c21339d8dc3ad4c830c6633947be0e4").asCompatibleSubstituteFor("postgres")).withDatabaseName("knowledge_base").withUsername("postgres").withPassword(UUID.randomUUID().toString())){
            pg.start();String migration=UUID.randomUUID().toString(),runtimePassword=UUID.randomUUID().toString();var admin=source(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());
            sql(admin,"CREATE ROLE dkp_kb_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"+migration+"'");sql(admin,"CREATE ROLE dkp_kb_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"+runtimePassword+"'");
            sql(admin,"REVOKE ALL ON DATABASE knowledge_base FROM PUBLIC");sql(admin,"REVOKE ALL ON SCHEMA public FROM PUBLIC");sql(admin,"REVOKE CONNECT ON DATABASE postgres,template1 FROM PUBLIC");sql(admin,"GRANT CONNECT,CREATE ON DATABASE knowledge_base TO dkp_kb_migrator");sql(admin,"GRANT CONNECT ON DATABASE knowledge_base TO dkp_kb_runtime");sql(admin,"CREATE SCHEMA kb AUTHORIZATION dkp_kb_migrator");
            var runtime=source(pg.getJdbcUrl(),"dkp_kb_runtime",runtimePassword);assertFalse(KnowledgeReadinessFactory.database(runtime));
            Flyway.configure().dataSource(source(pg.getJdbcUrl(),"dkp_kb_migrator",migration)).defaultSchema("kb").schemas("kb").createSchemas(false).locations("classpath:db/migration").load().migrate();
            assertTrue(KnowledgeReadinessFactory.database(runtime));assertFalse(KnowledgeReadinessFactory.database(admin));
            sql(admin,"ALTER TABLE kb.audit_facts RENAME TO absent_audit_facts");try{assertFalse(KnowledgeReadinessFactory.database(runtime));}finally{sql(admin,"ALTER TABLE kb.absent_audit_facts RENAME TO audit_facts");}assertTrue(KnowledgeReadinessFactory.database(runtime));
            sql(admin,"ALTER ROLE dkp_kb_runtime CREATEDB");try{assertFalse(KnowledgeReadinessFactory.database(runtime));}finally{sql(admin,"ALTER ROLE dkp_kb_runtime NOCREATEDB");}assertTrue(KnowledgeReadinessFactory.database(runtime));
            var properties=new Properties();properties.setProperty("user","dkp_kb_runtime");properties.setProperty("password",runtimePassword);
            String overriding=pg.getJdbcUrl().split("\\?",2)[0]+"?user=postgres&password="+pg.getPassword();
            try(var connection=DriverManager.getConnection(overriding,properties);var statement=connection.createStatement();var row=statement.executeQuery("SELECT current_user")){assertTrue(row.next());assertEquals("postgres",row.getString(1),"Actual pgjdbc URL properties override the caller-supplied runtime credentials");}
            var forbidden=Map.<String,Object>of("dkp.kb.jdbc.url",overriding,"dkp.kb.jdbc.username","dkp_kb_runtime","dkp.kb.jdbc.password",runtimePassword);
            try(var context=ApplicationContext.run(forbidden)){assertThrows(org.documentknowledge.knowledgebase.domain.KbFailure.class,()->new KnowledgeRuntimeFactory().runtimePool(context.getEnvironment()));}
            try(var connection=admin.getConnection();var statement=connection.createStatement();var row=statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name='dkp-kb-runtime'")){assertTrue(row.next());assertEquals(0,row.getLong(1),"Rejected URL never creates a background runtime connection or later SQL");}
            sql(admin,"REVOKE CONNECT ON DATABASE knowledge_base FROM dkp_kb_runtime");assertFalse(KnowledgeReadinessFactory.database(runtime));
        }
    }
    @Test void disabledFoundationDoesNotAdvertiseEnabledBusinessDependenciesAndMissingRequiredConfigIsDown(){
        try(var context=ApplicationContext.run()){
            assertTrue(context.getBean(java.util.function.BooleanSupplier.class,io.micronaut.inject.qualifiers.Qualifiers.byName("kb-readiness")).getAsBoolean());
        }
        var unavailable=source("jdbc:postgresql://127.0.0.1:1/knowledge_base","dkp_kb_runtime","synthetic-test-only");assertFalse(KnowledgeReadinessFactory.database(unavailable));
    }
}
