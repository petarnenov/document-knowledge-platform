package org.documentknowledge.loggingclient;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.documentknowledge.observability.ExecutionIdentity;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Private fixed wire codec; no service dependency or caller-supplied JSON. */
final class LogWire {
    static final int EVENT_BYTES=8192, BATCH_BYTES=32768, BATCH_EVENTS=32;
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(BATCH_BYTES)
            .maxNestingDepth(8).maxStringLength(512).maxNumberLength(32).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    record Prepared(UUID id, byte[] bytes) {}
    enum Receipt { COMMITTED, UNKNOWN, EXPIRED }

    static Prepared prepare(LifecycleEvent event,ExecutionIdentity execution,LogVocabulary vocabulary) {
        var metadata=event.metadata();
        if(!metadata.execution().equals(execution) || !vocabulary.events().contains(metadata.eventCode())
                || !vocabulary.phases().contains(event.phase()))throw new IllegalArgumentException("UNREGISTERED_LOG_EVENT");
        var build=execution.build();var row=new LinkedHashMap<String,Object>();
        row.put("eventId",metadata.eventId().toString());row.put("buildId",build.buildId().toString());
        row.put("runId",execution.runId().toString());row.put("instanceId",execution.instanceId().toString());
        row.put("component",build.component());row.put("gitSha",build.gitSha());row.put("sourceFingerprint",build.sourceFingerprint());
        row.put("timestamp",metadata.timestamp().toString());row.put("severity",metadata.severity().name());
        row.put("eventName",metadata.eventCode().wireName());row.put("correlationId",metadata.correlationId().toString());
        if(metadata.trace().traceId()!=null)row.put("traceId",metadata.trace().traceId());
        if(metadata.trace().spanId()!=null)row.put("spanId",metadata.trace().spanId());
        row.put("context",Map.of("phase",event.phase(),"outcome",event.outcome().name(),"durationMs",event.durationMs(),
            "attempt",event.attempt(),"attributes",Map.of("count",event.count(),"cleanupSettled",event.cleanupSettled())));
        byte[] bytes=JSON.writeValueAsBytes(row);
        if(bytes.length>EVENT_BYTES)throw new IllegalArgumentException("LOG_EVENT_TOO_LARGE");
        return new Prepared(metadata.eventId(),bytes);
    }

    static byte[] batch(List<Prepared> events) {
        if(events.isEmpty() || events.size()>BATCH_EVENTS)throw new IllegalArgumentException("INVALID_LOG_BATCH");
        var bytes=new ByteArrayOutputStream();bytes.writeBytes("{\"schemaVersion\":1,\"events\":[".getBytes(StandardCharsets.US_ASCII));
        var ids=new HashSet<UUID>();
        for(var event:events) {
            if(!ids.add(event.id()))throw new IllegalArgumentException("DUPLICATE_LOG_EVENT");
            if(event.bytes().length==0 || event.bytes().length>EVENT_BYTES
                    || bytes.size()+event.bytes().length+3>BATCH_BYTES)throw new IllegalArgumentException("LOG_BATCH_TOO_LARGE");
            if(ids.size()>1)bytes.write(',');bytes.writeBytes(event.bytes());
        }
        bytes.writeBytes("]}".getBytes(StandardCharsets.US_ASCII));
        if(bytes.size()>BATCH_BYTES)throw new IllegalArgumentException("LOG_BATCH_TOO_LARGE");
        return bytes.toByteArray();
    }

    static byte[] lookup(List<Prepared> events) {
        return JSON.writeValueAsBytes(Map.of("eventIds",events.stream().map(event->event.id().toString()).toList()));
    }

    static Map<UUID,Receipt> receipts(byte[] bytes,List<Prepared> events,boolean commitOnly) {
        try {
            if(bytes.length==0 || bytes.length>BATCH_BYTES)throw new IllegalArgumentException();
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            Object value=JSON.readValue(text,Object.class);
            if(!(value instanceof Map<?,?> root) || !root.keySet().equals(Set.of("receipts"))
                    || !(root.get("receipts") instanceof List<?> rows) || rows.size()!=events.size())throw new IllegalArgumentException();
            var wanted=new HashSet<>(events.stream().map(Prepared::id).toList());var result=new HashMap<UUID,Receipt>();
            for(Object item:rows) {
                if(!(item instanceof Map<?,?> row))throw new IllegalArgumentException();
                var id=UUID.fromString((String)row.get("eventId"));var state=Receipt.valueOf((String)row.get("state"));
                if(!id.toString().equals(row.get("eventId")) || !wanted.remove(id) || (commitOnly && state!=Receipt.COMMITTED))throw new IllegalArgumentException();
                if(state==Receipt.COMMITTED) {
                    if(!row.keySet().equals(Set.of("eventId","state","receivedAt")))throw new IllegalArgumentException();
                    Instant.parse((String)row.get("receivedAt"));
                } else if(!row.keySet().equals(Set.of("eventId","state")))throw new IllegalArgumentException();
                result.put(id,state);
            }
            if(!wanted.isEmpty())throw new IllegalArgumentException();
            return Map.copyOf(result);
        } catch(Exception invalid) {throw new IllegalArgumentException("INVALID_LOG_RECEIPTS");}
    }
}
