package org.documentknowledge.documentmanagement.application;
import java.util.function.Function;
@FunctionalInterface public interface DocumentUnitOfWork {
 <T> T execute(Function<DocumentRepository,T> work);
}
