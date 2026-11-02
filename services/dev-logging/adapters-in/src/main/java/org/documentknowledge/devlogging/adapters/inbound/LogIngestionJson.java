package org.documentknowledge.devlogging.adapters.inbound;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import org.documentknowledge.devlogging.application.RequestDeadline;
import org.documentknowledge.devlogging.domain.*;
import org.documentknowledge.observability.*;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Closed untrusted event validation and deterministic symbol sanitation against the original installed build. */
final class LogIngestionJson {
    private static final Set<String> EVENT=Set.of("eventId","buildId","runId","instanceId","component","gitSha","sourceFingerprint",
        "timestamp","severity","eventName","correlationId","context");
    private static final Set<String> CONTEXT=Set.of("phase","outcome","status","durationMs","remainingDeadlineMs","attempt","errorCode",
        "sqlState","exceptionClass","causeClasses","stackFrames","redactedFields","truncatedFields","dependency","dependencyVersion",
        "dependencyImageDigest","attributes","unregisteredSymbols");
    private static final Set<String> ATTRIBUTES=Set.of("bytes","count","queueDepth","cancelRequested","cleanupSettled","vendorBuildProvenance");
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(1048576).maxNestingDepth(16).maxStringLength(512).maxNumberLength(32).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    static LogBatch batch(byte[] bytes,ProducerScope authority,VerifiedLogManifest manifest,RequestDeadline deadline) {
        try {
            deadline.requireTime();var root=object(read(bytes,32768));exact(root,Set.of("schemaVersion","events"));
            if(!(root.get("schemaVersion") instanceof Integer version) || version!=1)throw invalid();
            var values=list(root.get("events"),1,32);var catalog=catalog(manifest);var events=new ArrayList<CanonicalLogEvent>();int total=0;
            for(var value:values) {
                deadline.requireTime();var event=object(value);allowed(event,EVENT,Set.of("traceId","spanId"));
                var build=new BuildIdentity(id(event,"buildId"),text(event,"component",96,"[a-z][a-z0-9.-]*"),
                    text(event,"gitSha",40,"[0-9a-f]{40}"),text(event,"sourceFingerprint",64,"[0-9a-f]{64}"));
                String name=text(event,"eventName",128,"[a-z][a-z0-9_.-]*");
                EventCode code=Arrays.stream(EventCode.values()).filter(item->item.wireName().equals(name)).findFirst().orElseThrow(LogIngestionJson::unregistered);
                if(!catalog.events().contains(name))throw unregistered();
                var trace=new TraceContext(event.containsKey("traceId")?text(event,"traceId",32,"[0-9a-f]{32}"):null,
                    event.containsKey("spanId")?text(event,"spanId",16,"[0-9a-f]{16}"):null);
                var metadata=new EventMetadata(id(event,"eventId"),new ExecutionIdentity(build,id(event,"runId"),id(event,"instanceId")),
                    LogRegistrationJson.timestamp(text(event,"timestamp",64,null)),Severity.valueOf(text(event,"severity",8,null)),code,id(event,"correlationId"),trace);
                if(!authority.binding().matches(metadata) || !manifest.build().equals(build))throw new LogFailure(LogFailure.Kind.FORBIDDEN);
                var sanitized=new HashMap<String,Object>(event);sanitized.put("context",context(event.get("context"),catalog));
                byte[] canonical=LogCanonicalJson.bytes(sanitized);
                if(canonical.length>8192 || (total+=canonical.length)>32768)throw new LogFailure(LogFailure.Kind.TOO_LARGE);
                events.add(new CanonicalLogEvent(metadata,canonical));
            }
            return new LogBatch(events);
        } catch(LogFailure known) {throw known;} catch(Exception invalid) {throw invalid();}
    }
    static List<UUID> receipts(byte[] bytes) {
        try {
            var root=object(read(bytes,32768));exact(root,Set.of("eventIds"));var result=new ArrayList<UUID>();
            for(var value:list(root.get("eventIds"),1,32)) {
                if(!(value instanceof String id))throw invalid();result.add(LogRegistrationJson.id(id));
            }
            if(new HashSet<>(result).size()!=result.size())throw invalid();return List.copyOf(result);
        } catch(LogFailure known) {throw known;} catch(Exception invalid) {throw invalid();}
    }
    private record Frame(String symbol,String source) { }
    private record Catalog(Set<String> events,Set<String> phases,Set<String> errors,Set<String> classes,
                           Map<String,Map<String,Object>> dependencies,Set<Frame> frames,Set<String> symbols) { }
    private static Catalog catalog(VerifiedLogManifest manifest) {
        try {
            var root=object(read(manifest.bytes(),VerifiedLogManifest.MAX_BYTES));var build=manifest.build();
            if(!build.buildId().toString().equals(root.get("buildId")) || !build.component().equals(root.get("component"))
                    || !build.gitSha().equals(root.get("gitSha")) || !build.sourceFingerprint().equals(root.get("sourceFingerprint"))
                    || !manifest.vocabularyDigest().equals(root.get("vocabularySha256")))throw invalid();
            var row=object(root.get("vocabulary"));exact(row,Set.of("events","phases","errorCodes","dependencies","exceptionClasses","frames"));
            if(!manifest.vocabularyDigest().equals(VerifiedLogManifest.sha256(LogCanonicalJson.bytes(row))))throw invalid();
            var dependencies=new HashMap<String,Map<String,Object>>();
            for(var value:list(row.get("dependencies"),0,256)) {
                var dependency=object(value);allowed(dependency,Set.of("name","version","vendorBuildProvenance"),Set.of("imageDigest"));
                String name=text(dependency,"name",128,"[A-Za-z][A-Za-z0-9_.-]*");text(dependency,"version",128,null);
                if(!Set.of("VERIFIED","UNKNOWN").contains(text(dependency,"vendorBuildProvenance",8,null)))throw invalid();
                if(dependency.containsKey("imageDigest"))text(dependency,"imageDigest",71,"sha256:[0-9a-f]{64}");
                if(dependencies.put(name,Map.copyOf(dependency))!=null)throw invalid();
            }
            var frames=new HashSet<Frame>();var symbols=new HashSet<String>();
            for(var value:list(row.get("frames"),0,32768)) {
                var frame=object(value);exact(frame,Set.of("symbol","source"));
                String symbol=text(frame,"symbol",256,"[A-Za-z0-9_.$<>-]+"),source=text(frame,"source",256,sourcePattern());
                if(!frames.add(new Frame(symbol,source)))throw invalid();symbols.add(symbol);
            }
            return new Catalog(strings(row.get("events"),16,1024,128,"[a-z][a-z0-9_.-]*"),strings(row.get("phases"),0,1024,128,"[A-Za-z][A-Za-z0-9_.-]*"),
                strings(row.get("errorCodes"),0,1024,96,"[A-Z0-9_]+"),strings(row.get("exceptionClasses"),0,8192,256,"[A-Za-z0-9_.$]+"),dependencies,frames,symbols);
        } catch(Exception corruptCatalog) {throw new LogFailure(LogFailure.Kind.UNAVAILABLE);}
    }
    private static Map<String,Object> context(Object value,Catalog catalog) {
        var row=object(value);allowed(row,Set.of(),CONTEXT);var result=new HashMap<String,Object>(row);
        member(row,"phase",96,"[A-Za-z0-9_.-]+",catalog.phases());member(row,"errorCode",96,"[A-Z0-9_]+",catalog.errors());
        if(row.containsKey("outcome") && !Set.of("STARTED","SUCCEEDED","FAILED","CANCELLED","RETRYING","REJECTED","UNKNOWN").contains(text(row,"outcome",16,null)))throw invalid();
        if(row.containsKey("sqlState"))text(row,"sqlState",5,"[A-Z0-9]{5}");
        for(String key:List.of("durationMs","remainingDeadlineMs","attempt","redactedFields","truncatedFields","unregisteredSymbols"))
            if(row.containsKey(key))integer(row.get(key),0,LogCanonicalJson.MAX_INTEGER);
        if(row.containsKey("status"))integer(row.get("status"),100,599);
        long omitted=0;
        if(row.containsKey("exceptionClass") && !catalog.classes().contains(text(row,"exceptionClass",256,"[A-Za-z0-9_.$]+"))) {result.remove("exceptionClass");omitted++;}
        if(row.containsKey("causeClasses")) {
            var known=new ArrayList<String>();
            for(var item:list(row.get("causeClasses"),0,4)) {
                String name=string(item,256,"[A-Za-z0-9_.$]+");if(catalog.classes().contains(name))known.add(name);else omitted++;
            }
            result.put("causeClasses",known);
        }
        if(row.containsKey("stackFrames")) {
            var known=new ArrayList<Map<String,Object>>();
            for(var item:list(row.get("stackFrames"),0,32)) {
                var frame=object(item);allowed(frame,Set.of("symbol"),Set.of("source","line"));String symbol=text(frame,"symbol",256,null);
                String source=frame.containsKey("source")?text(frame,"source",256,sourcePattern()):null;
                if(frame.containsKey("line"))integer(frame.get("line"),0,LogCanonicalJson.MAX_INTEGER);
                if(source==null ? catalog.symbols().contains(symbol) : catalog.frames().contains(new Frame(symbol,source)))known.add(Map.copyOf(frame));else omitted++;
            }
            result.put("stackFrames",known);
        }
        if(omitted>0) {
            long original=row.containsKey("unregisteredSymbols")?integer(row.get("unregisteredSymbols"),0,LogCanonicalJson.MAX_INTEGER):0;
            if(original>LogCanonicalJson.MAX_INTEGER-omitted)throw invalid();result.put("unregisteredSymbols",original+omitted);
        }
        Map<String,Object> dependency=null;
        if(row.containsKey("dependency")) {
            dependency=catalog.dependencies().get(text(row,"dependency",96,"[a-z][a-z0-9.-]*"));if(dependency==null)throw unregistered();
        }
        if(row.containsKey("dependencyVersion") && (dependency==null || !text(row,"dependencyVersion",128,null).equals(dependency.get("version"))))throw unregistered();
        if(row.containsKey("dependencyImageDigest") && (dependency==null || !text(row,"dependencyImageDigest",71,"sha256:[0-9a-f]{64}").equals(dependency.get("imageDigest"))))throw unregistered();
        if(row.containsKey("attributes")) {
            var attributes=object(row.get("attributes"));allowed(attributes,Set.of(),ATTRIBUTES);
            for(String key:List.of("bytes","count","queueDepth"))if(attributes.containsKey(key))integer(attributes.get(key),0,LogCanonicalJson.MAX_INTEGER);
            for(String key:List.of("cancelRequested","cleanupSettled"))if(attributes.containsKey(key) && !(attributes.get(key) instanceof Boolean))throw invalid();
            if(attributes.containsKey("vendorBuildProvenance")) {
                String provenance=text(attributes,"vendorBuildProvenance",8,null);
                if(!Set.of("VERIFIED","UNKNOWN").contains(provenance))throw invalid();
                if(dependency==null || !provenance.equals(dependency.get("vendorBuildProvenance")))throw unregistered();
            }
        }
        return result;
    }
    private static Object read(byte[] bytes,int maximum) throws Exception {
        if(bytes==null || bytes.length==0)throw invalid();if(bytes.length>maximum)throw new LogFailure(LogFailure.Kind.TOO_LARGE);
        String validated=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        return JSON.readValue(validated,Object.class);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {
        if(!(value instanceof Map<?,?> row) || row.keySet().stream().anyMatch(key->!(key instanceof String)))throw invalid();return (Map<String,Object>)row;
    }
    private static void exact(Map<String,Object> row,Set<String> keys){if(!row.keySet().equals(keys))throw invalid();}
    private static void allowed(Map<String,Object> row,Set<String> required,Set<String> optional) {
        if(!row.keySet().containsAll(required) || row.keySet().stream().anyMatch(key->!required.contains(key) && !optional.contains(key)))throw invalid();
    }
    private static List<?> list(Object value,int minimum,int maximum) {
        if(!(value instanceof List<?> list) || list.size()<minimum || list.size()>maximum)throw invalid();return list;
    }
    private static Set<String> strings(Object value,int minimum,int maximum,int length,String pattern) {
        var result=new HashSet<String>();for(var item:list(value,minimum,maximum))if(!result.add(string(item,length,pattern)))throw invalid();return result;
    }
    private static String text(Map<String,Object> row,String key,int length,String pattern){return string(row.get(key),length,pattern);}
    private static String string(Object value,int length,String pattern) {
        if(!(value instanceof String text) || text.isEmpty() || text.codePointCount(0,text.length())>length || (pattern!=null && !text.matches(pattern)))throw invalid();
        for(int i=0;i<text.length();i++)if(Character.isSurrogate(text.charAt(i))) {
            if(!Character.isHighSurrogate(text.charAt(i)) || i+1>=text.length() || !Character.isLowSurrogate(text.charAt(i+1)))throw invalid();i++;
        }
        return text;
    }
    private static UUID id(Map<String,Object> row,String key){return LogRegistrationJson.id(text(row,key,36,null));}
    private static long integer(Object value,long minimum,long maximum) {
        if(!(value instanceof Integer || value instanceof Long))throw invalid();long number=((Number)value).longValue();if(number<minimum || number>maximum)throw invalid();return number;
    }
    private static void member(Map<String,Object> row,String key,int length,String pattern,Set<String> allowed) {
        if(row.containsKey(key) && !allowed.contains(text(row,key,length,pattern)))throw unregistered();
    }
    private static String sourcePattern(){return "(?!/)(?!.*(?:^|/)\\.\\.(?:/|$))[A-Za-z0-9_./$-]+";}
    private static LogFailure invalid(){return new LogFailure(LogFailure.Kind.INVALID);}
    private static LogFailure unregistered(){return new LogFailure(LogFailure.Kind.UNREGISTERED);}
}
