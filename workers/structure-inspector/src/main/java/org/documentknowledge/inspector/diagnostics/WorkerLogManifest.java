package org.documentknowledge.inspector.diagnostics;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Original embedded public metadata only; no runtime credentials, paths or HTTP authority. */
final class WorkerLogManifest {
    static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(1048576)
            .maxNestingDepth(16).maxStringLength(512).maxNumberLength(32).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    final String buildId,sha256;
    final Set<String> exceptions;
    final Map<String,String> frames;
    private WorkerLogManifest(String buildId,String sha256,Set<String> exceptions,Map<String,String> frames) {
        this.buildId=buildId;this.sha256=sha256;this.exceptions=Set.copyOf(exceptions);this.frames=Map.copyOf(frames);
    }
    static WorkerLogManifest read(InputStream source,String expectedSha256) throws IOException {
        try(InputStream input=Objects.requireNonNull(source)) {
            text(expectedSha256,64,"[0-9a-f]{64}");byte[] raw=input.readNBytes(1048577);
            require(raw.length>0 && raw.length<=1048576);
            require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)).equals(expectedSha256));
            String utf8=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
            var manifest=JSON.readValue(utf8,Map.class);
            fields(manifest,Set.of("schemaVersion","buildId","component","gitSha","sourceFingerprint","compiledAt",
                "toolchainFingerprint","dependencyFingerprint","vocabularySha256","vocabulary","sourceFingerprintAlgorithm","dirty"),Set.of());
            require(Integer.valueOf(1).equals(manifest.get("schemaVersion")) && "structure-inspector".equals(manifest.get("component"))
                && "dkp-public-inputs-v1".equals(manifest.get("sourceFingerprintAlgorithm")) && manifest.get("dirty") instanceof Boolean);
            String build=id(manifest.get("buildId"));text(manifest.get("gitSha"),40,"[0-9a-f]{40}");
            for(String name:List.of("sourceFingerprint","toolchainFingerprint","dependencyFingerprint","vocabularySha256"))text(manifest.get(name),64,"[0-9a-f]{64}");
            Instant.parse((String)manifest.get("compiledAt"));
            Map<?,?> catalog=object(manifest.get("vocabulary"));
            fields(catalog,Set.of("events","phases","errorCodes","dependencies","exceptionClasses","frames"),Set.of());
            var events=strings(catalog.get("events"),16,96,"[a-z.]+");
            require(Set.of("process.started","process.stopped","operation.started","operation.succeeded","operation.failed","operation.cancelled","dependency.started","dependency.succeeded","dependency.failed","build.started","build.succeeded","build.failed","ui.route.changed","ui.identity.changed","producer.delivery.degraded","producer.events.lost").containsAll(events));
            require(events.containsAll(Set.of("process.started","process.stopped","operation.started","operation.succeeded","operation.failed","operation.cancelled")));
            var phases=strings(catalog.get("phases"),256,96,"[A-Za-z][A-Za-z0-9_.-]*");
            for(var phase:InspectorDiagnostics.Phase.values())require(phases.contains(phase.name()));
            var errors=strings(catalog.get("errorCodes"),1024,96,"[A-Z0-9_]+");
            for(var error:InspectorDiagnostics.Error.values())require(errors.contains(error.name()));
            var exceptions=strings(catalog.get("exceptionClasses"),8192,256,"[A-Za-z0-9_.$]+");
            var dependencies=new HashSet<String>();
            for(Object value:list(catalog.get("dependencies"),256)) {
                var row=object(value);fields(row,Set.of("name","version","vendorBuildProvenance"),Set.of("imageDigest"));
                String name=text(row.get("name"),128,"[A-Za-z][A-Za-z0-9_.-]*");text(row.get("version"),128,"[\\s\\S]+");
                require(Set.of("VERIFIED","UNKNOWN").contains(row.get("vendorBuildProvenance")));require(dependencies.add(name));
                if(row.containsKey("imageDigest"))text(row.get("imageDigest"),71,"sha256:[0-9a-f]{64}");
            }
            var frames=new HashMap<String,String>();
            for(Object value:list(catalog.get("frames"),32768)) {
                var row=object(value);fields(row,Set.of("symbol","source"),Set.of());
                String symbol=text(row.get("symbol"),256,"[A-Za-z0-9_.$<>-]+"),path=path(row.get("source"));
                require(frames.put(symbol,path)==null);
            }
            return new WorkerLogManifest(build,expectedSha256,exceptions,frames);
        }catch(Exception invalid){throw new IOException("INSPECTOR_LOG_MANIFEST_INVALID");}
    }
    static String id(Object value) {
        String text=text(value,36,"[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
        require(!UUID.fromString(text).equals(new UUID(0,0)));return text;
    }
    private static Set<String> strings(Object value,int maximum,int length,String pattern) {
        var result=new HashSet<String>();for(Object item:list(value,maximum))require(result.add(text(item,length,pattern)));return result;
    }
    private static List<?> list(Object value,int maximum) {require(value instanceof List<?> && ((List<?>)value).size()<=maximum);return (List<?>)value;}
    private static Map<?,?> object(Object value) {require(value instanceof Map<?,?>);return (Map<?,?>)value;}
    private static String path(Object value) {
        String path=text(value,256,"[A-Za-z0-9_./$-]+");require(!path.startsWith("/"));
        for(String part:path.split("/",-1))require(!part.isEmpty() && !part.equals(".") && !part.equals(".."));return path;
    }
    private static String text(Object value,int maximum,String pattern) {require(value instanceof String && !((String)value).isEmpty() && ((String)value).codePointCount(0,((String)value).length())<=maximum && ((String)value).matches(pattern));return (String)value;}
    private static void fields(Map<?,?> value,Set<String> required,Set<String> optional) {require(value.keySet().containsAll(required));var allowed=new HashSet<>(required);allowed.addAll(optional);require(allowed.containsAll(value.keySet()));}
    static void require(boolean value) {if(!value)throw new IllegalArgumentException("INVALID_INSPECTOR_LOG_VALUE");}
}
