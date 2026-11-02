package org.documentknowledge.devlogging.bootstrap;

import com.sun.security.auth.module.UnixSystem;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Instant;
import java.util.*;
import org.documentknowledge.devlogging.domain.LogFailure;
import org.documentknowledge.devlogging.domain.VerifiedLogManifest;
import org.documentknowledge.observability.BuildIdentity;
import org.documentknowledge.observability.EventCode;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Private operator inbound adapter; no route, automatic install or event-derived catalogs. */
final class PrivateLogManifest {
    private static final Set<String> ROOT = Set.of("schemaVersion", "buildId", "component", "gitSha", "sourceFingerprint",
        "compiledAt", "toolchainFingerprint", "dependencyFingerprint", "vocabularySha256", "vocabulary",
        "sourceFingerprintAlgorithm", "dirty");
    private static final String DIGEST = "[0-9a-f]{64}";

    private PrivateLogManifest() { }

    static VerifiedLogManifest read(Path file, String expectedSha256) {
        try {
            var before = Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            long uid = new UnixSystem().getUid();
            check(before.isRegularFile() && before.size() > 0 && before.size() <= VerifiedLogManifest.MAX_BYTES);
            check(((Number) Files.getAttribute(file, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue() == uid);
            var forbidden = EnumSet.of(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);
            check(before.permissions().contains(PosixFilePermission.OWNER_READ)
                && Collections.disjoint(before.permissions(), forbidden));
            byte[] bytes;
            try (var channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                 var input = Channels.newInputStream(channel)) { bytes = input.readNBytes(VerifiedLogManifest.MAX_BYTES + 1); }
            var after = Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            check(before.size() == bytes.length && before.size() == after.size()
                && before.owner().equals(after.owner()) && Objects.equals(before.fileKey(), after.fileKey())
                && before.lastModifiedTime().equals(after.lastModifiedTime()) && before.permissions().equals(after.permissions()));
            return parse(bytes, expectedSha256);
        } catch (Exception invalid) { throw failure(); }
    }

    static VerifiedLogManifest parse(byte[] bytes, String expectedSha256) {
        try {
            check(bytes != null && bytes.length > 0 && bytes.length <= VerifiedLogManifest.MAX_BYTES);
            check(expectedSha256 != null && expectedSha256.matches(DIGEST) && expectedSha256.equals(VerifiedLogManifest.sha256(bytes)));
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var factory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                    .maxDocumentLength(VerifiedLogManifest.MAX_BYTES).maxStringLength(256).maxNumberLength(3).build()).build();
            var mapper = JsonMapper.builder(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
            var root = object(mapper.readValue(text, Object.class), ROOT);
            check(root.get("schemaVersion") instanceof Integer number && number == 1);
            check(root.get("dirty") instanceof Boolean);
            check("dkp-public-inputs-v1".equals(root.get("sourceFingerprintAlgorithm")));
            String id = string(root, "buildId", 36, "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
            var build = new BuildIdentity(UUID.fromString(id), string(root,"component",96,"[a-z][a-z0-9.-]*"),
                string(root,"gitSha",40,"[0-9a-f]{40}"), string(root,"sourceFingerprint",64,DIGEST));
            string(root,"toolchainFingerprint",64,DIGEST); string(root,"dependencyFingerprint",64,DIGEST);
            Instant.parse(string(root,"compiledAt",64,
                "[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt](?:[01][0-9]|2[0-3]):[0-5][0-9]:(?:[0-5][0-9]|60)"
                    + "(?:\\.[0-9]+)?(?:[Zz]|[+-](?:[01][0-9]|2[0-3]):[0-5][0-9])"));
            String vocabularyDigest = string(root,"vocabularySha256",64,DIGEST);
            var vocabulary = vocabulary(root.get("vocabulary"));
            check(vocabularyDigest.equals(VerifiedLogManifest.sha256(canonical(vocabulary))));
            check(Arrays.equals(bytes, canonical(root)));
            return new VerifiedLogManifest(build, expectedSha256, vocabularyDigest, bytes);
        } catch (Exception invalid) { throw failure(); }
    }

    private static Map<?,?> vocabulary(Object value) {
        var vocabulary = object(value, Set.of("events","phases","errorCodes","dependencies","exceptionClasses","frames"));
        var events = catalog(vocabulary.get("events"),16,1024,128,"[a-z][a-z0-9_.-]*");
        for (var code : EventCode.values()) check(events.contains(code.wireName()));
        catalog(vocabulary.get("phases"),0,1024,128,"[A-Za-z][A-Za-z0-9_.-]*");
        catalog(vocabulary.get("errorCodes"),0,1024,96,"[A-Z0-9_]+");
        catalog(vocabulary.get("exceptionClasses"),0,8192,256,"[A-Za-z0-9_.$]+");
        var names = new HashSet<String>();
        for (var valueDependency : list(vocabulary.get("dependencies"),0,256)) {
            check(valueDependency instanceof Map<?,?>); var dependency = (Map<?,?>) valueDependency;
            check(dependency.keySet().containsAll(Set.of("name","version","vendorBuildProvenance"))
                && Set.of("name","version","vendorBuildProvenance","imageDigest").containsAll(dependency.keySet()));
            check(names.add(string(dependency,"name",128,"[A-Za-z][A-Za-z0-9_.-]*")));
            string(dependency,"version",128,"[\\s\\S]+");
            check(Set.of("UNKNOWN","VERIFIED").contains(dependency.get("vendorBuildProvenance")));
            if (dependency.containsKey("imageDigest")) string(dependency,"imageDigest",71,"sha256:"+DIGEST);
        }
        var frames = list(vocabulary.get("frames"),0,32768);
        check(new HashSet<>(frames).size() == frames.size());
        for (var valueFrame : frames) {
            var frame = object(valueFrame, Set.of("symbol","source"));
            string(frame,"symbol",256,"[A-Za-z0-9_.$<>-]+");
            string(frame,"source",256,"(?!/)(?!.*(?:^|/)\\.\\.(?:/|$))[A-Za-z0-9_./$-]+");
        }
        return vocabulary;
    }

    private static Map<?,?> object(Object value, Set<String> keys) {
        check(value instanceof Map<?,?>); var map = (Map<?,?>) value; check(map.keySet().equals(keys)); return map;
    }
    private static List<?> list(Object value, int minimum, int maximum) {
        check(value instanceof List<?>); var list = (List<?>) value;
        check(list.size() >= minimum && list.size() <= maximum); return list;
    }
    private static Set<String> catalog(Object value, int minimum, int maximum, int length, String pattern) {
        var values = list(value,minimum,maximum); var result = new HashSet<String>();
        for (var item : values) check(result.add(text(item,length,pattern)));
        return result;
    }
    private static String string(Map<?,?> map, String key, int maximum, String pattern) {
        return text(map.get(key),maximum,pattern);
    }
    private static String text(Object value, int maximum, String pattern) {
        check(value instanceof String); var text = (String) value;
        check(text.codePointCount(0,text.length()) <= maximum && text.matches(pattern)); return text;
    }

    /** RFC8785 restricted to the manifest schema: its only numeric value is integer1. */
    static byte[] canonical(Object value) {
        var result = new StringBuilder(); append(result,value); return result.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void append(StringBuilder output, Object value) {
        if (value instanceof Map<?,?> map) {
            output.append('{'); var keys = new ArrayList<String>();
            for (var key : map.keySet()) { check(key instanceof String); keys.add((String) key); }
            Collections.sort(keys); boolean first = true;
            for (var key : keys) {
                if (!first) output.append(','); first = false; quote(output,key); output.append(':'); append(output,map.get(key));
            }
            output.append('}');
        } else if (value instanceof List<?> list) {
            output.append('['); boolean first = true;
            for (var item : list) { if (!first) output.append(','); first = false; append(output,item); }
            output.append(']');
        } else if (value instanceof String string) quote(output,string);
        else if (value instanceof Boolean || Integer.valueOf(1).equals(value)) output.append(value);
        else throw failure();
    }
    private static void quote(StringBuilder output, String value) {
        output.append('"');
        for (int i=0; i<value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isSurrogate(ch)) {
                check(Character.isHighSurrogate(ch) && i+1<value.length() && Character.isLowSurrogate(value.charAt(i+1)));
                output.append(ch).append(value.charAt(++i)); continue;
            }
            switch (ch) {
                case '"' -> output.append("\\\""); case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b"); case '\t' -> output.append("\\t"); case '\n' -> output.append("\\n");
                case '\f' -> output.append("\\f"); case '\r' -> output.append("\\r");
                default -> { if (ch < 0x20) output.append(String.format(Locale.ROOT,"\\u%04x",(int) ch)); else output.append(ch); }
            }
        }
        output.append('"');
    }
    private static void check(boolean valid) { if (!valid) throw failure(); }
    private static LogFailure failure() { return new LogFailure(LogFailure.Kind.INVALID); }
}
