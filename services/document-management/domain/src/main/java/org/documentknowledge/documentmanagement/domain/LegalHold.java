package org.documentknowledge.documentmanagement.domain;
import java.time.Instant;
import java.util.UUID;
public record LegalHold(UUID holdId,String reason,Principal placedBy,Instant placedAt) {
 public LegalHold { java.util.Objects.requireNonNull(holdId);Validation.text(reason,1024,false);java.util.Objects.requireNonNull(placedBy);java.util.Objects.requireNonNull(placedAt); }
}
