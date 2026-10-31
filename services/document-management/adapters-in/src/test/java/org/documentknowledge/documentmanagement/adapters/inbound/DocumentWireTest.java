package org.documentknowledge.documentmanagement.adapters.inbound;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocumentWireTest {
    private byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private void bad(String value){assertEquals(400,assertThrows(DmFailure.class,()->DocumentWire.decode(bytes(value),"application/json")).status());}
    @Test void strictRawParserRejectsUnknownTopLevelAndAmbiguousJson() {
        for(String value:List.of("null","[]","1","\"text\"","{}{}","{","{\"x\":1,\"x\":2}"))bad(value);
        assertThrows(DmFailure.class,()->DocumentWire.decode(null,"application/json"));assertThrows(DmFailure.class,()->DocumentWire.decode(new byte[0],"application/json"));
        assertEquals(413,assertThrows(DmFailure.class,()->DocumentWire.decode(new byte[16385],"application/json")).status());
        assertEquals(415,assertThrows(DmFailure.class,()->DocumentWire.decode(bytes("{}"),null)).status());
        assertEquals(415,assertThrows(DmFailure.class,()->DocumentWire.decode(bytes("{}"),"text/plain")).status());
        assertEquals(Map.of(),DocumentWire.decode(bytes("{}"),"application/json; charset=UTF-8"));
    }
    @Test void metadataBoundsAreRejectedAfterMultiChunkSizedRawParsing() {
        String payload="{\"metadata\":{\"title\":\"ok\",\"description\":\""+"x".repeat(4097)+"\"},\"classification\":\"development-restricted\",\"classificationPolicyRevision\":1}";
        Map<?,?> decoded=DocumentWire.decode(bytes(payload),"application/json");assertEquals(400,assertThrows(DmFailure.class,()->DocumentWire.draft(decoded)).status());
        for(Object value:List.of("bad",List.of(),Map.of("title","ok"),Map.of("title"," ","description","")))assertThrows(DmFailure.class,()->DocumentWire.metadata(value));
        assertThrows(DmFailure.class,()->DocumentWire.metadata(null));
    }
    @Test void structuralNumbersAndIdentifiersAreExactAndBounded() {
        for(Object value:List.of(-1,9007199254740992L,0.5,"1",true,java.math.BigInteger.ONE))assertThrows(DmFailure.class,()->DocumentWire.revision(value));
        assertThrows(DmFailure.class,()->DocumentWire.revision(null));assertEquals(0,DocumentWire.revision(0));assertEquals(9007199254740991L,DocumentWire.revision(9007199254740991L));
        for(String value:List.of("not-id","1-1-1-1-1","00000000-0000-0000-0000-00000000000g"))assertThrows(DmFailure.class,()->DocumentWire.id(value));assertThrows(DmFailure.class,()->DocumentWire.id(null));
        assertThrows(DmFailure.class,()->DocumentWire.text(null,0,10));assertThrows(DmFailure.class,()->DocumentWire.text("bad\n",0,10));
    }
    @Test void aclClosedShapeRequiresBoundedEnumeratedEntriesAndAbsoluteIssuer() {
        for(Object changes:List.of(List.of(),"not-array",Collections.nCopies(101,Map.of())))assertThrows(DmFailure.class,()->DocumentWire.acl(Map.of("expectedRevision",0,"mode","RESTRICT","changes",changes)));
        Map<String,Object> entry=Map.of("operation","REMOVE_ALLOW","principal",Map.of("issuer","https://issuer.example","subject","user"),"action","document.read");
        assertEquals(1,DocumentWire.acl(Map.of("expectedRevision",0,"mode","RESTRICT","changes",List.of(entry))).changes().size());
        for(var invalid:List.of(Map.of("operation","WILDCARD","principal",entry.get("principal"),"action","document.read"),Map.of("operation","REMOVE_ALLOW","principal",Map.of("issuer","relative","subject","user"),"action","document.read"),Map.of("operation","REMOVE_ALLOW","principal",entry.get("principal"),"action","admin")))assertThrows(DmFailure.class,()->DocumentWire.acl(Map.of("expectedRevision",0,"mode","RESTRICT","changes",List.of(invalid))));
    }
}
