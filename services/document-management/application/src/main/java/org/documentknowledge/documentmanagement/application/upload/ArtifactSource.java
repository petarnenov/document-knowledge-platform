package org.documentknowledge.documentmanagement.application.upload;
import java.nio.file.Path;
import java.util.Objects;
import org.documentknowledge.documentmanagement.domain.upload.UploadFormat;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels;
/** Caller-owned bounded private spool, never a browser path. Adapter opens bytes only. */
public record ArtifactSource(Path path,UploadFormat format,long sizeBytes,String sha256) {
 public ArtifactSource {Objects.requireNonNull(path);Objects.requireNonNull(format);UploadModels.size(sizeBytes);UploadModels.hash(sha256);}
}
