package org.documentknowledge.browserbff.bootstrap;

import java.util.Map;
import java.util.List;
import org.documentknowledge.browserbff.application.KnowledgeWire;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeWireFactoryTest {
    @Test void exactJdkAbsoluteSyntaxRetainsOpaqueUnicodeAndHierarchicalIssuers(){
        var wire=new KnowledgeWireFactory().wire();
        for(String issuer:List.of("https://issuer.example/realms/test","urn:synthetic:issuer","https://例.example/path","scheme:value","https://[::1]:443/path","https://issuer.example/%20?q=a#b")){
            assertTrue(wire.validate("Actor",Map.of("issuer",issuer,"subject","staff")),issuer);
            assertTrue(wire.validate("AudienceSelector",Map.of("kind","FIRM","issuer",issuer,"firmId","public-firm")),issuer);
            assertTrue(wire.validate("AudienceSelector",Map.of("kind","RESOURCE_SCOPE","issuer",issuer,"resourceScopeId","public-scope")),issuer);
        }
    }
    @Test void relativeMalformedAndOverlongIssuersNeverCrossBoundary(){
        var wire=new KnowledgeWireFactory().wire();
        for(String issuer:List.of("relative","/relative","//issuer.example/path","https://issuer.example/%ZZ","https://[broken","https://issuer.example/space here",":bad","urn:","https://issuer.example/"+"x".repeat(2048))){
            assertFalse(wire.validate("Actor",Map.of("issuer",issuer,"subject","staff")),issuer);
            assertFalse(wire.validate("AudienceSelector",Map.of("kind","FIRM","issuer",issuer,"firmId","public-firm")),issuer);
            assertFalse(wire.validate("AudienceSelector",Map.of("kind","RESOURCE_SCOPE","issuer",issuer,"resourceScopeId","public-scope")),issuer);
        }
        assertThrows(NullPointerException.class,()->new KnowledgeWire(null));
    }
}
