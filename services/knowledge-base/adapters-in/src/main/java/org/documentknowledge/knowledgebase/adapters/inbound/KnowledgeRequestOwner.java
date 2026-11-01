package org.documentknowledge.knowledgebase.adapters.inbound;

import io.micronaut.http.HttpRequest;
import org.documentknowledge.knowledgebase.application.Deadline;

/** Transport-owned cancellation capability; it never supplies identity or repository access. */
public interface KnowledgeRequestOwner {
    void begin(HttpRequest<?> request,Deadline deadline);
    org.reactivestreams.Publisher<io.micronaut.http.MutableHttpResponse<?>> supervise(HttpRequest<?> request,Deadline deadline,org.reactivestreams.Publisher<io.micronaut.http.MutableHttpResponse<?>> downstream);
    Lease enter(HttpRequest<?> request,Deadline deadline);
    void finish(HttpRequest<?> request);
    interface Lease extends AutoCloseable { @Override void close(); }
}
