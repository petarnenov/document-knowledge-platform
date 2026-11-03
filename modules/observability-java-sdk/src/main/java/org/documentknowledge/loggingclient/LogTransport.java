package org.documentknowledge.loggingclient;

/** Replaceable outbound transport. Only the dispatch worker invokes this port. */
@FunctionalInterface
public interface LogTransport extends AutoCloseable {
    enum Route { BATCHES, RECEIPTS }
    record Response(int status, byte[] body) {
        public Response {
            if(status<100 || status>599 || body==null || body.length>32768)
                throw new IllegalArgumentException("INVALID_LOG_RESPONSE");
            body=body.clone();
        }
        @Override public byte[] body() {return body.clone();}
    }
    Response post(Route route,byte[] body) throws Exception;
    @Override default void close() {}
}
