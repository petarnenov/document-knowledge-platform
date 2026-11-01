package org.documentknowledge.knowledgebase.adapters.inbound;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.documentknowledge.knowledgebase.domain.KbModels.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeWireTest {
    Map<?,?> json(String input){return KnowledgeWire.decode(input.getBytes(StandardCharsets.UTF_8),"application/json; charset=utf-8");}
    void bad(Runnable action){assertEquals(400,assertThrows(KbFailure.class,action::run).status());}
    @Test void malformedJsonAndExactPrimitiveBoundsNeverCoerce(){
        for(String input:List.of("null","1","[]","{","{} {}","{\"x\":1,\"x\":2}"))bad(()->json(input));
        bad(()->KnowledgeWire.decode(new byte[0],"application/json"));bad(()->KnowledgeWire.decode(null,"application/json"));
        assertEquals(413,assertThrows(KbFailure.class,()->KnowledgeWire.decode(new byte[16385],"application/json")).status());
        assertEquals(415,assertThrows(KbFailure.class,()->KnowledgeWire.decode(new byte[0],null)).status());
        assertEquals(415,assertThrows(KbFailure.class,()->KnowledgeWire.decode(new byte[0],"text/plain")).status());
        for(Object value:List.of(-1,1.0,"1",true,9007199254740992L))bad(()->KnowledgeWire.revision(value));
        bad(()->KnowledgeWire.revision(null));assertEquals(0,KnowledgeWire.revision(0));assertEquals(9007199254740991L,KnowledgeWire.revision(9007199254740991L));
        bad(()->KnowledgeWire.text(1,0,1));bad(()->KnowledgeWire.text("",1,2));bad(()->KnowledgeWire.text("long",0,1));
        bad(()->KnowledgeWire.id("A".repeat(36)));bad(()->KnowledgeWire.token("!".repeat(43)));assertEquals("A".repeat(43),KnowledgeWire.token("A".repeat(43)));
        bad(()->KnowledgeWire.action("admin"));assertEquals(Action.CREATE,KnowledgeWire.action(Action.CREATE.wire()));
        bad(()->KnowledgeWire.enumeration(Status.class,"unknown"));
    }
    @Test void utf16AndMalformedUtf8AreNotAlternativeTransports(){
        bad(()->KnowledgeWire.decode("{}".getBytes(java.nio.charset.StandardCharsets.UTF_16),"application/json"));
        bad(()->KnowledgeWire.decode(new byte[]{(byte)0xc3,(byte)0x28},"application/json"));
    }
    @Test void maximumSchemaLegalPageFitsExplicitResponseBound(){
        var actor=new Principal("https://issuer.example","user");var list=new ArrayList<Object>();
        for(int i=0;i<50;i++){UUID id=UUID.randomUUID();var collection=new org.documentknowledge.knowledgebase.domain.KbModels.Collection(id,"tenant",0,new Metadata(String.valueOf((char)1).repeat(256),String.valueOf((char)1).repeat(4096)),Status.ACTIVE,0,new AudiencePolicy(id,1,Set.of(),java.time.Instant.EPOCH),Set.of(),actor,java.time.Instant.EPOCH);list.add(KnowledgeWire.collection(collection));}
        byte[] bytes=KnowledgeWire.encode(KnowledgeWire.page(list,null));assertTrue(bytes.length>1048576);assertTrue(bytes.length<KnowledgeWire.RESPONSE_LIMIT);
    }
    @Test void maximumMembershipAclAndAudienceResponsesIncludeAllClosedFieldsWithinCap(){
        String issuer="https://issuer.example/"+"あ".repeat(2048-"https://issuer.example/".length());assertEquals(2048,issuer.length());
        UUID collectionId=UUID.randomUUID();var creator=new Principal(issuer,String.valueOf((char)1).repeat(256));var rows=new ArrayList<Object>();
        for(int i=0;i<50;i++)rows.add(KnowledgeWire.membership(new Membership(UUID.randomUUID(),"tenant",collectionId,UUID.randomUUID(),9007199254740991L,State.INACTIVE,creator,java.time.Instant.EPOCH,String.valueOf((char)1).repeat(1024))));
        assertTrue(KnowledgeWire.encode(KnowledgeWire.page(rows,"a".repeat(2048))).length<KnowledgeWire.RESPONSE_LIMIT);
        var rules=new HashSet<AclRule>();var audience=new HashSet<AudienceRule>();
        for(int i=0;i<100;i++){String subject=String.valueOf((char)1).repeat(253)+String.format("%03d",i);rules.add(new AclRule(new Principal(issuer,subject),Action.DELEGATE,Effect.DENY));audience.add(new AudienceRule(Effect.ALLOW,new Selector(SelectorKind.FIRM,issuer,subject)));}
        var policy=new AudiencePolicy(collectionId,9007199254740991L,audience,java.time.Instant.EPOCH);
        var collection=new org.documentknowledge.knowledgebase.domain.KbModels.Collection(collectionId,"tenant",9007199254740991L,new Metadata("x",""),Status.ARCHIVED,9007199254740991L,policy,rules,creator,java.time.Instant.EPOCH);
        assertEquals(100,((java.util.Collection<?>)KnowledgeWire.acl(collection).get("rules")).size());assertTrue(KnowledgeWire.encode(KnowledgeWire.acl(collection)).length<KnowledgeWire.RESPONSE_LIMIT);assertTrue(KnowledgeWire.encode(KnowledgeWire.audience(policy)).length<KnowledgeWire.RESPONSE_LIMIT);
        var mixed=new AudiencePolicy(collectionId,1,Set.of(new AudienceRule(Effect.ALLOW,new Selector(SelectorKind.PRINCIPAL,issuer,"user")),new AudienceRule(Effect.DENY,new Selector(SelectorKind.RESOURCE_SCOPE,issuer,"scope")),new AudienceRule(Effect.ALLOW,new Selector(SelectorKind.TENANT,null,"tenant"))),java.time.Instant.EPOCH);assertEquals(3,((List<?>)KnowledgeWire.audience(mixed).get("rules")).size());
    }
    @Test void immutableCommandHashIgnoresObjectOrderButPreservesArrayOrder(){
        assertEquals(KnowledgeWire.hash(json("{\"a\":{\"x\":1,\"y\":2},\"b\":[1,2]}")),KnowledgeWire.hash(json("{\"b\":[1,2],\"a\":{\"y\":2,\"x\":1}}")));
        assertNotEquals(KnowledgeWire.hash(json("{\"a\":[1,2]}")),KnowledgeWire.hash(json("{\"a\":[2,1]}")));
    }
    @Test void allClosedAudienceSelectorsAndInvalidRulesAreIndependentlyChecked(){
        String principal="{\"kind\":\"PRINCIPAL\",\"principal\":{\"issuer\":\"https://issuer\",\"subject\":\"user\"}}";
        for(String selector:List.of(principal,"{\"kind\":\"TENANT\",\"tenant\":\"t\"}","{\"kind\":\"FIRM\",\"issuer\":\"https://issuer\",\"firmId\":\"firm\"}","{\"kind\":\"RESOURCE_SCOPE\",\"issuer\":\"https://issuer\",\"resourceScopeId\":\"scope\"}")){
            var change=KnowledgeWire.audienceChange(json("{\"expectedRevision\":0,\"rules\":[{\"effect\":\"ALLOW\",\"selector\":"+selector+"}],\"reason\":\"test\"}"));assertEquals(1,change.rules().size());
        }
        String rule="{\"effect\":\"ALLOW\",\"selector\":"+principal+"}";
        assertEquals("INVALID_POLICY",assertThrows(KbFailure.class,()->KnowledgeWire.audienceChange(json("{\"expectedRevision\":0,\"rules\":["+rule+","+rule+"],\"reason\":\"test\"}"))).code());
        for(String value:List.of("null","{}","[1]"))bad(()->KnowledgeWire.audienceChange(json("{\"expectedRevision\":0,\"rules\":"+value+",\"reason\":\"test\"}")));
        bad(()->KnowledgeWire.principal(json("{\"issuer\":\"relative\",\"subject\":\"user\"}")));
        bad(()->KnowledgeWire.principal(json("{\"issuer\":\"%invalid\",\"subject\":\"user\"}")));
        bad(()->KnowledgeWire.audienceChange(json("{\"expectedRevision\":0,\"rules\":[{\"effect\":\"ALLOW\",\"selector\":{\"kind\":\"FIRM\",\"issuer\":\"relative\",\"firmId\":\"firm\"}}],\"reason\":\"test\"}")));
        bad(()->KnowledgeWire.audienceChange(json("{\"expectedRevision\":0,\"rules\":[{\"effect\":\"ALLOW\",\"selector\":{\"kind\":\"FIRM\",\"issuer\":\"%invalid\",\"firmId\":\"firm\"}}],\"reason\":\"test\"}")));
    }
    @Test void AclAndMembershipDoNotAcceptImplicitScopeOrUnboundedCollections(){
        for(String changes:List.of("[]","null","{}"))bad(()->KnowledgeWire.aclChange(json("{\"expectedRevision\":0,\"mode\":\"DELEGATE\",\"changes\":"+changes+",\"reason\":\"test\"}")));
        bad(()->KnowledgeWire.membership(json("{\"expectedRevision\":0,\"documentId\":\"00000000-0000-0000-0000-000000000001\",\"expectedDocumentRevision\":0,\"sharingScope\":\"GLOBAL\",\"reason\":\"test\"}")));
        assertEquals(503,assertThrows(KbFailure.class,()->KnowledgeWire.encode("x".repeat(2097153))).status());
        var receipt=new Receipt(UUID.randomUUID(),null,0);assertEquals(Set.of("collectionId","revision"),KnowledgeWire.receipt(receipt).keySet());assertTrue(KnowledgeWire.page(List.of(),null).containsKey("nextCursor"));
    }
}
