package org.documentknowledge.documentmanagement.application.upload;

import java.nio.file.Path;
import java.time.Clock;
import java.util.function.Consumer;
import org.documentknowledge.documentmanagement.application.DocumentUnitOfWork;
import org.documentknowledge.identity.ResourceAuthorizationPort;

/** Test-only bridge to the package-private cleanup timing seam; no runtime configuration override. */
public final class UploadServiceTestFactory {
 private UploadServiceTestFactory(){}
 public static UploadService create(DocumentUnitOfWork transactions,ResourceAuthorizationPort grants,UploadStoragePort storage,
     MalwareScanPort scanner,StructureInspectionPort inspector,Clock clock,Path root,Consumer<Path> cleanup){
  return new UploadService(transactions,grants,storage,scanner,inspector,clock,root,cleanup);
 }
}
