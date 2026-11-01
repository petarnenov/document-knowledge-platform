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
 /** Deadline-aware COMPLETE operations; implementations abort only their own invocation. */
 default InputStream open(ObjectIdentity object,CompleteBudget.Phase phase){phase.check();var result=open(object);try{phase.check();return result;}catch(RuntimeException failure){try{result.close();}catch(java.io.IOException ignored){}throw failure;}}
 default ObjectIdentity copy(ObjectIdentity source,ObjectKey target,CompleteBudget.Phase phase){phase.check();return copy(source,target);}
 /** Recovery only: writer settlement must be proven before cleanup can consume these identities. */
 List<ObjectIdentity> versions(ObjectKey key);
 record DeletionResult(UUID attemptId,ObjectIdentity object,String requestId,boolean terminal,
     boolean noFurtherExecution,boolean absent,boolean successful) { }
}
