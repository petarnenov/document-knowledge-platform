package org.documentknowledge.documentmanagement.adapters.outbound.verificationtemp;

import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.*;
import org.documentknowledge.documentmanagement.application.upload.VerificationWorkspacePort.Identity;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Closed private v1 format; corruption never becomes a payload selector. */
record VerificationLedger(UUID operationId,UUID ownerEpoch,Identity identity,State source,Role role,State scratch,UUID reservationId) {
    enum State { RESERVED,MAY_EXIST,DELETE_PENDING,ABSENT }
    enum Role { NONE,COPY,READBACK }
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    VerificationLedger {
        Objects.requireNonNull(operationId);Objects.requireNonNull(ownerEpoch);Objects.requireNonNull(identity);Objects.requireNonNull(source);Objects.requireNonNull(role);Objects.requireNonNull(scratch);
        if(role==Role.NONE&&(reservationId!=null||scratch!=State.RESERVED&&scratch!=State.ABSENT)||role!=Role.NONE&&reservationId==null||source==State.ABSENT&&scratch!=State.ABSENT)throw unsafe();
    }
    VerificationLedger epoch(UUID epoch){return new VerificationLedger(operationId,epoch,identity,source,role,scratch,reservationId);}
    VerificationLedger source(State value){return new VerificationLedger(operationId,ownerEpoch,identity,value,role,scratch,reservationId);}
    VerificationLedger scratch(Role next,State state,UUID id){return new VerificationLedger(operationId,ownerEpoch,identity,source,next,state,id);}
    byte[] encode(){
        var record=new LinkedHashMap<String,Object>();record.put("version",1);record.put("operationId",operationId.toString());record.put("ownerEpoch",ownerEpoch.toString());record.put("tenantDigest",identity.tenantDigest());record.put("actorDigest",identity.actorDigest());record.put("documentId",identity.documentId().toString());record.put("uploadId",identity.uploadId().toString());record.put("declaredSize",identity.declaredSize());record.put("source",source.name());
        var buffer=new LinkedHashMap<String,Object>();buffer.put("role",role.name());buffer.put("state",scratch.name());buffer.put("reservationId",reservationId==null?null:reservationId.toString());record.put("scratch",buffer);
        byte[] bytes=JSON.writeValueAsBytes(record);if(bytes.length>2048)throw unsafe();return bytes;
    }
    static VerificationLedger decode(byte[] bytes){
        try{
            if(bytes.length<1||bytes.length>2048)throw unsafe();
            String utf8=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var root=keys(JSON.readValue(utf8,Object.class),Set.of("version","operationId","ownerEpoch","tenantDigest","actorDigest","documentId","uploadId","declaredSize","source","scratch"));
            if(number(root.get("version"))!=1)throw unsafe();var child=keys(root.get("scratch"),Set.of("role","state","reservationId"));
            var identity=new Identity(text(root.get("tenantDigest")),text(root.get("actorDigest")),uuid(root.get("documentId")),uuid(root.get("uploadId")),number(root.get("declaredSize")));
            return new VerificationLedger(uuid(root.get("operationId")),uuid(root.get("ownerEpoch")),identity,State.valueOf(text(root.get("source"))),Role.valueOf(text(child.get("role"))),State.valueOf(text(child.get("state"))),child.get("reservationId")==null?null:uuid(child.get("reservationId")));
        }catch(Exception invalid){throw unsafe();}
    }
    private static Map<?,?> keys(Object value,Set<String> keys){if(!(value instanceof Map<?,?> map)||!map.keySet().equals(keys))throw unsafe();return map;}
    private static String text(Object value){if(!(value instanceof String text))throw unsafe();return text;}
    private static UUID uuid(Object value){String text=text(value);UUID id=UUID.fromString(text);if(!id.toString().equals(text))throw unsafe();return id;}
    private static long number(Object value){if(!(value instanceof Integer||value instanceof Long))throw unsafe();return ((Number)value).longValue();}
    private static IllegalArgumentException unsafe(){return new IllegalArgumentException("Invalid verification ownership");}
}
