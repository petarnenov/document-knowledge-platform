package org.documentknowledge.devlogging.bootstrap;

import com.zaxxer.hikari.*;
import io.micronaut.context.annotation.*;
import io.micronaut.context.env.Environment;
import jakarta.inject.*;
import java.time.Clock;
import org.documentknowledge.devlogging.adapters.outbound.persistence.*;
import org.documentknowledge.devlogging.application.*;

/** Optional composition only: no migration owner, SQL or authority inference. */
@Factory
@Requires(property="dkp.logging.writer-enabled",value="true")
public final class WriterAssembly {
    @Context PrivateWriterConfiguration configuration(Environment environment,PrivateLogConfiguration readers,Clock clock) {
        try {
            return new PrivateWriterConfiguration(environment.getRequiredProperty("dkp.logging.writer-password-file",String.class),
                environment.getRequiredProperty("dkp.logging.registration-grants-file",String.class),
                environment.getRequiredProperty("dkp.logging.observer-grant-file",String.class),
                environment.getRequiredProperty("dkp.logging.volume-identity",String.class),readers,clock);
        } catch(Exception invalid){throw new IllegalStateException("LOG_CONFIGURATION_UNAVAILABLE");}
    }
    @Context @Bean(preDestroy="close") @Named("log-writer")
    HikariDataSource writer(PrivateWriterConfiguration configuration,PrivateLogConfiguration readers) {
        HikariDataSource pool=null;
        try {
            var settings=new HikariConfig();settings.setJdbcUrl(readers.jdbcUrl());settings.setUsername("dkp_log_writer");
            settings.setPassword(configuration.password());settings.setMaximumPoolSize(32);settings.setMinimumIdle(0);
            settings.setConnectionTimeout(1000);settings.setValidationTimeout(500);settings.setInitializationFailTimeout(-1);
            settings.setPoolName("dkp-log-writer");settings.addDataSourceProperty("connectTimeout","1");settings.addDataSourceProperty("socketTimeout","5");
            pool=new HikariDataSource(settings);new JdbcWriteRoleVerifier(pool).verify(RequestDeadline.start());return pool;
        } catch(Exception invalid) {
            if(pool!=null)pool.close();throw new IllegalStateException("LOG_DATABASE_UNAVAILABLE");
        }
    }
    @Singleton @Bean(typed={JdbcLogProducers.class,LogProducerRegistry.class,LogManifestCatalog.class})
    JdbcLogProducers producers(@Named("log-writer") HikariDataSource pool){return new JdbcLogProducers(pool);}
    @Singleton LogProducerAuthentication producerAuthentication(JdbcLogProducers producers,PrivateWriterConfiguration config) {
        return new SeparatedLogProducerAuthentication(producers,config.nonproducerHashes());
    }
    @Singleton LogRegistrationAuthentication registration(PrivateWriterConfiguration config,Clock clock) {
        return new ConfiguredLogRegistrars(config.registrars(),config.delegators(),clock);
    }
    @Singleton LogObserverAuthentication observer(PrivateWriterConfiguration config,Clock clock){return new ConfiguredLogObserver(config.observer(),clock);}
    @Singleton ObservedVolumeAdmission observation(PrivateWriterConfiguration config,Clock clock){return new ObservedVolumeAdmission(config.volumeIdentity(),clock);}
    @Singleton LogPersistence persistence(@Named("log-writer") HikariDataSource pool,StorageAdmission admission){return new JdbcLogPersistence(pool,admission);}
}
