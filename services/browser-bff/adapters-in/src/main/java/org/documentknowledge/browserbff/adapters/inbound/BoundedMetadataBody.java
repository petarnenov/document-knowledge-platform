package org.documentknowledge.browserbff.adapters.inbound;

import io.micronaut.http.HttpRequest;
import java.io.InputStream;
import java.util.Map;
import org.documentknowledge.browserbff.application.BffFailure;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Semantic JSON bound independent of the framework streaming window. */
final class BoundedMetadataBody {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private BoundedMetadataBody() {}
    @SuppressWarnings("unchecked")
    static Map<String, Object> decode(HttpRequest<?> request, InputStream body) {
        if (request.getHeaders().contentLength().orElse(-1) > 16384) throw new BffFailure(413, "PAYLOAD_TOO_LARGE");
        if (body == null) throw new BffFailure(400, "INVALID_REQUEST");
        try {
            byte[] bytes = body.readNBytes(16385);
            if (bytes.length > 16384) throw new BffFailure(413, "PAYLOAD_TOO_LARGE");
            Object decoded = JSON.readValue(bytes, Object.class);
            if (!(decoded instanceof Map<?, ?>)) throw new BffFailure(400, "INVALID_REQUEST");
            return (Map<String, Object>) decoded;
        } catch (BffFailure failure) { throw failure; }
        catch (Exception invalid) { throw new BffFailure(400, "INVALID_REQUEST"); }
    }
    static void close(InputStream body) {
        if (body != null) try { body.close(); } catch (java.io.IOException ignored) { /* finite response already chosen */ }
    }
}
