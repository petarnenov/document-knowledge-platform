package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.server.types.files.StreamedFile;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import java.io.InputStream;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Test-only producers; actual HTTP encoding, NIO writes and owner cleanup remain operational. */
@Controller("/dkp222/output")
@Requires(property = "dkp222.output-witness", value = "true")
@Secured(SecurityRule.IS_ANONYMOUS)
final class OutboundWriteWitness {
    final AtomicLong generated = new AtomicLong();
    final AtomicBoolean closed = new AtomicBoolean();

    @Get(value = "/stream", produces = MediaType.APPLICATION_OCTET_STREAM)
    StreamedFile stream() {
        return new StreamedFile(new InputStream() {
            long remaining = 100_000_000;
            @Override public int read() {
                if (remaining == 0) return -1;
                remaining--; generated.incrementAndGet(); return 137;
            }
            @Override public int read(byte[] bytes, int offset, int length) {
                if (length == 0) return 0;
                if (remaining == 0) return -1;
                int size = (int) Math.min(length, remaining);
                Arrays.fill(bytes, offset, offset + size, (byte) 137);
                remaining -= size;
                generated.addAndGet(size);
                return size;
            }
            @Override public void close() { closed.set(true); }
        }, MediaType.APPLICATION_OCTET_STREAM_TYPE, 0, 100_000_000);
    }

    @Get(value = "/metadata", produces = MediaType.APPLICATION_JSON)
    Map<String, Object> metadata() {
        var documents = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 50; i++) {
            var document = DocumentGatewayTest.draft();
            document.put("documentId", new UUID(0, i + 1).toString());
            document.put("metadata", Map.of("title", "\"".repeat(256), "description", "\"".repeat(4096)));
            documents.add(document);
        }
        var page = new HashMap<String, Object>();
        page.put("items", documents);
        page.put("nextCursor", null);
        return page;
    }
}
