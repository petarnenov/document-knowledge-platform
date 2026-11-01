package org.documentknowledge.browserbff.application;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocumentActionMirrorTest {
    private static final String PATH="/api/v1/documents/11111111-1111-4111-8111-111111111111/acl";
    private static final String ID="11111111-1111-4111-8111-111111111111";
    private static final String SHARE="document.share-to-collection";
    private static final Map<String,String> PRINCIPAL=Map.of("issuer","https://issuer.example","subject","staff-1");
    private static final Set<String> APPROVED=Set.of("document.create","document.inspect-draft","document.read","document.update-metadata","document.acl.restrict","document.acl.delegate","document.version.append","document.legal-hold.place","document.legal-hold.inspect","document.content.review",SHARE);
    private Map<String,Object> command(Object action,String mode,String operation){
        var change=new HashMap<String,Object>();change.put("principal",PRINCIPAL);change.put("action",action);change.put("operation",operation);
        return Map.of("expectedRevision",0,"mode",mode,"changes",List.of(change));
    }
    private Map<String,Object> acl(Object action,String effect){
        var rule=new HashMap<String,Object>();rule.put("principal",PRINCIPAL);rule.put("action",action);rule.put("effect",effect);
        return Map.of("documentId",ID,"revision",1,"rules",List.of(rule));
    }
    @Test void approvedShareDenyAndAclResponsePassTheSameClosedBoundary(){
        assertDoesNotThrow(()->DocumentWire.request("PATCH",PATH,Map.of(),command(SHARE,"RESTRICT","ADD_DENY"),"original-key"));
        assertTrue(DocumentWire.response("GET",PATH,acl(SHARE,"DENY"),"tenant-a",20));
    }
    @Test void allAndOnlyElevenApprovedActionsRetainAllFourDeltaShapesAndBothModes(){
        assertEquals(APPROVED,DocumentWire.ACTIONS);
        for(String action:APPROVED){
            for(String mode:List.of("RESTRICT","DELEGATE"))for(String operation:List.of("ADD_ALLOW","REMOVE_ALLOW","ADD_DENY","REMOVE_DENY"))
                assertDoesNotThrow(()->DocumentWire.request("PATCH",PATH,Map.of(),command(action,mode,operation),"original-key"));
            for(String effect:List.of("ALLOW","DENY"))assertTrue(DocumentWire.response("GET",PATH,acl(action,effect),"tenant-a",20));
        }
    }
    @Test void unknownActionsAndMalformedDelegationShapesStayRejected(){
        for(Object action:Arrays.asList(null,1,"document.share-to-collection.extra","document.share-to-*","document.SHARE-TO-COLLECTION","document.unknown")){
            var failure=assertThrows(BffFailure.class,()->DocumentWire.request("PATCH",PATH,Map.of(),command(action,"DELEGATE","ADD_ALLOW"),"original-key"));
            assertEquals(400,failure.status());assertEquals("INVALID_REQUEST",failure.code());
            assertFalse(DocumentWire.response("GET",PATH,acl(action,"ALLOW"),"tenant-a",20));
        }
        for(var bad:List.of(command(SHARE,"OWNER","ADD_ALLOW"),command(SHARE,"DELEGATE","REPLACE_ALLOW"),Map.<String,Object>of("expectedRevision",0,"mode","DELEGATE","changes",List.of(Map.of("principal",Map.of("issuer","https://issuer.example"),"action",SHARE,"operation","ADD_ALLOW"))))){
            assertThrows(BffFailure.class,()->DocumentWire.request("PATCH",PATH,Map.of(),bad,"original-key"));
        }
        var forged=new HashMap<>(command(SHARE,"DELEGATE","ADD_ALLOW"));forged.put("tenant","foreign");
        assertThrows(BffFailure.class,()->DocumentWire.request("PATCH",PATH,Map.of(),forged,"original-key"));
        assertFalse(DocumentWire.response("GET",PATH,acl(SHARE,"OWNER"),"tenant-a",20));
    }
}
