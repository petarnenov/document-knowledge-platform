package org.documentknowledge.documentmanagement.bootstrap;

import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import javax.sql.DataSource;
import org.documentknowledge.documentmanagement.application.Readiness;

@Factory
public final class ReadinessFactory {
    @Singleton public Readiness readiness(@Named("default") BeanProvider<DataSource> runtime){
        return new Readiness(()->{try {if(!runtime.isPresent())return false;try(var connection=runtime.get().getConnection()){return connection.isValid(1);}}catch(Exception unavailable){return false;}});
    }
}
