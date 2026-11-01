package org.documentknowledge.documentmanagement.adapters.outbound.generictemp;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.documentknowledge.documentmanagement.application.upload.GenericTempPort.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenericTempLedgerTest {
 GenericTempLedger value(Role role){return new GenericTempLedger(role,role==Role.TRANSFER?9:1,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),new Identity("a".repeat(64),"b".repeat(64),UUID.randomUUID(),UUID.randomUUID(),role==Role.TRANSFER?null:UUID.randomUUID(),100_000_000),GenericTempLedger.State.RESERVED);}
 @Test void completeMaximumClosedSchemaRoundTripsEveryRoleAndState(){for(var role:Role.values())for(var state:GenericTempLedger.State.values()){var value=value(role).state(state);assertTrue(value.encode().length<2048);assertEquals(value,GenericTempLedger.decode(value.encode()));assertNotEquals(value.ownerEpoch(),value.epoch(UUID.randomUUID()).ownerEpoch());}}
 @Test void corruptOrUnboundedDataCannotBecomeAnOwnedSelector(){
  String transfer=new String(value(Role.TRANSFER).encode(),StandardCharsets.UTF_8),preappend=new String(value(Role.PREAPPEND).encode(),StandardCharsets.UTF_8);
  for(String bad:List.of("{}",transfer+"{}",transfer.replace("\"version\":1","\"version\":1,\"version\":1"),transfer.replace("\"version\":1","\"version\":2"),transfer.replace("\"lane\":9","\"lane\":10"),preappend.replace("\"lane\":1","\"lane\":2"),transfer.replace("\"receiptId\":null","\"receiptId\":\""+UUID.randomUUID()+"\""),preappend.replaceFirst("\"receiptId\":\"[a-f0-9-]+\"","\"receiptId\":null"),transfer.replace("\"declaredSize\":100000000","\"declaredSize\":100000001"),transfer.replace("\"lane\":9","\"lane\":9.0"),transfer.replace("\"TRANSFER\"","null"),transfer.replace("\"RESERVED\"","\"UNKNOWN\""),transfer.replace("\"version\":1","\"unexpected\":true,\"version\":1")))assertThrows(IllegalArgumentException.class,()->GenericTempLedger.decode(bad.getBytes(StandardCharsets.UTF_8)));
  assertThrows(IllegalArgumentException.class,()->GenericTempLedger.decode(new byte[]{(byte)0xff}));assertThrows(IllegalArgumentException.class,()->GenericTempLedger.decode(new byte[2049]));
 }
}
