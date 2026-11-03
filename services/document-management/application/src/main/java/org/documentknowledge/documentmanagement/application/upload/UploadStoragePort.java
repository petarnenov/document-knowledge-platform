package org.documentknowledge.documentmanagement.application.upload;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;
/** All identities server-derived. Implementations MUST NOT automatically retry versioned writes. */
public interface UploadStoragePort {
 ObjectIdentity put(ObjectKey key,Path source,long sizeBytes,String sha256);
 InputStream open(ObjectIdentity object);
 ObjectIdentity copy(ObjectIdentity source,ObjectKey target);
 /** Generic role budgets must be forwarded exactly; no path-only legacy fallback. */
 default ObjectIdentity put(ObjectKey key,Path source,long sizeBytes,String sha256,GenericTempBudget budget){throw new org.documentknowledge.documentmanagement.application.DmFailure(503,"UNAVAILABLE");}
 default InputStream open(ObjectIdentity object,GenericTempBudget budget){throw new org.documentknowledge.documentmanagement.application.DmFailure(503,"UNAVAILABLE");}
 /** Deadline-aware COMPLETE operations; implementations abort only their own invocation. */
 default InputStream open(ObjectIdentity object,CompleteBudget.Phase phase){phase.check();var result=open(object);try{phase.check();return result;}catch(RuntimeException failure){try{result.close();}catch(java.io.IOException ignored){}throw failure;}}
 default ObjectIdentity copy(ObjectIdentity source,ObjectKey target,CompleteBudget.Phase phase){phase.check();return copy(source,target);}
 /** COMPLETE copy must use the shared durable verification workspace; legacy implementations fail closed. */
 default ObjectIdentity copy(ObjectIdentity source,ObjectKey target,CompleteBudget.Phase phase,VerificationWorkspacePort.Workspace workspace){throw new org.documentknowledge.documentmanagement.application.DmFailure(503,"UNAVAILABLE");}
 /** Recovery only: writer settlement must be proven before cleanup can consume these identities. */
 List<ObjectIdentity> versions(ObjectKey key);
 /** Per-invocation raw failure/status observations; no callback is installed on a singleton client. */
 default ObjectIdentity put(ObjectKey key,Path source,long size,String hash,GenericTempBudget budget,UploadDiagnosticsPort.FailureObserver observer){return put(key,source,size,hash,budget);}
 default InputStream open(ObjectIdentity object,UploadDiagnosticsPort.FailureObserver observer){return open(object);}
 default InputStream open(ObjectIdentity object,GenericTempBudget budget,UploadDiagnosticsPort.FailureObserver observer){return open(object,budget);}
 default InputStream open(ObjectIdentity object,CompleteBudget.Phase phase,UploadDiagnosticsPort.FailureObserver observer){return open(object,phase);}
 default ObjectIdentity copy(ObjectIdentity source,ObjectKey target,CompleteBudget.Phase phase,VerificationWorkspacePort.Workspace workspace,UploadDiagnosticsPort.FailureObserver observer){return copy(source,target,phase,workspace);}
 default List<ObjectIdentity> versions(ObjectKey key,UploadDiagnosticsPort.FailureObserver observer){return versions(key);}
 record DeletionResult(UUID attemptId,ObjectIdentity object,String requestId,boolean terminal,
     boolean noFurtherExecution,boolean absent,boolean successful) { }
}
