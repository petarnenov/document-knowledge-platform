package org.documentknowledge.devlogging.adapters.inbound;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.documentknowledge.devlogging.domain.*;
import org.documentknowledge.observability.BuildIdentity;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Closed untrusted wire parser; contains no credential or server grant construction. */
final class LogRegistrationJson {
    record Registration(ArtifactRegistration artifact, LogScope.Description scope, Instant acceptUntil) { }
    record Delegation(UUID parent, ArtifactRegistration artifact, LogScope.Description scope, UUID epoch, Instant captureUntil) { }
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(32768).maxNestingDepth(16)
            .maxStringLength(512).maxNumberLength(16).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    static Registration registration(byte[] bytes) {
        try {
            var row=parse(bytes,Set.of("binding","scope","acceptUntil"));
            return new Registration(artifact(row.get("binding")),scope(row.get("scope")),timestamp(text(row,"acceptUntil")));
        } catch(Exception invalid) { throw new LogFailure(LogFailure.Kind.INVALID); }
    }
    static Delegation delegation(byte[] bytes) {
        try {
            var row=parse(bytes,Set.of("parentProducerId","binding","scope","contextEpoch","captureUntil"));
            return new Delegation(id(text(row,"parentProducerId")),artifact(row.get("binding")),scope(row.get("scope")),
                id(text(row,"contextEpoch")),timestamp(text(row,"captureUntil")));
        } catch(Exception invalid) { throw new LogFailure(LogFailure.Kind.INVALID); }
    }
    private static Map<?,?> parse(byte[] bytes,Set<String> keys) throws Exception {
        if(bytes==null || bytes.length==0 || bytes.length>32768) invalid();
        String validated=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString();
        return object(JSON.readValue(validated,Object.class),keys);
    }
    private static ArtifactRegistration artifact(Object value) {
        var row=object(value,Set.of("buildId","runId","component","gitSha","sourceFingerprint","manifestSha256","vocabularySha256"));
        return new ArtifactRegistration(new ProducerBinding(new BuildIdentity(id(text(row,"buildId")),text(row,"component"),
            text(row,"gitSha"),text(row,"sourceFingerprint")),id(text(row,"runId"))),text(row,"manifestSha256"),text(row,"vocabularySha256"));
    }
    private static LogScope.Description scope(Object value) {
        if(!(value instanceof Map<?,?> row)) { invalid(); return null; }
        var kind=LogScope.Kind.valueOf(text(row,"scopeKind"));
        var keys=switch(kind) {
            case TENANT -> Set.of("scopeKind","issuer","subject","tenantId");
            case AUTHENTICATED_UNSELECTED -> Set.of("scopeKind","issuer","subject");
            case OPERATOR_ONLY,UNATTRIBUTED -> Set.of("scopeKind");
        };
        object(row,keys);
        String issuer=keys.contains("issuer") ? text(row,"issuer") : null;
        if(issuer!=null && !java.net.URI.create(issuer).isAbsolute()) invalid();
        return new LogScope.Description(kind,issuer,keys.contains("subject") ? text(row,"subject") : null,
            keys.contains("tenantId") ? text(row,"tenantId") : null);
    }
    static UUID id(String value) {
        if(value==null || !value.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) invalid();
        var id=UUID.fromString(value); if(id.equals(new UUID(0,0))) invalid(); return id;
    }
    static Instant timestamp(String value) {
        if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt](?:[01][0-9]|2[0-3]):[0-5][0-9]:(?:[0-5][0-9]|60)(?:\\.[0-9]+)?(?:[Zz]|[+-](?:[01][0-9]|2[0-3]):[0-5][0-9])")) invalid();
        return Instant.parse(value);
    }
    private static Map<?,?> object(Object value,Set<String> keys) {
        if(!(value instanceof Map<?,?> row) || !row.keySet().equals(keys)) { invalid(); return null; }
        return row;
    }
    private static String text(Map<?,?> row,String key) {
        if(!(row.get(key) instanceof String value) || value.isEmpty() || value.length()>512) { invalid(); return null; }
        return value;
    }
    private static void invalid() { throw new LogFailure(LogFailure.Kind.INVALID); }
}
