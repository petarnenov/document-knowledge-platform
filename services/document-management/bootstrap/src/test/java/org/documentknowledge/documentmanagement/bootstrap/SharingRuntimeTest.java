package org.documentknowledge.documentmanagement.bootstrap;
import com.zaxxer.hikari.*;
import io.micronaut.context.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.documentmanagement.application.sharing.*;
import org.documentknowledge.documentmanagement.domain.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;
class SharingRuntimeTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6@sha256:979bccfde35445db618c65107d27d08c6c21339d8dc3ad4c830c6633947be0e4").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("dm").withUsername("postgres").withPassword(UUID.randomUUID().toString())
        .withCommand("postgres","-c","fsync=on","-c","synchronous_commit=on","-c","full_page_writes=on");
    static final String MIGRATOR_PASSWORD=UUID.randomUUID().toString(), RUNTIME_PASSWORD=UUID.randomUUID().toString();
    static final Principal ALICE=new Principal("https://issuer.example","alice");
    static final Instant NOW=Instant.parse("2026-10-31T12:00:00Z");
    static PGSimpleDataSource admin,runtime,migrator;
    static String jdbcUrl;
    static org.documentknowledge.documentmanagement.adapters.outbound.persistence.JdbcDocumentStore store;
    static Flyway flyway;
    static PGSimpleDataSource source(String user,String password) {
        PGSimpleDataSource result=new PGSimpleDataSource(); result.setURL(jdbcUrl==null?POSTGRES.getJdbcUrl():jdbcUrl);result.setUser(user);result.setPassword(password);return result;
    }
    @BeforeAll static void setup() throws Exception {
        POSTGRES.start(); jdbcUrl=POSTGRES.getJdbcUrl(); admin=source(POSTGRES.getUsername(),POSTGRES.getPassword());
        try(Connection c=admin.getConnection();Statement s=c.createStatement()) {
            s.execute("CREATE ROLE dkp_dm_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"+MIGRATOR_PASSWORD+"'");
            s.execute("CREATE ROLE dkp_dm_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"+RUNTIME_PASSWORD+"'");
            s.execute("REVOKE ALL ON DATABASE dm FROM PUBLIC");
            s.execute("REVOKE CONNECT ON DATABASE postgres,template1 FROM PUBLIC");
            s.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
            s.execute("GRANT CONNECT,CREATE ON DATABASE dm TO dkp_dm_migrator");
            s.execute("GRANT CONNECT ON DATABASE dm TO dkp_dm_runtime");
            s.execute("ALTER ROLE dkp_dm_runtime SET search_path=dm");
        }
        migrator=source("dkp_dm_migrator",MIGRATOR_PASSWORD);runtime=source("dkp_dm_runtime",RUNTIME_PASSWORD);
        flyway=Flyway.configure().dataSource(migrator).defaultSchema("dm").schemas("dm").locations("classpath:db/migration").load();
        assertEquals(2,flyway.migrate().migrationsExecuted);
        store=new org.documentknowledge.documentmanagement.adapters.outbound.persistence.JdbcDocumentStore(runtime);
    }
    @AfterAll static void cleanup() { POSTGRES.stop(); }


    static BeanProvider<DataSource> provider(DataSource source){return new BeanProvider<>(){public boolean isPresent(){return source!=null;}public DataSource get(){return source;}};}
    HikariDataSource managed(){var config=new HikariConfig();config.setJdbcUrl(POSTGRES.getJdbcUrl());config.setUsername("dkp_dm_runtime");config.setPassword(RUNTIME_PASSWORD);config.setConnectionTimeout(1000);config.setValidationTimeout(500);config.setMaximumPoolSize(2);config.setMinimumIdle(0);return new HikariDataSource(config);}
    HikariDataSource derived(SharingRuntime runtime)throws Exception{var field=SharingRuntime.class.getDeclaredField("pool");field.setAccessible(true);return (HikariDataSource)field.get(runtime);}
    @Test void actualDedicatedPoolBoundsAndExhaustionDoNotAlterLegacyOrCredentials()throws Exception{
        HikariDataSource derived;
        try(var original=managed();var runtime=new SharingRuntime(provider(original))){
            assertNull(derived(runtime));assertEquals(0,runtime.<Integer>locked("missing",List.of(UUID.randomUUID()),SharingDeadline.request(),List::size));derived=derived(runtime);
            assertNotSame(original,derived);assertEquals(16,derived.getMaximumPoolSize());assertEquals(0,derived.getMinimumIdle());assertEquals(250,derived.getConnectionTimeout());assertEquals(250,derived.getValidationTimeout());assertEquals(-1,derived.getInitializationFailTimeout());assertEquals(original.getJdbcUrl(),derived.getJdbcUrl());assertEquals(original.getUsername(),derived.getUsername());assertEquals(1000,original.getConnectionTimeout());assertEquals(500,original.getValidationTimeout());assertEquals(2,original.getMaximumPoolSize());
            List<Connection> borrowed=new ArrayList<>();try{for(int i=0;i<16;i++)borrowed.add(derived.getConnection());long started=System.nanoTime();var error=assertThrows(DmFailure.class,()->runtime.locked("missing",List.of(UUID.randomUUID()),SharingDeadline.request(),List::size));assertEquals(503,error.status());long elapsed=Duration.ofNanos(System.nanoTime()-started).toMillis();assertTrue(elapsed>=200&&elapsed<700,"Acquisition limited independently of legacy: "+elapsed);}finally{for(Connection connection:borrowed)connection.close();}
            assertEquals(0,runtime.<Integer>locked("missing",List.of(UUID.randomUUID()),SharingDeadline.request(),List::size));
        }
        assertTrue(derived.isClosed());
    }
    @Test void missingOrNonmanagedDatasourceCannotCreateFallbackAndClosedOwnershipStaysClosed(){for(DataSource invalid:Arrays.asList(null,runtime)){try(var sharing=new SharingRuntime(provider(invalid))){assertEquals(503,assertThrows(DmFailure.class,()->sharing.locked("t",List.of(UUID.randomUUID()),SharingDeadline.request(),List::size)).status());sharing.close();assertEquals(503,assertThrows(DmFailure.class,()->sharing.locked("t",List.of(UUID.randomUUID()),SharingDeadline.request(),List::size)).status());}}}
    @Test void defaultBindingMissingDatasourceOrExternalAuthorityCannotBecomeAllow(){
        try(var context=ApplicationContext.builder().properties(Map.of("dkp.security.profile","external")).start();var runtime=new SharingRuntime(provider(null))){var service=new DocumentFactory().sharing(context.getEnvironment(),runtime);var actor=new org.documentknowledge.identity.Actor("https://issuer.example","staff");var caller=new DocumentCaller(actor,"tenant-a",()->org.documentknowledge.identity.AuthorityDecision.ALLOW);assertEquals(503,assertThrows(DmFailure.class,()->service.discover(caller,List.of(UUID.randomUUID()),SharingDeadline.request())).status());}
    }
}
