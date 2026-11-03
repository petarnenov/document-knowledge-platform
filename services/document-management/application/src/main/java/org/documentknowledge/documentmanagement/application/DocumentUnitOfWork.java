package org.documentknowledge.documentmanagement.application;
import java.util.function.Function;
@FunctionalInterface public interface DocumentUnitOfWork {
 <T> T execute(Function<DocumentRepository,T> work);
 default <T> T execute(DiagnosticContext context,Function<DocumentRepository,T> work){java.util.Objects.requireNonNull(context);return execute(work);}
}
