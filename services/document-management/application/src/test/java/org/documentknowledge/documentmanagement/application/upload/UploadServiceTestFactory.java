package org.documentknowledge.documentmanagement.application.upload;

import java.io.InputStream;
import java.nio.file.*;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Consumer;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.identity.ResourceAuthorizationPort;

/** Explicit UNIT workspace: actual temp files, in-memory ownership; NOT durable/native resource evidence. */
public final class UploadServiceTestFactory {
 private UploadServiceTestFactory(){}
 public static UploadService create(DocumentUnitOfWork transactions,ResourceAuthorizationPort grants,UploadStoragePort storage,
     MalwareScanPort scanner,StructureInspectionPort inspector,Clock clock,Path root,VerificationWorkspacePort workspace){
  return new UploadService(transactions,grants,storage,scanner,inspector,clock,root,workspace);
 }
 public static VerificationWorkspacePort workspace(Path root,Clock clock,Consumer<Path> cleanup){return new UnitWorkspace(root,clock,cleanup);}
 private static final class UnitWorkspace implements VerificationWorkspacePort {
  final Path root;final Clock clock;final Consumer<Path> cleanup;UnitOperation current;boolean active,closed;
  UnitWorkspace(Path root,Clock clock,Consumer<Path> cleanup){this.root=root;this.clock=clock;this.cleanup=cleanup;}
  public Workspace reserve(Identity identity,CompleteBudget.Phase phase){phase.check();if(closed)throw unavailable();if(active)throw new DmFailure(429,"QUOTA_EXCEEDED");if(current!=null){current.erase(phase);current=null;}current=new UnitOperation(identity);active=true;return current;}
  public boolean ready(){return !closed;}
  public void check(Workspace workspace,CompleteBudget.Phase phase){phase.check();if(closed||workspace!=current||!active)throw unavailable();}
  public void close(){closed=true;}
  final class UnitOperation implements Workspace {
   final Identity identity;final UnitBuffer source;UnitBuffer scratch;boolean ended;Role role;
   UnitOperation(Identity identity){this.identity=identity;source=new UnitBuffer(true);}
   void valid(){if(closed||ended||current!=this||!active)throw unavailable();}
   public Buffer source(){valid();return source;}
   public Use retain(CompleteBudget.Phase phase){valid();phase.check();return ()->{};}
   public Buffer scratch(Role next,CompleteBudget.Phase phase){valid();phase.check();if(scratch!=null&&!scratch.absent()||next==Role.READBACK&&role!=Role.COPY||next==Role.COPY&&role!=null)throw unavailable();role=next;scratch=new UnitBuffer(false);return scratch;}
   public void finish(CompleteBudget.Phase phase){valid();erase(phase);phase.check();current=null;}
   void erase(CompleteBudget.Phase phase){phase.check();if(scratch!=null)scratch.cleanup(phase);source.cleanup(phase);phase.check();}
   public void close(){ended=true;active=false;}
   final class UnitBuffer implements Buffer {
    final boolean sourceRole;UploadSupport.Spool spool;boolean attempted;
    UnitBuffer(boolean source){sourceRole=source;}
    public ArtifactFile write(InputStream input,CompleteBudget.Phase phase){valid();phase.check();if(spool!=null)throw unavailable();spool=UploadSupport.spool(input,root,identity.declaredSize(),clock,60);phase.check();return new ArtifactFile(spool.file(),spool.size(),spool.sha256());}
    boolean absent(){return spool==null||!Files.exists(spool.file());}
    public void cleanup(CompleteBudget.Phase phase){phase.check();if(absent())return;if(attempted&&active)throw unavailable();attempted=true;if(sourceRole)cleanup.accept(spool.file());else UploadSupport.remove(spool.file());phase.check();}
   }
  }
 }
 private static DmFailure unavailable(){return new DmFailure(503,"UNAVAILABLE");}
}
