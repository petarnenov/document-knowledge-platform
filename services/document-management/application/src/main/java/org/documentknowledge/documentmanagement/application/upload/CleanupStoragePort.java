package org.documentknowledge.documentmanagement.application.upload;

import java.util.List;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;

/** Internal fenced cleanup only. Never supplied to upload, content or receipt services. */
public interface CleanupStoragePort {
    List<ObjectIdentity> versions(ObjectKey key);
    UploadStoragePort.DeletionResult deleteOnce(DeletionFence attempt);
}
