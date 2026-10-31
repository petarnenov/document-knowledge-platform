package org.documentknowledge.documentmanagement.application;
import java.util.UUID;
public record CommandReceipt(UUID documentId,long revision) { }
