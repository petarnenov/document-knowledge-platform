package org.documentknowledge.documentmanagement.domain;
public record AclRule(Principal principal,Action action,Effect effect) {
 public enum Effect { ALLOW,DENY }
 public AclRule { java.util.Objects.requireNonNull(principal); java.util.Objects.requireNonNull(action);java.util.Objects.requireNonNull(effect); }
}
