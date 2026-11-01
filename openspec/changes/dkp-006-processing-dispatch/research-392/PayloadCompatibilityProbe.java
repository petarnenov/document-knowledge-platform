import static org.junit.jupiter.api.Assertions.*;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DefaultDataConverter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** A finite stored-payload cross-graph fixture, not workflow history/replay certification. */
public final class PayloadCompatibilityProbe {
    public record Value(String title, List<Long> revisions, Instant acceptedAt) {}
    public static void main(String[] args) throws Exception {
        var converter = DefaultDataConverter.newDefaultInstance();
        var value = new Value("public synthetic", List.of(1L, 7L), Instant.parse("2026-11-01T00:00:00Z"));
        var generated = converter.toPayload(value).orElseThrow();
        if (args[0].equals("write")) {
            Files.write(Path.of(args[1]), generated.toByteArray());
        } else {
            assertEquals("read", args[0]);
            var stored = Payload.parseFrom(Files.readAllBytes(Path.of(args[1])));
            assertEquals(value, converter.fromPayload(stored, Value.class, Value.class));
            assertEquals(stored, generated);
        }
        System.out.println("FINITE_PAYLOAD_COMPATIBILITY_PASS");
    }
}
