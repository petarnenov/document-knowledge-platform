package org.documentknowledge.documentmanagement.application.upload;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.identity.Actor;

/** Distinct durable TRANSFER/PREAPPEND reservations. No role conveys document authority. */
public interface GenericTempPort extends AutoCloseable {
 enum Role {TRANSFER,PREAPPEND}
 record Identity(String tenantDigest,String actorDigest,UUID documentId,UUID uploadId,UUID receiptId,long declaredSize){
  public Identity{new VerificationWorkspacePort.Identity(tenantDigest,actorDigest,documentId,uploadId,declaredSize);}
  public static Identity of(String tenant,Actor actor,UUID document,UUID upload,UUID receipt,long size){var value=VerificationWorkspacePort.Identity.of(tenant,actor,document,upload,size);return new Identity(value.tenantDigest(),value.actorDigest(),document,upload,receipt,size);}
 }
 record ArtifactFile(Path path,long size,String sha256){public ArtifactFile{Objects.requireNonNull(path);if(size<1||size>100_000_000||sha256==null||!sha256.matches("[0-9a-f]{64}"))throw unavailableFailure();}}
 Workspace reserve(Role role,Identity identity,GenericTempBudget budget);
 boolean ready();
 @Override void close();
 interface Workspace extends AutoCloseable {
  ArtifactFile write(InputStream input,GenericTempBudget budget);
  Use retain(GenericTempBudget budget);
  interface Use extends AutoCloseable{@Override void close();}
  void finish(GenericTempBudget budget);
  /** Inactive marker only; never an implicit payload unlink or physical capacity release. */
  @Override void close();
 }
 static GenericTempPort unavailable(){return new GenericTempPort(){public Workspace reserve(Role role,Identity identity,GenericTempBudget budget){throw unavailableFailure();}public boolean ready(){return false;}public void close(){}};}
 private static DmFailure unavailableFailure(){return new DmFailure(503,"UNAVAILABLE");}
}
