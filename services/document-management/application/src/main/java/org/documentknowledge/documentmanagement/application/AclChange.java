package org.documentknowledge.documentmanagement.application;
import java.util.List;
import java.util.Objects;
import org.documentknowledge.documentmanagement.domain.Action;
import org.documentknowledge.documentmanagement.domain.Principal;
public record AclChange(Mode mode,List<Entry> changes) {
 public enum Mode { RESTRICT,DELEGATE }
 public enum Operation { ADD_ALLOW,REMOVE_ALLOW,ADD_DENY,REMOVE_DENY }
 public record Entry(Operation operation,Principal principal,Action action){public Entry{Objects.requireNonNull(operation);Objects.requireNonNull(principal);Objects.requireNonNull(action);}}
 public AclChange {Objects.requireNonNull(mode);changes=List.copyOf(changes);if(changes.isEmpty()||changes.size()>100)throw new DmFailure(422,"INVALID_ACL_CHANGE");}
}
