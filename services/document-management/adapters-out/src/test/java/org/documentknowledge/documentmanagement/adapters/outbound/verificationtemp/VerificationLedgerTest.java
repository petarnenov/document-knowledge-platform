package org.documentknowledge.documentmanagement.adapters.outbound.verificationtemp;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.documentknowledge.documentmanagement.application.upload.VerificationWorkspacePort;
import org.documentknowledge.identity.Actor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerificationLedgerTest {
 VerificationLedger initial(){return new VerificationLedger(UUID.randomUUID(),UUID.randomUUID(),VerificationWorkspacePort.Identity.of("tenant",new Actor("https://issuer.example","subject"),UUID.randomUUID(),UUID.randomUUID(),100_000_000),VerificationLedger.State.RESERVED,VerificationLedger.Role.NONE,VerificationLedger.State.RESERVED,null);}
 @Test void allValidDurableStatesRoundTripWithoutUnknownFieldsOrPayloadPaths(){
  var initial=initial();var states=new ArrayList<VerificationLedger>();states.add(initial);
  var source=initial.source(VerificationLedger.State.MAY_EXIST);states.add(source);
  var copy=source.scratch(VerificationLedger.Role.COPY,VerificationLedger.State.RESERVED,UUID.randomUUID());states.add(copy);
  for(var state:VerificationLedger.State.values())states.add(copy.scratch(copy.role(),state,copy.reservationId()));
  var readback=source.scratch(VerificationLedger.Role.READBACK,VerificationLedger.State.DELETE_PENDING,UUID.randomUUID());states.add(readback);
  states.add(readback.scratch(readback.role(),VerificationLedger.State.ABSENT,readback.reservationId()).source(VerificationLedger.State.ABSENT));
  for(var record:states){byte[] encoded=record.encode();assertTrue(encoded.length<=2048);assertEquals(record,VerificationLedger.decode(encoded));assertFalse(new String(encoded,StandardCharsets.UTF_8).contains(".bin"));}
  assertEquals(source.operationId(),source.epoch(UUID.randomUUID()).operationId());
 }
 @Test void duplicateUnknownNullTypesBoundsNoncanonicalUuidAndInvalidUtf8AreClosedFailures(){
  String valid=new String(initial().encode(),StandardCharsets.UTF_8);
  for(String bad:List.of("", "null","[]","{}",valid+"{}",valid.replace("\"version\":1","\"version\":1,\"version\":1"),valid.replace("\"version\":1","\"version\":2"),valid.replace("\"version\":1","\"version\":1.0"),valid.replace("\"version\":1","\"version\":null"),valid.replace("\"version\":1","\"version\":1,\"path\":\"/foreign\""),valid.replace("\"declaredSize\":100000000","\"declaredSize\":100000001"),valid.replace("\"declaredSize\":100000000","\"declaredSize\":0"),valid.replace("\"declaredSize\":100000000","\"declaredSize\":-1"),valid.replace("\"declaredSize\":100000000","\"declaredSize\":\"100000000\""),valid.replace("\"role\":\"NONE\"","\"role\":\"INVALID\""),valid.replace("\"state\":\"RESERVED\"","\"state\":\"MAY_EXIST\""),valid.replace("\"reservationId\":null","\"reservationId\":\""+UUID.randomUUID()+"\""),valid.replace("\"source\":\"RESERVED\"","\"source\":\"ABSENT\""),valid.replace("\"source\":\"RESERVED\"","\"source\":null"),valid.replace("\"scratch\":{","\"scratch\":{\"extra\":true,"),valid.toUpperCase(Locale.ROOT)," ".repeat(2049))){assertThrows(IllegalArgumentException.class,()->VerificationLedger.decode(bad.getBytes(StandardCharsets.UTF_8)),bad);}
  for(byte[] bad:List.of(new byte[]{(byte)0xc3,0x28},new byte[2049]))assertThrows(IllegalArgumentException.class,()->VerificationLedger.decode(bad));
  var record=initial();assertThrows(IllegalArgumentException.class,()->record.scratch(VerificationLedger.Role.COPY,VerificationLedger.State.RESERVED,null));
  String copy=new String(record.source(VerificationLedger.State.MAY_EXIST).scratch(VerificationLedger.Role.COPY,VerificationLedger.State.RESERVED,UUID.randomUUID()).encode(),StandardCharsets.UTF_8);
  assertThrows(IllegalArgumentException.class,()->VerificationLedger.decode(copy.replace("\"reservationId\":\"","\"reservationId\":\"A").getBytes(StandardCharsets.UTF_8)));
 }
}
