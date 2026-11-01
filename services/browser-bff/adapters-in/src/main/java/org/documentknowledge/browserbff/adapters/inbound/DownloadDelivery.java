package org.documentknowledge.browserbff.adapters.inbound;

import io.micronaut.http.HttpRequest;
import java.io.InputStream;
import org.documentknowledge.browserbff.application.UploadGateway;

/** Presentation stream ownership; no domain permission or infrastructure contract. */
public interface DownloadDelivery {
    Reservation reserve(HttpRequest<?> request);

    interface Reservation extends AutoCloseable {
        Body attach(UploadGateway.StreamResult result, InputStream authorizedSource);
        /** The controller has finished dispatch; cancel an attached source or certify none was acquired. */
        @Override void close();
    }

    record Body(long length, Reservation owner) { }
}
