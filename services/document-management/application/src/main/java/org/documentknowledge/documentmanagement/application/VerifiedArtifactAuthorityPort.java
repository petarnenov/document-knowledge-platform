package org.documentknowledge.documentmanagement.application;
import java.util.UUID;
import org.documentknowledge.identity.Actor;
@FunctionalInterface public interface VerifiedArtifactAuthorityPort {
 VerifiedArtifact verify(Actor actor,String tenant,UUID documentId,String receiptId);
 static VerifiedArtifactAuthorityPort unavailable(){return (actor,tenant,id,receipt)->{throw new DmFailure(503,"UNAVAILABLE");};}
 record VerifiedArtifact(String sha256,String mimeType,String originalArtifactRef,String producer) { }
}
