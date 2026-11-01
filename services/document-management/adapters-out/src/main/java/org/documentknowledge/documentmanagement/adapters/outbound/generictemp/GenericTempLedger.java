package org.documentknowledge.documentmanagement.adapters.outbound.generictemp;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import org.documentknowledge.documentmanagement.application.upload.GenericTempPort.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Exact private schema v1. It cannot supply a caller-controlled path or grant. */
record GenericTempLedger(Role role,int lane,UUID operationId,UUID reservationId,UUID ownerEpoch,Identity identity,State state){
 enum State {RESERVED,MAY_EXIST,DELETE_PENDING,ABSENT}
 private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
 GenericTempLedger{Objects.requireNonNull(role);Objects.requireNonNull(operationId);Objects.requireNonNull(reservationId);Objects.requireNonNull(ownerEpoch);Objects.requireNonNull(identity);Objects.requireNonNull(state);if(lane<0||lane>=(role==Role.TRANSFER?10:2)||(role==Role.TRANSFER?identity.receiptId()!=null:identity.receiptId()==null))throw invalid();}
 GenericTempLedger state(State next){return new GenericTempLedger(role,lane,operationId,reservationId,ownerEpoch,identity,next);}
 GenericTempLedger epoch(UUID next){return new GenericTempLedger(role,lane,operationId,reservationId,next,identity,state);}
 byte[] encode(){var value=new LinkedHashMap<String,Object>();value.put("version",1);value.put("role",role.name());value.put("lane",lane);value.put("operationId",operationId.toString());value.put("reservationId",reservationId.toString());value.put("ownerEpoch",ownerEpoch.toString());value.put("tenantDigest",identity.tenantDigest());value.put("actorDigest",identity.actorDigest());value.put("documentId",identity.documentId().toString());value.put("uploadId",identity.uploadId().toString());value.put("receiptId",identity.receiptId()==null?null:identity.receiptId().toString());value.put("declaredSize",identity.declaredSize());value.put("state",state.name());byte[] bytes=JSON.writeValueAsBytes(value);if(bytes.length>2048)throw invalid();return bytes;}
 static GenericTempLedger decode(byte[] bytes){try{
  if(bytes.length<1||bytes.length>2048)throw invalid();String value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();Object parsed=JSON.readValue(value,Object.class);
  if(!(parsed instanceof Map<?,?> v)||!v.keySet().equals(Set.of("version","role","lane","operationId","reservationId","ownerEpoch","tenantDigest","actorDigest","documentId","uploadId","receiptId","declaredSize","state"))||number(v.get("version"))!=1)throw invalid();
  return new GenericTempLedger(Role.valueOf(text(v.get("role"))),Math.toIntExact(number(v.get("lane"))),uuid(v.get("operationId")),uuid(v.get("reservationId")),uuid(v.get("ownerEpoch")),new Identity(text(v.get("tenantDigest")),text(v.get("actorDigest")),uuid(v.get("documentId")),uuid(v.get("uploadId")),v.get("receiptId")==null?null:uuid(v.get("receiptId")),number(v.get("declaredSize"))),State.valueOf(text(v.get("state"))));
 }catch(Exception failure){throw invalid();}}
 private static String text(Object value){if(!(value instanceof String text))throw invalid();return text;}
 private static UUID uuid(Object value){String text=text(value);UUID uuid=UUID.fromString(text);if(!uuid.toString().equals(text))throw invalid();return uuid;}
 private static long number(Object value){if(!(value instanceof Integer||value instanceof Long))throw invalid();return ((Number)value).longValue();}
 private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid generic temporary ownership");}
}
