package org.documentknowledge.documentmanagement.bootstrap;

import io.micronaut.context.ApplicationContext;
import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import javax.net.ssl.SSLContext;
import org.documentknowledge.documentmanagement.adapters.outbound.storage.S3UploadStorageAdapter;
import org.documentknowledge.documentmanagement.adapters.outbound.verification.StandardDefinitions;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;
import org.documentknowledge.identity.Actor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenericRuntimeBindingTest {
    static final class Manager implements GenericTempPort {
        int reservations,closes; boolean healthy=true,failClose;
        Role role; Identity identity; GenericTempBudget budget;
        final Workspace workspace=new Workspace(){
            public ArtifactFile write(InputStream input,GenericTempBudget budget){throw new AssertionError();}
            public Use retain(GenericTempBudget budget){throw new AssertionError();}
            public void finish(GenericTempBudget budget){throw new AssertionError();}
            public void close(){}
        };
        public Workspace reserve(Role role,Identity identity,GenericTempBudget budget){this.role=role;this.identity=identity;this.budget=budget;reservations++;return workspace;}
        public boolean ready(){return healthy;}
        public void close(){closes++;if(failClose)throw new DmFailure(503,"UNAVAILABLE");}
    }
    static final class Connections implements UploadRuntime.Connections {
        final Manager manager=new Manager(); final VerificationRuntimeBindingTest.Manager verification=new VerificationRuntimeBindingTest.Manager();
        int initialized,storageCreated,dispatches,storageClosed,cleanupClosed;
        boolean failInitialization; Runnable onStorage=()->{}; GenericTempBudget forwarded;
        public GenericTempPort generic(Path root){assertEquals(Path.of("/var/lib/dkp/uploads"),root);initialized++;if(failInitialization)throw new DmFailure(503,"UNAVAILABLE");return manager;}
        public VerificationWorkspacePort verification(Path root){return verification;}
        public UploadRuntime.Storage storage(S3UploadStorageAdapter.Configuration configuration){
            storageCreated++;onStorage.run();
            return new UploadRuntime.Storage(new UploadStoragePort(){
                public ObjectIdentity put(ObjectKey k,Path p,long s,String h){throw new AssertionError("No legacy PUT");}
                public InputStream open(ObjectIdentity o){throw new AssertionError("No legacy OPEN");}
                public ObjectIdentity put(ObjectKey k,Path p,long s,String h,GenericTempBudget b){forwarded=b;dispatches++;return null;}
                public InputStream open(ObjectIdentity o,GenericTempBudget b){forwarded=b;dispatches++;return new ByteArrayInputStream(new byte[]{7});}
                public ObjectIdentity copy(ObjectIdentity o,ObjectKey k){throw new AssertionError();}
                public List<ObjectIdentity> versions(ObjectKey k){throw new AssertionError();}
            },()->true,()->storageClosed++);
        }
        public UploadRuntime.Cleanup cleanup(S3UploadStorageAdapter.Configuration c){return new UploadRuntime.Cleanup(null,()->true,()->cleanupClosed++);}
        public UploadRuntime.Scanner scanner(InetSocketAddress e,StandardDefinitions d,Clock c){throw new DmFailure(503,"UNAVAILABLE");}
        public UploadRuntime.Inspector inspector(URI e,SSLContext t,byte[] k,String p,String i,Clock c){throw new AssertionError();}
        public boolean health(URI e,SSLContext t){throw new AssertionError();}
    }
    private ApplicationContext context(){return ApplicationContext.run(Map.of("dkp.dm.upload.enabled",true,"dkp.dm.storage.endpoint","https://storage.example:9000/","dkp.dm.storage.region","us-east-1"));}
    private UploadRuntime runtime(ApplicationContext context,Connections connections){return new UploadRuntime(context.getEnvironment(),Clock.systemUTC(),(p,m,s)->p.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII),connections);}
    private GenericTempPort.Identity identity(){return GenericTempPort.Identity.of("tenant",new Actor("issuer","subject"),UUID.randomUUID(),UUID.randomUUID(),null,1);}

    @Test void oneLazyManagerReceivesExactRoleIdentityAndBudgetForBothRoles(){
        var connections=new Connections();
        try(var context=context();var runtime=runtime(context,connections)){
            var port=runtime.generic();assertSame(port,runtime.generic());assertEquals(0,connections.initialized);
            for(var role:GenericTempPort.Role.values()){
                var budget=new GenericTempBudget(Clock.systemUTC(),role);var identity=identity();
                assertSame(connections.manager.workspace,port.reserve(role,identity,budget));
                assertSame(role,connections.manager.role);assertSame(identity,connections.manager.identity);assertSame(budget,connections.manager.budget);
            }
            assertEquals(1,connections.initialized);assertEquals(2,connections.manager.reservations);assertEquals(0,connections.storageCreated);
        }
        assertEquals(1,connections.manager.closes);
    }
    @Test void retainedInvalidOrFailedManagerCannotReportReadyAndShutdownNeverRecreatesIt(){
        var connections=new Connections();
        try(var context=context();var runtime=runtime(context,connections)){
            connections.manager.healthy=false;assertFalse(runtime.ready());assertEquals(0,connections.storageCreated);
            assertFalse(runtime.generic().ready());runtime.close();runtime.close();assertEquals(1,connections.manager.closes);
            assertFalse(runtime.ready());assertFalse(runtime.generic().ready());
            assertThrows(DmFailure.class,()->runtime.generic().reserve(GenericTempPort.Role.TRANSFER,identity(),new GenericTempBudget(Clock.systemUTC(),GenericTempPort.Role.TRANSFER)));
            assertEquals(1,connections.initialized);
        }
        var failed=new Connections();failed.failInitialization=true;
        try(var context=context();var runtime=runtime(context,failed)){assertFalse(runtime.generic().ready());assertEquals(0,failed.storageCreated);}
    }
    @Test void exactRoleBudgetsReachPutAndOpenAndCancelledBudgetsNeverDispatch()throws Exception{
        var connections=new Connections();
        try(var context=context();var runtime=runtime(context,connections)){
            var transfer=new GenericTempBudget(Clock.systemUTC(),GenericTempPort.Role.TRANSFER);
            runtime.put(null,null,1,"hash",transfer);assertSame(transfer,connections.forwarded);
            var append=new GenericTempBudget(Clock.systemUTC(),GenericTempPort.Role.PREAPPEND);
            try(var input=runtime.open(null,append)){assertEquals(7,input.read());}assertSame(append,connections.forwarded);
            transfer.cancel();append.cancel();
            assertThrows(DmFailure.class,()->runtime.put(null,null,1,"hash",transfer));assertThrows(DmFailure.class,()->runtime.open(null,append));
            assertEquals(2,connections.dispatches);assertEquals(1,connections.storageCreated);
            assertThrows(DmFailure.class,()->runtime.generic().reserve(GenericTempPort.Role.TRANSFER,identity(),transfer));assertEquals(0,connections.initialized);
        }
    }
    @Test void cancellationDuringConnectionCreationPreventsLatePutAndOpenDispatch(){
        for(boolean put:List.of(false,true)){
            var connections=new Connections();var budget=new GenericTempBudget(Clock.systemUTC(),GenericTempPort.Role.TRANSFER);connections.onStorage=budget::cancel;
            try(var context=context();var runtime=runtime(context,connections)){
                assertThrows(DmFailure.class,()->{if(put)runtime.put(null,null,1,"hash",budget);else runtime.open(null,budget);});
                assertEquals(1,connections.storageCreated);assertEquals(0,connections.dispatches);
            }
        }
    }
    @Test void disabledUnusedAndFailedShutdownKeepAuthorityClosedAndCloseOtherResources(){
        var unused=new Connections();
        try(var context=ApplicationContext.run(Map.of());var runtime=runtime(context,unused)){
            assertTrue(runtime.ready());assertFalse(runtime.generic().ready());runtime.close();assertFalse(runtime.ready());assertEquals(0,unused.initialized);
        }
        var connections=new Connections();
        try(var context=context()){
            var runtime=runtime(context,connections);assertTrue(runtime.generic().ready());assertTrue(runtime.verification().ready());
            runtime.put(null,null,1,"hash",new GenericTempBudget(Clock.systemUTC(),GenericTempPort.Role.TRANSFER));connections.manager.failClose=true;
            assertThrows(DmFailure.class,runtime::close);runtime.close();
            assertEquals(1,connections.manager.closes);assertEquals(1,connections.verification.closes);assertEquals(1,connections.storageClosed);assertEquals(1,connections.cleanupClosed);assertFalse(runtime.ready());
        }
    }
}
