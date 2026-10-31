package org.documentknowledge.documentmanagement.application;
import org.documentknowledge.identity.Actor;
public record CommandKey(Actor actor,String tenant,String operation,String resource,String idempotencyKey) { }
