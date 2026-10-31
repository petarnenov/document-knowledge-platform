package org.documentknowledge.documentmanagement.application;
import java.time.Instant;
import java.util.UUID;
public record PagePosition(Instant createdAt,UUID documentId) { }
