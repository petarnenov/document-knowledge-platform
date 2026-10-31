package org.documentknowledge.authorization;

import com.sun.security.auth.module.UnixSystem;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TrustedAuthorityFilesTest {
 @TempDir Path directory;
 long uid(){return new UnixSystem().getUid();}
 TrustedAuthorityFiles linux(){return new TrustedAuthorityFiles(new TrustedAuthorityFiles.Platform("Linux",false,uid()));}
 Path file()throws Exception{Path root=directory.toRealPath();Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));Path file=root.resolve("key");Files.writeString(file,"secret");Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));return file;}
 @Test void boundedPrivateReadsAndNormalization()throws Exception{
  Path p=file();assertEquals("secret",new String(linux().readBounded(p,6,true)));
  for(Path invalid:new Path[]{null,Path.of("relative"),p.resolveSibling("missing"),p.getParent(),p.getParent().resolve(".").resolve("key"),Path.of("/")})assertThrows(IOException.class,()->linux().readBounded(invalid,10,true));
  assertThrows(IOException.class,()->linux().readBounded(p,0,true));assertThrows(IOException.class,()->linux().readBounded(p,5,true));
  Files.writeString(p,"x".repeat(8193));assertEquals(8193,linux().readBounded(p,9000,true).length);
  Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-r-----"));assertThrows(IOException.class,()->linux().readBounded(p,9000,true));assertEquals(8193,linux().readBounded(p,9000,false).length);
 }
 @Test void everyAncestorRejectsGroupOtherWriteStickyAndSymlinks()throws Exception{
  Path p=file();Path grand=p.getParent();
  for(int mode:new int[]{0770,0702,01700}){
   Files.setAttribute(grand,"unix:mode",mode);assertThrows(IOException.class,()->linux().readBounded(p,100,false));Files.setAttribute(grand,"unix:mode",0700);
  }
  Path link=grand.resolve("file-link");Files.createSymbolicLink(link,p);assertThrows(IOException.class,()->linux().readBounded(link,100,false));
  Path parentLink=grand.resolve("parent-link");Files.createSymbolicLink(parentLink,grand);assertThrows(IOException.class,()->linux().readBounded(parentLink.resolve("key"),100,false));
  Files.setAttribute(p,"unix:mode",0620);assertThrows(IOException.class,()->linux().readBounded(p,100,false));
 }
 @Test void untrustedOwnerAndUnsupportedAclPlatformsFailClosed()throws Exception{
  Path p=file();var foreign=new TrustedAuthorityFiles(new TrustedAuthorityFiles.Platform("Linux",false,uid()+10000));assertThrows(IOException.class,()->foreign.readBounded(p,100,false));
  var mac=new TrustedAuthorityFiles(new TrustedAuthorityFiles.Platform("Mac OS X",false,uid()));assertThrows(IOException.class,()->mac.readBounded(p,100,false));
  var absentView=new TrustedAuthorityFiles(new TrustedAuthorityFiles.Platform("NonLinux",true,uid()));if(Files.getFileAttributeView(p,AclFileAttributeView.class)==null)assertThrows(IOException.class,()->absentView.readBounded(p,100,false));
  if(!System.getProperty("os.name").equalsIgnoreCase("Linux") && !FileSystems.getDefault().supportedFileAttributeViews().contains("acl"))assertThrows(IOException.class,()->TrustedAuthorityFiles.read(p,100,false));
 }
 @Test void aclPolicyAcceptsTrustedOwnersAndDenialsButRejectsForeignMutation()throws Exception{
  UserPrincipal owner=()->"owner",root=()->"root",other=()->"other";
  for(AclEntryPermission permission:List.of(AclEntryPermission.WRITE_DATA,AclEntryPermission.APPEND_DATA,AclEntryPermission.DELETE,AclEntryPermission.DELETE_CHILD,AclEntryPermission.WRITE_ACL,AclEntryPermission.WRITE_OWNER,AclEntryPermission.WRITE_ATTRIBUTES,AclEntryPermission.WRITE_NAMED_ATTRS)){
   AclEntry unsafe=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(other).setPermissions(permission).build();assertThrows(IOException.class,()->TrustedAuthorityFiles.validateAcl(List.of(unsafe),owner,root));
  }
  var read=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(other).setPermissions(AclEntryPermission.READ_DATA).build();
  var deny=AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(other).setPermissions(AclEntryPermission.WRITE_DATA).build();
  var own=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(AclEntryPermission.WRITE_DATA).build();
  var admin=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(root).setPermissions(AclEntryPermission.WRITE_DATA).build();
  TrustedAuthorityFiles.validateAcl(List.of(read,deny,own,admin),owner,root);TrustedAuthorityFiles.validateAcl(List.of(),owner,root);
 }
}
