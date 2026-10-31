package org.documentknowledge.documentmanagement.application;
import java.time.Instant;
import java.util.UUID;
import org.documentknowledge.identity.Actor;
public record AuditFact(UUID eventId,UUID documentId,String tenant,Actor actor,String action,long revision,Instant occurredAt) { }
