package org.documentknowledge.documentmanagement.application;
import java.util.List;
import org.documentknowledge.documentmanagement.domain.DocumentAggregate;
public record DocumentPage(List<DocumentAggregate> items,String nextCursor){public DocumentPage{items=List.copyOf(items);}}
