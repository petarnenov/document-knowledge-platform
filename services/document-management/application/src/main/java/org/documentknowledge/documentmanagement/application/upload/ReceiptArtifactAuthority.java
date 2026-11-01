package org.documentknowledge.documentmanagement.application.upload;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.identity.Actor;
/** Real storage preflight OUTSIDE SQL. Consumption is mandatory in DocumentService's later UoW. */
public final class ReceiptArtifactAuthority implements VerifiedArtifactAuthorityPort {
 private final DocumentUnitOfWork transactions;private final UploadStoragePort storage;private final Clock clock;private final Path temporary;
 public ReceiptArtifactAuthority(DocumentUnitOfWork transactions,UploadStoragePort storage,Clock clock,Path temporary){this.transactions=java.util.Objects.requireNonNull(transactions);this.storage=java.util.Objects.requireNonNull(storage);this.clock=java.util.Objects.requireNonNull(clock);this.temporary=java.util.Objects.requireNonNull(temporary);}
 @Override public VerifiedArtifact verify(Actor actor,String tenant,UUID document,String identifier){
  UUID id;try{id=UUID.fromString(identifier);}catch(IllegalArgumentException bad){throw UploadPolicy.hidden();}
  var receipt=transactions.execute(r->{var u=r.uploads();var value=u.receipt(tenant,id).orElseThrow(UploadPolicy::hidden);if(!value.tenant().equals(tenant)||!value.documentId().equals(document)||!value.actor().issuer().equals(actor.issuer())||!value.actor().subject().equals(actor.subject()))throw UploadPolicy.hidden();if(!clock.instant().isBefore(value.expiresAt()))throw new DmFailure(409,"UPLOAD_EXPIRED");return value;});
  try(var input=storage.open(receipt.original());var source=UploadSupport.spool(input,temporary,receipt.sizeBytes(),clock,60)){
   if(!source.sha256().equals(receipt.sha256()))throw UploadPolicy.unavailable();
   return new VerifiedArtifact(receipt.sha256(),receipt.format().mime(),receipt.receiptId().toString(),"dkp-upload-v1");
  }catch(java.io.IOException failure){throw UploadPolicy.unavailable();}
 }
}
