package org.documentknowledge.devlogging.bootstrap;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;
import jakarta.inject.Named;
import java.time.Clock;
import org.documentknowledge.devlogging.adapters.inbound.requests.DeadlineLogRequests;
import org.documentknowledge.devlogging.adapters.outbound.persistence.*;
import org.documentknowledge.devlogging.application.*;

/** Composition root only; the outbound adapter owns all SQL and role verification. */
@Factory
public final class LoggingAssembly {
    @Singleton Clock clock() { return Clock.systemUTC(); }

    @Context PrivateLogConfiguration configuration(Environment environment, Clock clock) {
        try {
            String writer=environment.getProperty("dkp.logging.writer-enabled",String.class).orElse("false");
            if(!writer.equals("true") && !writer.equals("false"))throw new IllegalArgumentException();
            return new PrivateLogConfiguration(environment.getRequiredProperty("dkp.logging.jdbc-url", String.class),
                environment.getRequiredProperty("dkp.logging.db-password-file", String.class),
                environment.getRequiredProperty("dkp.logging.reader-grants-file", String.class),
                environment.getRequiredProperty("dkp.logging.cursor-key-file", String.class), clock);
        } catch (Exception invalid) { throw new IllegalStateException("LOG_CONFIGURATION_UNAVAILABLE"); }
    }

    @Context @Bean(preDestroy = "close") @Named("log-reader")
    HikariDataSource dataSource(PrivateLogConfiguration configuration) {
        HikariDataSource pool = null;
        try {
            var settings = new HikariConfig();
            settings.setJdbcUrl(configuration.jdbcUrl());
            settings.setUsername("dkp_log_reader");
            settings.setPassword(configuration.password());
            settings.setMaximumPoolSize(32); settings.setMinimumIdle(0);
            settings.setConnectionTimeout(1000); settings.setValidationTimeout(500);
            settings.setInitializationFailTimeout(-1); settings.setPoolName("dkp-log-reader");
            settings.addDataSourceProperty("connectTimeout", "1");
            settings.addDataSourceProperty("socketTimeout", "5");
            pool = new HikariDataSource(settings);
            new JdbcReadRoleVerifier(pool).verify(RequestDeadline.start());
            return pool;
        } catch (Exception invalid) {
            if (pool != null) pool.close();
            throw new IllegalStateException("LOG_DATABASE_UNAVAILABLE");
        }
    }

    @Singleton LogReaderAuthentication readers(PrivateLogConfiguration config, Clock clock) {
        return new ConfiguredLogReaders(config.grants(), clock);
    }
    @Singleton LogQueries queries(@Named("log-reader") HikariDataSource pool, PrivateLogConfiguration config, Clock clock) {
        return new LogQueries(new JdbcLogQueries(pool), new EncryptedLogCursors(config.cursorKey()), clock);
    }
    @Singleton @Bean(preDestroy = "close") DeadlineLogRequests requests() { return new DeadlineLogRequests(); }
}
