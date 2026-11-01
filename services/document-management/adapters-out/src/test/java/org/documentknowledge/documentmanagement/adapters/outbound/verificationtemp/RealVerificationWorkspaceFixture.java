package org.documentknowledge.documentmanagement.adapters.outbound.verificationtemp;

import com.sun.security.auth.module.UnixSystem;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.documentknowledge.authorization.TrustedAuthorityFiles;
import org.documentknowledge.documentmanagement.application.upload.*;

/** Test-only Linux descriptor plus optional failure hook; no production configuration path. */
public final class RealVerificationWorkspaceFixture {
 private RealVerificationWorkspaceFixture(){}
 public static FileVerificationWorkspace open(Path root){return new FileVerificationWorkspace(root,new TrustedAuthorityFiles(new TrustedAuthorityFiles.Platform("Linux",false,new UnixSystem().getUid())));}
 public static VerificationWorkspacePort create(Path root,Consumer<Path> beforeSourceCleanup){
  var actual=open(root);
  return new VerificationWorkspacePort(){
   public Workspace reserve(Identity identity,CompleteBudget.Phase phase){var operation=actual.reserve(identity,phase);return new Workspace(){
    Path source;
    public Buffer source(){var buffer=operation.source();return new Buffer(){public ArtifactFile write(java.io.InputStream input,CompleteBudget.Phase p){var value=buffer.write(input,p);source=value.path();return value;}public void cleanup(CompleteBudget.Phase p){buffer.cleanup(p);}};}
    public Use retain(CompleteBudget.Phase p){return operation.retain(p);}
    public Buffer scratch(Role role,CompleteBudget.Phase p){return operation.scratch(role,p);}
    public void finish(CompleteBudget.Phase p){if(source!=null&&beforeSourceCleanup!=null)beforeSourceCleanup.accept(source);operation.finish(p);}
    public void close(){operation.close();}
   };}
   public boolean ready(){return actual.ready();}
   public void check(Workspace workspace,CompleteBudget.Phase phase){throw new AssertionError("Fixture wraps operation capabilities; fake S3 does not select a real storage transport");}
   public void close(){actual.close();}
  };
 }
}
