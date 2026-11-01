package org.documentknowledge.browserbff.adapters.inbound;
import io.micronaut.http.HttpRequest;
import org.documentknowledge.browserbff.application.KnowledgeDeadline;
/** Only transport cancellation; no identity, destination, result or business capability. */
public interface KnowledgeTransportOwner {
    Binding attach(HttpRequest<?> request,KnowledgeDeadline deadline);
    interface Binding extends AutoCloseable { @Override void close(); }
}
