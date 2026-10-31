package org.documentknowledge.documentmanagement.domain;
public final class Validation {
 private Validation() { }
 public static String text(String value,int max,boolean empty) {
  if(value==null || value.length()>max || (!empty && value.isBlank()) || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid text");
  return value;
 }
}
