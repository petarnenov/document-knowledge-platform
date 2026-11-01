package org.documentknowledge.documentmanagement.adapters.outbound.storage;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.documentknowledge.documentmanagement.application.upload.VerificationWorkspacePort;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** Test-only loss of an already-dispatched PUT response. Never delays dispatch until after expiry.
 * The production adapter still owns its actual input stream and Workspace.Use throughout the barrier.
 */
public final class RealPutResponseBarrier implements AutoCloseable {
    private final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    private final AtomicInteger dispatched=new AtomicInteger();
    private final S3UploadStorageAdapter storage;
    public static RealPutResponseBarrier connect(S3UploadStorageAdapter.Configuration configuration,VerificationWorkspacePort workspaces){
        return new RealPutResponseBarrier(S3UploadStorageAdapter.client(configuration,"normal"),configuration.privateSpoolRoot(),workspaces);
    }
    // Java-only transport seam: native connect above always delegates to the genuine scoped SDK client.
    RealPutResponseBarrier(S3Client actual,java.nio.file.Path root,VerificationWorkspacePort workspaces){
        S3Client transport=(S3Client)Proxy.newProxyInstance(S3Client.class.getClassLoader(),new Class<?>[]{S3Client.class},(proxy,method,args)->{
            Object result;
            try{result=method.invoke(actual,args);}catch(InvocationTargetException failure){throw failure.getCause();}
            if(method.getName().equals("putObject")&&args[0] instanceof PutObjectRequest request&&request.bucket().equals("dkp-originals")){
                if(dispatched.incrementAndGet()!=1)throw new AssertionError("Unexpected duplicate real PUT");
                entered.countDown();
                // Ignore coordinator interruption: it cannot prove remote settlement. Always finite.
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);boolean interrupted=false;
                try{while(release.getCount()!=0){long left=deadline-System.nanoTime();if(left<=0)throw new AssertionError("Owned response barrier timed out");try{release.await(left,TimeUnit.NANOSECONDS);}catch(InterruptedException cancellation){interrupted=true;}}}
                finally{if(interrupted)Thread.currentThread().interrupt();}
            }
            return result;
        });
        storage=new S3UploadStorageAdapter(transport,root,workspaces);
    }
    public S3UploadStorageAdapter storage(){return storage;}
    public CountDownLatch entered(){return entered;}
    public CountDownLatch release(){return release;}
    public int dispatched(){return dispatched.get();}
    @Override public void close(){release.countDown();storage.close();}
}
