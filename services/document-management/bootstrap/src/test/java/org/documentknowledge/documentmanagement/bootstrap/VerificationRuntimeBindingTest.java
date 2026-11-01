package org.documentknowledge.documentmanagement.bootstrap;

import io.micronaut.context.ApplicationContext;
import java.io.InputStream;
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

class VerificationRuntimeBindingTest {
    static final class Manager implements VerificationWorkspacePort {
        int reservations,checks,closes;boolean healthy=true;
        final Workspace workspace=new Workspace(){
            public Buffer source(){throw new AssertionError();}
            public Use retain(CompleteBudget.Phase phase){phase.check();return ()->{};}
            public Buffer scratch(Role role,CompleteBudget.Phase phase){throw new AssertionError();}
            public void finish(CompleteBudget.Phase phase){throw new AssertionError();}
            public void close(){}
        };
        public Workspace reserve(Identity identity,CompleteBudget.Phase phase){phase.check();reservations++;return workspace;}
        public void check(Workspace workspace,CompleteBudget.Phase phase){phase.check();if(workspace!=this.workspace)throw new DmFailure(503,"UNAVAILABLE");checks++;}
        public boolean ready(){return healthy;}
        public void close(){closes++;}
    }
    static final class Connections implements UploadRuntime.Connections {
        final Manager manager=new Manager();int initialized,storageCreated,copies;VerificationWorkspacePort bound;CompleteBudget.Phase forwarded;
        public VerificationWorkspacePort verification(Path root){assertEquals(Path.of("/var/lib/dkp/uploads"),root);initialized++;return manager;}
        public UploadRuntime.Storage storage(S3UploadStorageAdapter.Configuration c){throw new AssertionError("Legacy factory must not be selected");}
        public UploadRuntime.Storage storage(S3UploadStorageAdapter.Configuration c,VerificationWorkspacePort workspace){
            storageCreated++;bound=workspace;
            return new UploadRuntime.Storage(new UploadStoragePort(){
                public ObjectIdentity put(ObjectKey k,Path p,long s,String h){throw new AssertionError();}
                public InputStream open(ObjectIdentity o){throw new AssertionError();}
                public ObjectIdentity copy(ObjectIdentity o,ObjectKey k){throw new AssertionError("Legacy COPY forbidden");}
                public ObjectIdentity copy(ObjectIdentity o,ObjectKey k,CompleteBudget.Phase phase,VerificationWorkspacePort.Workspace owned){assertSame(manager.workspace,owned);forwarded=phase;copies++;return null;}
                public List<ObjectIdentity> versions(ObjectKey k){throw new AssertionError();}
            },()->true,()->{});
        }
        public UploadRuntime.Cleanup cleanup(S3UploadStorageAdapter.Configuration c){return new UploadRuntime.Cleanup(null,()->true,()->{});}
        public UploadRuntime.Scanner scanner(InetSocketAddress e,StandardDefinitions d,Clock c){throw new DmFailure(503,"UNAVAILABLE");}
        public UploadRuntime.Inspector inspector(URI e,SSLContext t,byte[] k,String p,String i,Clock c){throw new AssertionError();}
        public boolean health(URI e,SSLContext t){throw new AssertionError();}
    }
    private Map<String,Object> configuration(){return Map.of("dkp.dm.upload.enabled",true,"dkp.dm.storage.endpoint","https://storage.example:9000/","dkp.dm.storage.region","us-east-1");}
    private UploadRuntime runtime(ApplicationContext context,Connections connections){return new UploadRuntime(context.getEnvironment(),Clock.systemUTC(),(p,m,s)->p.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII),connections);}
    private VerificationWorkspacePort.Identity identity(){return VerificationWorkspacePort.Identity.of("tenant",new Actor("issuer","subject"),UUID.randomUUID(),UUID.randomUUID(),1);}

    @Test void sharedManagerIsLazyAndExactWorkspaceAndPhaseReachStorage(){
        var connections=new Connections();
        try(var context=ApplicationContext.run(configuration());var runtime=runtime(context,connections)){
            var port=runtime.verification();assertSame(port,runtime.verification());assertEquals(0,connections.initialized);
            var budget=new CompleteBudget(Clock.systemUTC());
            try(var phase=budget.phase(CompleteBudget.Kind.STORAGE)){
                var workspace=port.reserve(identity(),phase);assertSame(connections.manager.workspace,workspace);
                runtime.copy(null,null,phase,workspace);
                assertSame(port,connections.bound);assertSame(phase,connections.forwarded);
                assertEquals(1,connections.initialized);assertEquals(1,connections.storageCreated);assertEquals(1,connections.copies);
                assertEquals(1,connections.manager.checks);
            }
            try(var phase=budget.phase(CompleteBudget.Kind.STORAGE)){port.reserve(identity(),phase);}
            assertEquals(1,connections.initialized);
        }
        assertEquals(1,connections.manager.closes);
    }
    @Test void foreignWorkspaceAndExpiredPhaseCannotReachStorageFactory(){
        var connections=new Connections();
        try(var context=ApplicationContext.run(configuration());var runtime=runtime(context,connections)){
            var budget=new CompleteBudget(Clock.systemUTC());var phase=budget.phase(CompleteBudget.Kind.STORAGE);
            assertThrows(DmFailure.class,()->runtime.copy(null,null,phase,new Manager().workspace));
            assertEquals(0,connections.storageCreated);phase.close();
            assertThrows(DmFailure.class,()->runtime.copy(null,null,phase,connections.manager.workspace));
            assertEquals(0,connections.storageCreated);assertEquals(0,connections.copies);
        }
    }
    @Test void retainedOrClosedManagerKeepsReadinessDownAndShutdownDoesNotRecreateIt(){
        var connections=new Connections();
        try(var context=ApplicationContext.run(configuration());var runtime=runtime(context,connections)){
            connections.manager.healthy=false;assertFalse(runtime.ready());assertEquals(0,connections.storageCreated);
            runtime.close();runtime.close();assertEquals(1,connections.manager.closes);assertFalse(runtime.ready());
            var budget=new CompleteBudget(Clock.systemUTC());try(var phase=budget.phase(CompleteBudget.Kind.STORAGE)){assertThrows(DmFailure.class,()->runtime.verification().reserve(identity(),phase));}
            assertEquals(1,connections.initialized);assertEquals(0,connections.storageCreated);
        }
    }
    @Test void disabledAndUnusedShutdownNeverTouchFilesystemOrInitializeManager(){
        var connections=new Connections();
        try(var context=ApplicationContext.run(Map.of());var runtime=runtime(context,connections)){
            assertTrue(runtime.ready());assertFalse(runtime.verification().ready());runtime.close();assertFalse(runtime.ready());
            assertEquals(0,connections.initialized);assertEquals(0,connections.manager.closes);
        }
    }
}
