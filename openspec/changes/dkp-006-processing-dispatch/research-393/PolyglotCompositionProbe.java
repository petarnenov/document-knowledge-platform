import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.io.IOAccess;

/** Optional research only. Fixed upstream library code, untrusted JSON data, no custom serializer. */
public final class PolyglotCompositionProbe {
    private PolyglotCompositionProbe() {}
    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("EXACT_RESEARCH_INPUTS_REQUIRED");
        try(var context=Context.newBuilder("js").allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(name->false).allowIO(IOAccess.NONE)
                .allowNativeAccess(false).allowCreateThread(false).allowCreateProcess(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE).allowPolyglotAccess(PolyglotAccess.NONE)
                .option("js.esm-eval-returns-exports","true").build()) {
            var source=Source.newBuilder("js",Files.readString(Path.of(args[1])),"canonicalize.mjs")
                    .mimeType("application/javascript+module").buildLiteral();
            var canonicalize=context.eval(source).getMember("default");
            var parse=context.eval("js","JSON.parse");
            if(!context.eval("js","(() => { try { Java.type('java.lang.System'); return false; } catch { return typeof process === 'undefined'; } })()").asBoolean())
                throw new IllegalStateException("RESEARCH_HOST_ACCESS_GUARD_FAILED");
            for(String line:Files.readAllLines(Path.of(args[0]),StandardCharsets.UTF_8)) {
                String[] fields=line.split("\t",-1);String outcome;
                try {
                    byte[] input;
                    if(fields[1].equals("IEEE754")) {
                        double n=Double.longBitsToDouble(Long.parseUnsignedLong(fields[2],16));
                        input=("{\"n\":"+Double.toString(n)+"}").getBytes(StandardCharsets.UTF_8);
                    } else input=Base64.getDecoder().decode(fields[2]);
                    StrictCompositionProbe.strictValue(input);
                    var value=parse.execute(new String(input,StandardCharsets.UTF_8));
                    byte[] bytes=canonicalize.execute(value).asString().getBytes(StandardCharsets.UTF_8);
                    outcome=Base64.getEncoder().encodeToString(bytes);
                } catch(Exception rejected) { outcome="REJECT"; }
                System.out.println(fields[0]+"\t"+outcome);
            }
        }
    }
}
