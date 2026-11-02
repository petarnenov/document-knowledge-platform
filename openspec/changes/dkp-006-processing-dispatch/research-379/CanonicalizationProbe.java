import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.erdtman.jcs.JsonCanonicalizer;

/** Research-only candidate adapter. Deliberately exposes candidate rejection failures. */
public final class CanonicalizationProbe {
    private CanonicalizationProbe() {}
    public static void main(String[] args) throws Exception {
        for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
            String[] fields = line.split("\t", -1);
            String outcome;
            try {
                byte[] input;
                if (fields[1].equals("IEEE754")) {
                    double value = Double.longBitsToDouble(Long.parseUnsignedLong(fields[2], 16));
                    input = ("{\"n\":" + Double.toString(value) + "}").getBytes(StandardCharsets.UTF_8);
                } else {
                    input = Base64.getDecoder().decode(fields[2]);
                }
                outcome = Base64.getEncoder().encodeToString(new JsonCanonicalizer(input).getEncodedUTF8());
            } catch (Exception rejected) {
                outcome = "REJECT";
            }
            System.out.println(fields[0] + "\t" + outcome);
        }
    }
}
