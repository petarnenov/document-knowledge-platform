package org.documentknowledge.documentmanagement.application;
import org.documentknowledge.identity.Actor;
public interface DocumentCursorPort {
 PagePosition decode(String cursor,Actor actor,String tenant);
 String encode(PagePosition position,Actor actor,String tenant);
}
