import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import com.apicatalog.jcs.Jcs;
import org.erdtman.jcs.JsonCanonicalizer;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Research only: standard strict decoding/parser validation, third-party serialization only. */
public final class StrictCompositionProbe {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private StrictCompositionProbe() {}
    static Object strictValue(byte[] input) throws Exception {
        String raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(input)).toString();
        Object parsed = JSON.readValue(raw, Object.class);
        var pending = new ArrayDeque<Object>();
        if (parsed != null) pending.push(parsed);
        while (!pending.isEmpty()) {
            Object value = pending.pop();
            if (value instanceof String text) {
                StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text));
            } else if (value instanceof Number number) {
                if (!Double.isFinite(number.doubleValue())) throw new IllegalArgumentException("NON_FINITE");
            } else if (value instanceof Map<?, ?> object) {
                for (var entry : object.entrySet()) {
                    pending.push(entry.getKey());
                    if (entry.getValue() != null) pending.push(entry.getValue());
                }
            } else if (value instanceof List<?> array) {
                for (Object item : array) if (item != null) pending.push(item);
            }
        }
        return parsed;
    }
    static byte[] canonize(byte[] input, String mode) throws Exception {
        Object value = strictValue(input);
        if (mode.equals("TITANIUM_STRICT")) return Jcs.canonize(value);
        if (mode.equals("ERDTMAN_STRICT")) return new JsonCanonicalizer(input).getEncodedUTF8();
        throw new IllegalArgumentException("EXACT_MODE_REQUIRED");
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !List.of("TITANIUM_STRICT", "ERDTMAN_STRICT").contains(args[0]))
            throw new IllegalArgumentException("EXACT_RESEARCH_PROFILE_REQUIRED");
        for (String line : Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8)) {
            String[] fields = line.split("\t", -1);
            String outcome;
            try {
                byte[] input;
                if (fields[1].equals("IEEE754")) {
                    double value = Double.longBitsToDouble(Long.parseUnsignedLong(fields[2], 16));
                    input = ("{\"n\":" + Double.toString(value) + "}").getBytes(StandardCharsets.UTF_8);
                } else input = Base64.getDecoder().decode(fields[2]);
                outcome = Base64.getEncoder().encodeToString(canonize(input, args[0]));
            } catch (Exception rejected) { outcome = "REJECT"; }
            System.out.println(fields[0] + "\t" + outcome);
        }
    }
}
