package org.documentknowledge.documentmanagement.domain;
public record Principal(String issuer, String subject) {
 public Principal { Validation.text(issuer,2048,false); Validation.text(subject,256,false); }
}
