package org.documentknowledge.browserbff.application;

public record SecurityContext(String service, String issuer, String subject, String tenant) { }
