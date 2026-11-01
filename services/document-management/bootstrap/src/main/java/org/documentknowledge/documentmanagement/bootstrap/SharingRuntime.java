package org.documentknowledge.documentmanagement.bootstrap;

import com.zaxxer.hikari.*;
import io.micronaut.context.BeanProvider;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.documentknowledge.documentmanagement.adapters.outbound.sharing.JdbcDocumentShareStore;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.application.sharing.*;
import org.documentknowledge.documentmanagement.application.sharing.SharingModels.Document;

/** Dedicated derived DML pool; legacy pool, migration credentials and qualification remain untouched. */
public final class SharingRuntime implements DocumentShareStore,AutoCloseable {
    private final BeanProvider<DataSource> runtime;
    private HikariDataSource pool;
    private boolean closed;
    public SharingRuntime(BeanProvider<DataSource> runtime){this.runtime=runtime;}
    @Override public <T>T locked(String tenant,List<UUID> ids,SharingDeadline deadline,Function<List<Document>,T> work){
        deadline.check();return new JdbcDocumentShareStore(pool(deadline)).locked(tenant,ids,deadline,work);
    }
    private synchronized HikariDataSource pool(SharingDeadline deadline){
        deadline.check();if(closed)throw unavailable();
        if(pool==null){
            if(!runtime.isPresent()||!(runtime.get() instanceof HikariDataSource managed))throw unavailable();
            HikariConfig config=new HikariConfig();managed.copyStateTo(config);
            config.setPoolName("dm-sharing");config.setMaximumPoolSize(16);config.setMinimumIdle(0);
            config.setConnectionTimeout(250);config.setValidationTimeout(250);config.setInitializationFailTimeout(-1);
            pool=new HikariDataSource(config);
        }
        deadline.check();return pool;
    }
    @Override public synchronized void close(){closed=true;if(pool!=null)pool.close();}
    private static DmFailure unavailable(){return new DmFailure(503,"UNAVAILABLE");}
}
