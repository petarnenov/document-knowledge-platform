package org.documentknowledge.documentmanagement.adapters.outbound.upload;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;
import org.junit.jupiter.api.Test;

class NativeCapabilityForwardingTest {
 @Test void nativeDecoratorForwardsIdenticalPhaseAndWorkspaceWithoutSelectingLegacyFallback()throws Exception{
  try(var phase=new CompleteBudget(Clock.systemUTC()).phase(CompleteBudget.Kind.STORAGE)){
   var workspace=(VerificationWorkspacePort.Workspace)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{VerificationWorkspacePort.Workspace.class},(p,m,a)->{throw new AssertionError("Decorator must not consume workspace");});
   var calls=new AtomicInteger();
   var delegate=(UploadStoragePort)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{UploadStoragePort.class},(p,m,a)->{
    assertEquals(m.getName().equals("open")?2:4,a.length,"Native decorator selected a legacy method");assertSame(phase,a[m.getName().equals("open")?1:2]);calls.incrementAndGet();
    if(m.getName().equals("open")){assertEquals(2,a.length);return new ByteArrayInputStream(new byte[]{1});}
    assertEquals("copy",m.getName());assertEquals(4,a.length);assertSame(workspace,a[3]);return new ObjectIdentity((ObjectKey)a[1],"version");
   });
   var cleanup=(CleanupStoragePort)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{CleanupStoragePort.class},(p,m,a)->{throw new AssertionError("No cleanup dispatch");});
   var wrapper=new RealUploadPersistenceStorageAcceptance.Forwarding(delegate,cleanup);
   var key=new ObjectKey("dkp-quarantine","a".repeat(64)+"/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222");
   var target=new ObjectKey("dkp-originals",key.key());var object=new ObjectIdentity(key,"source-version");
   try(var input=wrapper.open(object,phase)){assertEquals(1,input.read());}
   assertEquals(new ObjectIdentity(target,"version"),wrapper.copy(object,target,phase,workspace));assertEquals(2,calls.get());
  }
 }
 @Test void nativeDecoratorPreservesExactTransferAndPreappendBudget()throws Exception{
  var calls=new AtomicInteger();var forwarded=new java.util.concurrent.atomic.AtomicReference<GenericTempBudget>();
  var delegate=(UploadStoragePort)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{UploadStoragePort.class},(p,m,a)->{
   assertTrue(m.getName().equals("put")||m.getName().equals("open"));assertEquals(m.getName().equals("put")?5:2,a.length);
   forwarded.set((GenericTempBudget)a[a.length-1]);calls.incrementAndGet();return m.getName().equals("open")?new ByteArrayInputStream(new byte[]{7}):null;
  });
  var cleanup=(CleanupStoragePort)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{CleanupStoragePort.class},(p,m,a)->{throw new AssertionError();});
  var wrapper=new RealUploadPersistenceStorageAcceptance.Forwarding(delegate,cleanup);
  var transfer=new GenericTempBudget(Clock.systemUTC(),GenericTempPort.Role.TRANSFER);wrapper.put(null,null,1,"hash",transfer);assertSame(transfer,forwarded.get());
  var append=new GenericTempBudget(Clock.systemUTC(),GenericTempPort.Role.PREAPPEND);try(var input=wrapper.open(null,append)){assertEquals(7,input.read());}assertSame(append,forwarded.get());assertEquals(2,calls.get());
 }

}
