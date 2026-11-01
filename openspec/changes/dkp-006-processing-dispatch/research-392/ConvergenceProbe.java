import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.google.common.collect.ImmutableMap;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.reporter.MicrometerClientStatsReporter;
import io.temporal.failure.ApplicationFailure;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.minidev.json.parser.JSONParser;
import net.minidev.json.parser.ParseException;
import org.apache.kafka.common.compress.Compression;
import org.apache.kafka.common.utils.internals.BufferSupplier;
import org.apache.kafka.common.utils.internals.ByteBufferOutputStream;

/** Research only: local bounded behavior, no client/server/provider connection. */
public final class ConvergenceProbe {
    public record Payload(String title, List<Integer> revisions) {}
    public record Hidden(@JsonIgnore String secretValue, String publicValue) {}
    public static void main(String[] args) throws Exception {
        ignoredRecordProperty();
        parserBounds();
        temporalPayloadAndFailure();
        temporalMetricsBridge();
        kafkaCodecRoundTrips();
        guavaSerialization();
        jsonSmartDepth();
        System.out.println("CONVERGENCE_LOCAL_BEHAVIORS=7_PASS_NO_CONNECTION");
    }
    static void ignoredRecordProperty() throws Exception {
        var mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var value = mapper.readValue("{\"secret_value\":\"untrusted\",\"public_value\":\"visible\"}", Hidden.class);
        assertNull(value.secretValue());
        assertEquals("visible", value.publicValue());
        assertFalse(mapper.writeValueAsString(new Hidden("private", "visible")).contains("private"));
    }
    static void parserBounds() throws Exception {
        var factory = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
                .maxStringLength(16).maxNumberLength(16).maxNestingDepth(8).build()).build();
        try (var parser = factory.createParser("\"" + "a".repeat(256) + "\"")) {
            assertThrows(StreamConstraintsException.class, () -> { parser.nextToken(); parser.getText(); });
        }
        try (var parser = factory.createParser("[0]")) {
            drain(parser);
            assertNull(parser.currentToken());
        }
        try (var parser = factory.createParser("[".repeat(16) + "0" + "]".repeat(16))) {
            assertThrows(StreamConstraintsException.class, () -> drain(parser));
        }
    }
    static void drain(com.fasterxml.jackson.core.JsonParser parser) throws Exception {
        while (parser.nextToken() != null) {}
    }
    static void temporalPayloadAndFailure() {
        var testOptions = io.temporal.testing.TestEnvironmentOptions.newBuilder()
                .setUseExternalService(false).setUseTimeskipping(false).build();
        assertFalse(testOptions.isUseExternalService());
        assertFalse(testOptions.isUseTimeskipping());
        var converter = DefaultDataConverter.newDefaultInstance();
        var value = new Payload("public synthetic", List.of(1, 2, 3));
        var payload = converter.toPayload(value).orElseThrow();
        assertEquals(value, converter.fromPayload(payload, Payload.class, Payload.class));
        var original = ApplicationFailure.newNonRetryableFailure("synthetic", "research-boundary", 7);
        var failure = (ApplicationFailure) converter.failureToException(converter.exceptionToFailure(original));
        assertEquals("research-boundary", failure.getType());
        assertTrue(failure.isNonRetryable());
        assertEquals(Integer.valueOf(7), failure.getDetails().get(0, Integer.class));
    }
    static void temporalMetricsBridge() {
        var registry = new SimpleMeterRegistry();
        try {
            var reporter = new MicrometerClientStatsReporter(registry);
            reporter.reportCounter("research.counter", Map.of("scope", "synthetic"), 3);
            reporter.reportTimer("research.timer", Map.of(), com.uber.m3.util.Duration.ofMillis(5));
            assertEquals(3.0, registry.get("research.counter").counter().count(), 0.0);
            assertEquals(5.0, registry.get("research.timer").timer().totalTime(TimeUnit.MILLISECONDS), 0.001);
            reporter.close();
        } finally { registry.close(); }
    }
    static void kafkaCodecRoundTrips() throws Exception {
        byte[] bytes = "public synthetic compression fixture ".repeat(128).getBytes(StandardCharsets.UTF_8);
        for (var codec : List.of(Compression.lz4().build(), Compression.snappy().build())) {
            var target = new ByteBufferOutputStream(64);
            try (var output = codec.wrapForOutput(target, (byte) 2)) { output.write(bytes); }
            var buffer = target.buffer().duplicate();
            buffer.flip();
            try (var input = codec.wrapForInput(buffer, (byte) 2, BufferSupplier.NO_CACHING)) {
                assertArrayEquals(bytes, input.readAllBytes());
            }
        }
    }
    static void guavaSerialization() throws Exception {
        var original = ImmutableMap.of("revision", 1);
        var bytes = new ByteArrayOutputStream();
        try (var stream = new ObjectOutputStream(bytes)) { stream.writeObject(original); }
        try (var stream = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertEquals(original, stream.readObject());
        }
    }
    static void jsonSmartDepth() throws Exception {
        var parser = new JSONParser(JSONParser.MODE_RFC4627 | JSONParser.LIMIT_JSON_DEPTH);
        assertEquals(1, ((Number) ((List<?>) parser.parse("[1]")).get(0)).intValue());
        assertThrows(ParseException.class, () -> parser.parse("[".repeat(1024) + "0" + "]".repeat(1024)));
    }
}
