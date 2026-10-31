package org.documentknowledge.documentmanagement.domain;
public record Metadata(String title,String description) {
 public Metadata { Validation.text(title,256,false); Validation.text(description,4096,true); }
}
