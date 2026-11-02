package org.documentknowledge.devlogging.adapters.inbound;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.documentknowledge.devlogging.application.RequestDeadline;
import org.documentknowledge.devlogging.domain.*;
import org.documentknowledge.observability.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogIngestionJsonTest {
    static final BuildIdentity BUILD=new BuildIdentity(UUID.randomUUID(),"browser-bff","a".repeat(40),"b".repeat(64));
    static final ProducerBinding BINDING=new ProducerBinding(BUILD,UUID.randomUUID());
    static final UUID EVENT=UUID.randomUUID(),INSTANCE=UUID.randomUUID(),CORRELATION=UUID.randomUUID();
    static final ProducerScope AUTHORITY=new ProducerScope(UUID.randomUUID(),new LogScope(UUID.randomUUID(),LogScope.Kind.TENANT,"https://issuer.invalid","actor","tenant-a"),BINDING,Instant.now().plusSeconds(3600));
    static final tools.jackson.databind.json.JsonMapper JSON=tools.jackson.databind.json.JsonMapper.builder().build();
    static Map<String,Object> vocabulary() {
        return new HashMap<>(Map.of("events",Arrays.stream(EventCode.values()).map(EventCode::wireName).toList(),"phases",List.of("capture","commit"),
            "errorCodes",List.of("DB_UNAVAILABLE"),"exceptionClasses",List.of("java.lang.IllegalStateException","java.sql.SQLException"),
            "dependencies",List.of(Map.of("name","postgresql","version","42.7.14","vendorBuildProvenance","VERIFIED","imageDigest","sha256:"+"e".repeat(64))),
            "frames",List.of(Map.of("symbol","Example.process","source","Example.java"))));
    }
    static VerifiedLogManifest manifest(Map<String,Object> vocabulary) {
        // Synthetic trusted catalog-port fixture; full private manifest schema is verified separately.
        String digest=VerifiedLogManifest.sha256(LogCanonicalJson.bytes(vocabulary));
        byte[] raw=LogCanonicalJson.bytes(Map.of("buildId",BUILD.buildId().toString(),"component",BUILD.component(),"gitSha",BUILD.gitSha(),
            "sourceFingerprint",BUILD.sourceFingerprint(),"vocabularySha256",digest,"vocabulary",vocabulary));
        return new VerifiedLogManifest(BUILD,VerifiedLogManifest.sha256(raw),digest,raw);
    }
    static Map<String,Object> event(Map<String,Object> context) {
        var value=new LinkedHashMap<String,Object>();value.put("eventId",EVENT.toString());value.put("buildId",BUILD.buildId().toString());
        value.put("runId",BINDING.runId().toString());value.put("instanceId",INSTANCE.toString());value.put("component",BUILD.component());
        value.put("gitSha",BUILD.gitSha());value.put("sourceFingerprint",BUILD.sourceFingerprint());value.put("timestamp","2026-11-02T21:00:00.123456Z");
        value.put("severity","DEBUG");value.put("eventName","operation.started");value.put("correlationId",CORRELATION.toString());value.put("context",context);return value;
    }
    static byte[] wire(Object value){return JSON.writeValueAsBytes(value);}
    static byte[] batch(Map<String,Object> event){return wire(Map.of("schemaVersion",1,"events",List.of(event)));}
    LogBatch parse(byte[] bytes){return LogIngestionJson.batch(bytes,AUTHORITY,manifest(vocabulary()),RequestDeadline.start());}
    void fails(LogFailure.Kind kind,byte[] bytes){assertEquals(kind,assertThrows(LogFailure.class,()->parse(bytes)).kind());}
    @Test void bindsExactMetadataAndSanitizesUnknownSymbolsWithDeterministicCounts() {
        var context=new HashMap<String,Object>(Map.of("phase","capture","outcome","STARTED","status",200,"durationMs",0L,"remainingDeadlineMs",4500,"attempt",1,"errorCode","DB_UNAVAILABLE","sqlState","08001"));
        context.put("exceptionClass","unregistered.SecretClass");context.put("causeClasses",List.of("java.sql.SQLException","unknown.HiddenClass"));
        context.put("stackFrames",List.of(Map.of("symbol","Example.process","source","Example.java","line",12),Map.of("symbol","Unknown.private","source","Unknown.java")));
        context.put("dependency","postgresql");context.put("dependencyVersion","42.7.14");context.put("dependencyImageDigest","sha256:"+"e".repeat(64));
        context.put("attributes",Map.of("bytes",12,"count",2,"queueDepth",0,"cancelRequested",false,"cleanupSettled",true,"vendorBuildProvenance","VERIFIED"));
        context.put("redactedFields",0);context.put("truncatedFields",0);context.put("unregisteredSymbols",2);
        var result=parse(batch(event(context)));var stored=result.events().getFirst();var payload=JSON.readValue(stored.payload(),Map.class);
        var safe=(Map<?,?>)payload.get("context");assertFalse(safe.containsKey("exceptionClass"));assertEquals(5,safe.get("unregisteredSymbols"));
        assertEquals(List.of("java.sql.SQLException"),safe.get("causeClasses"));assertEquals(1,((List<?>)safe.get("stackFrames")).size());
        assertFalse(new String(stored.payload(),StandardCharsets.UTF_8).contains("HiddenClass"));assertFalse(new String(stored.payload(),StandardCharsets.UTF_8).contains("SecretClass"));
        assertEquals(EVENT,stored.metadata().eventId());assertEquals(BUILD,stored.metadata().execution().build());assertEquals(BINDING.runId(),stored.metadata().execution().runId());
        assertEquals(INSTANCE,stored.metadata().execution().instanceId());assertEquals(CORRELATION,stored.metadata().correlationId());
        assertEquals(new TraceContext(null,null),stored.metadata().trace());assertEquals(Instant.parse((String)payload.get("timestamp")),stored.metadata().timestamp());
        assertEquals(stored.payloadHash(),parse(batch(event(context))).events().getFirst().payloadHash());
    }
    @Test void canonicalizesWireOrderWhitespaceTraceAndKnownOptionalFrames() {
        var row=event(Map.of("exceptionClass","java.lang.IllegalStateException","causeClasses",List.of(),"stackFrames",List.of(Map.of("symbol","Example.process"))));
        row.put("traceId","1".repeat(32));row.put("spanId","2".repeat(16));var first=parse(batch(row)).events().getFirst();
        var reversed=new LinkedHashMap<String,Object>();var keys=new ArrayList<>(row.keySet());Collections.reverse(keys);keys.forEach(key->reversed.put(key,row.get(key)));
        assertArrayEquals(first.payload(),parse(batch(reversed)).events().getFirst().payload());
        assertArrayEquals(first.payload(),parse(("  "+new String(batch(reversed),StandardCharsets.UTF_8)+" \n").getBytes(StandardCharsets.UTF_8)).events().getFirst().payload());
        assertEquals(new TraceContext("1".repeat(32),"2".repeat(16)),first.metadata().trace());
    }
    @Test void rejectsUnregisteredDiagnosticValuesAndForeignExecutionWithoutAcceptingMessages() {
        for(var context:List.of(Map.<String,Object>of("phase","secret-value"),Map.<String,Object>of("errorCode","SECRET_VALUE"),
                Map.<String,Object>of("dependency","other"),Map.<String,Object>of("dependencyVersion","42.7.14"),
                Map.<String,Object>of("dependency","postgresql","dependencyVersion","secret"),Map.<String,Object>of("dependency","postgresql","dependencyImageDigest","sha256:"+"f".repeat(64)),
                Map.<String,Object>of("attributes",Map.of("vendorBuildProvenance","VERIFIED")),Map.<String,Object>of("dependency","postgresql","attributes",Map.of("vendorBuildProvenance","UNKNOWN"))))
            fails(LogFailure.Kind.UNREGISTERED,batch(event(context)));
        for(var context:List.of(Map.<String,Object>of("message","password=document"),Map.<String,Object>of("tenantId","tenant-b"),
                Map.<String,Object>of("attributes",Map.of("document","private-content")),Map.<String,Object>of("exceptionClass","error: password")))
            fails(LogFailure.Kind.INVALID,batch(event(context)));
        var row=event(Map.of());row.put("eventName","unknown.event");fails(LogFailure.Kind.UNREGISTERED,batch(row));
        row=event(Map.of());row.put("buildId",UUID.randomUUID().toString());fails(LogFailure.Kind.FORBIDDEN,batch(row));
        row=event(Map.of());row.put("actor","forged");fails(LogFailure.Kind.INVALID,batch(row));
    }
    @Test void rejectsMalformedBoundsTypesNullsAndTraceRelationships() {
        for(var context:List.of(Map.<String,Object>of("status",99),Map.<String,Object>of("status",600),Map.<String,Object>of("durationMs",-1),
                Map.<String,Object>of("attempt",1.0),Map.<String,Object>of("durationMs",9007199254740992L),Map.<String,Object>of("outcome","secret"),
                Map.<String,Object>of("sqlState","secret"),Map.<String,Object>of("causeClasses",Collections.nCopies(5,"java.sql.SQLException")),
                Map.<String,Object>of("stackFrames",Collections.nCopies(33,Map.of("symbol","Example.process"))),
                Map.<String,Object>of("stackFrames",List.of(Map.of("symbol","Example.process","source","/private/secret"))),
                Map.<String,Object>of("attributes",Map.of("cancelRequested","true")),Map.<String,Object>of("attributes",Map.of("vendorBuildProvenance","bad")),
                Map.<String,Object>of("exceptionClass","unknown.Class","unregisteredSymbols",9007199254740991L)))
            fails(LogFailure.Kind.INVALID,batch(event(context)));
        for(var change:List.of(Map.entry("eventId",new UUID(0,0).toString()),Map.entry("traceId","0".repeat(32)),Map.entry("spanId","2".repeat(16)),
                Map.entry("timestamp","2026-11-02T24:00:00Z"),Map.entry("severity","UNKNOWN"))) {
            var row=event(Map.of());row.put(change.getKey(),change.getValue());fails(LogFailure.Kind.INVALID,batch(row));
        }
        var nullContext=event(Map.of());nullContext.put("context",null);fails(LogFailure.Kind.INVALID,batch(nullContext));
        var nullText=event(Map.of());nullText.put("eventName",null);fails(LogFailure.Kind.INVALID,batch(nullText));
        fails(LogFailure.Kind.INVALID,wire(Map.of("schemaVersion",1,"events",List.of(event(Map.of()),event(Map.of())))));
        fails(LogFailure.Kind.INVALID,wire(Map.of("schemaVersion",1.0,"events",List.of(event(Map.of())))));
        fails(LogFailure.Kind.INVALID,wire(Map.of("schemaVersion",1,"events",List.of())));
        fails(LogFailure.Kind.INVALID,wire(Map.of("schemaVersion",1,"events",Collections.nCopies(33,event(Map.of())))));
        for(byte[] invalid:new byte[][]{null,new byte[0],new byte[]{(byte)0xff}}) {
            fails(LogFailure.Kind.INVALID,invalid);
        }
        fails(LogFailure.Kind.TOO_LARGE,new byte[32769]);
        for(var charset:List.of(StandardCharsets.UTF_16BE,StandardCharsets.UTF_16LE))
            fails(LogFailure.Kind.INVALID,new String(batch(event(Map.of())),StandardCharsets.UTF_8).getBytes(charset));
        String valid=new String(batch(event(Map.of())),StandardCharsets.UTF_8);
        fails(LogFailure.Kind.INVALID,(valid+" {}").getBytes(StandardCharsets.UTF_8));
        fails(LogFailure.Kind.INVALID,valid.replace("\"schemaVersion\":","\"schemaVersion\":1,\"schemaVersion\":").getBytes(StandardCharsets.UTF_8));
    }
    @Test void enforcesCanonicalEventBoundAndFailsClosedOnCorruptInstalledCatalog() {
        var vocabulary=vocabulary();var frames=new ArrayList<Map<String,Object>>();
        for(int i=0;i<32;i++)frames.add(Map.of("symbol","Frame"+i+"x".repeat(190),"source","src/"+"x".repeat(190)+i+".java"));
        vocabulary.put("frames",frames);
        assertEquals(LogFailure.Kind.TOO_LARGE,assertThrows(LogFailure.class,()->LogIngestionJson.batch(batch(event(Map.of("stackFrames",frames))),AUTHORITY,manifest(vocabulary),RequestDeadline.start())).kind());
        byte[] broken="{}".getBytes(StandardCharsets.UTF_8);var corrupt=new VerifiedLogManifest(BUILD,VerifiedLogManifest.sha256(broken),"a".repeat(64),broken);
        assertEquals(LogFailure.Kind.UNAVAILABLE,assertThrows(LogFailure.class,()->LogIngestionJson.batch(batch(event(Map.of())),AUTHORITY,corrupt,RequestDeadline.start())).kind());
    }
    @Test void boundedReceiptInputNeverAcceptsArbitraryScopeOrDuplicateIds() {
        assertEquals(List.of(EVENT),LogIngestionJson.receipts(wire(Map.of("eventIds",List.of(EVENT.toString())))));
        for(var value:List.of(Map.of("eventIds",List.of()),Map.of("eventIds",List.of(EVENT.toString(),EVENT.toString())),
                Map.of("eventIds",List.of(42)),Map.of("eventIds",List.of(new UUID(0,0).toString())),Map.of("eventIds",List.of(EVENT.toString()),"scopeId",UUID.randomUUID().toString())))
            assertThrows(LogFailure.class,()->LogIngestionJson.receipts(wire(value)));
        assertThrows(LogFailure.class,()->LogIngestionJson.receipts(new byte[]{(byte)0xff}));
    }
}
