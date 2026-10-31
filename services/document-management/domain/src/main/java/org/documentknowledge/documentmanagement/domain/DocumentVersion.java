package org.documentknowledge.documentmanagement.domain;
import java.time.Instant;
import java.util.UUID;
public record DocumentVersion(UUID versionId,UUID documentId,int versionNumber,String sha256,String mimeType,String originalArtifactRef,String producer,Principal creator,Instant createdAt,String submittedClassification) {
 public DocumentVersion {java.util.Objects.requireNonNull(versionId);java.util.Objects.requireNonNull(documentId);if(versionNumber<1 || sha256==null || !sha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid version");Validation.text(mimeType,256,false);Validation.text(originalArtifactRef,2048,false);Validation.text(producer,256,false);java.util.Objects.requireNonNull(creator);java.util.Objects.requireNonNull(createdAt);if(!"development-restricted".equals(submittedClassification))throw new IllegalArgumentException("Invalid classification");}
 public DocumentVersion(UUID id,UUID documentId,int number,String hash,String mime,String ref,String producer,Principal creator,Instant time){this(id,documentId,number,hash,mime,ref,producer,creator,time,"development-restricted");}
 /** Review decisions are separate mutable facts, never a mutation of immutable version content. */
 public String initialReviewState(){return "UNREVIEWED";}
}
