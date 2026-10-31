package org.documentknowledge.documentmanagement.application;
public final class DmFailure extends RuntimeException {
 private final int status;private final String code;
 public DmFailure(int status,String code){super(code);this.status=status;this.code=code;}
 public int status(){return status;}public String code(){return code;}
}
