package org.documentknowledge.documentmanagement.domain;
public enum Action {
 CREATE("document.create"), INSPECT_DRAFT("document.inspect-draft"), READ("document.read"), UPDATE_METADATA("document.update-metadata"), ACL_RESTRICT("document.acl.restrict"), ACL_DELEGATE("document.acl.delegate"), VERSION_APPEND("document.version.append"), HOLD_PLACE("document.legal-hold.place"), HOLD_INSPECT("document.legal-hold.inspect");
 private final String wire;
 Action(String wire){this.wire=wire;}
 public String wire(){return wire;}
 public static Action parse(String wire){for(Action value:values())if(value.wire.equals(wire))return value;throw new IllegalArgumentException("Unknown action");}
}
