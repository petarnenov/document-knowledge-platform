package org.documentknowledge.documentmanagement.application.upload;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.identity.Actor;
/** Real storage preflight OUTSIDE SQL. Consumption is mandatory in DocumentService's later UoW. */
public final class ReceiptArtifactAuthority implements VerifiedArtifactAuthorityPort {
 private final DocumentUnitOfWork transactions;private final UploadStoragePort storage;private final Clock clock;private final GenericTempPort genericTemps;
 public ReceiptArtifactAuthority(DocumentUnitOfWork transactions,UploadStoragePort storage,Clock clock,Path temporary){this(transactions,storage,clock,GenericTempPort.unavailable());java.util.Objects.requireNonNull(temporary);}
 public ReceiptArtifactAuthority(DocumentUnitOfWork transactions,UploadStoragePort storage,Clock clock,GenericTempPort genericTemps){this.transactions=java.util.Objects.requireNonNull(transactions);this.storage=java.util.Objects.requireNonNull(storage);this.clock=java.util.Objects.requireNonNull(clock);this.genericTemps=java.util.Objects.requireNonNull(genericTemps);}
 @Override public VerifiedArtifact verify(Actor actor,String tenant,UUID document,String identifier){
  var budget=new GenericTempBudget(clock,GenericTempPort.Role.PREAPPEND);return budget.execute(()->verifyBounded(actor,tenant,document,identifier,budget));
 }
 private VerifiedArtifact verifyBounded(Actor actor,String tenant,UUID document,String identifier,GenericTempBudget budget){
  budget.check();
  UUID id;try{id=UUID.fromString(identifier);}catch(IllegalArgumentException bad){throw UploadPolicy.hidden();}
  var receipt=transactions.execute(r->{budget.check();var u=r.uploads();var value=u.receipt(tenant,id).orElseThrow(UploadPolicy::hidden);if(!value.tenant().equals(tenant)||!value.documentId().equals(document)||!value.actor().issuer().equals(actor.issuer())||!value.actor().subject().equals(actor.subject()))throw UploadPolicy.hidden();if(!clock.instant().isBefore(value.expiresAt()))throw new DmFailure(409,"UPLOAD_EXPIRED");return value;});
  budget.check();
  try(var workspace=genericTemps.reserve(GenericTempPort.Role.PREAPPEND,GenericTempPort.Identity.of(tenant,actor,document,receipt.uploadId(),receipt.receiptId(),receipt.sizeBytes()),budget)){
   boolean cleanupAttempted=false;
   try {
    GenericTempPort.ArtifactFile source;
    try(var input=storage.open(receipt.original(),budget);var abort=budget.onCancel(()->{try{input.close();}catch(java.io.IOException ignored){}})){source=workspace.write(input,budget);budget.check();}
    if(!source.sha256().equals(receipt.sha256()))throw UploadPolicy.unavailable();
    cleanupAttempted=true;workspace.finish(budget);budget.check();
    return new VerifiedArtifact(receipt.sha256(),receipt.format().mime(),receipt.receiptId().toString(),"dkp-upload-v1");
   }catch(java.io.IOException|RuntimeException failure){if(!cleanupAttempted){cleanupAttempted=true;workspace.finish(budget);}throw failure instanceof RuntimeException runtime?runtime:UploadPolicy.unavailable();}
  }
 }
}
