package org.documentknowledge.documentmanagement.adapters.outbound.generictemp;
import java.nio.file.Path;
import com.sun.security.auth.module.UnixSystem;
import org.documentknowledge.authorization.TrustedAuthorityFiles;
import org.documentknowledge.documentmanagement.application.upload.GenericTempPort;
/** Explicit host-unit platform seam; production always selects SYSTEM, never a runtime override. */
public final class RealGenericTempFixture {
 private RealGenericTempFixture(){}
 public static GenericTempPort open(Path root){return new FileGenericTemp(root,new TrustedAuthorityFiles(new TrustedAuthorityFiles.Platform("Linux",false,new UnixSystem().getUid())));}
}
