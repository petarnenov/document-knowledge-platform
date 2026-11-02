package org.documentknowledge.devlogging.adapters.inbound;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.documentknowledge.devlogging.domain.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogRegistrationJsonTest {
    static final String BINDING="{\"buildId\":\""+UUID.randomUUID()+"\",\"runId\":\""+UUID.randomUUID()+"\",\"component\":\"browser-bff\",\"gitSha\":\""+"a".repeat(40)+"\",\"sourceFingerprint\":\""+"b".repeat(64)+"\",\"manifestSha256\":\""+"c".repeat(64)+"\",\"vocabularySha256\":\""+"d".repeat(64)+"\"}";
    static final String TENANT="{\"scopeKind\":\"TENANT\",\"issuer\":\"https://issuer.invalid\",\"subject\":\"actor\",\"tenantId\":\"tenant-a\"}";
    static String registration(String scope) { return "{\"binding\":"+BINDING+",\"scope\":"+scope+",\"acceptUntil\":\"2026-11-03T10:00:00Z\"}"; }
    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    @Test void closedScopeVariantsAndDelegationPreserveExactTypedIdentity() {
        for(String scope:List.of(TENANT,"{\"scopeKind\":\"AUTHENTICATED_UNSELECTED\",\"issuer\":\"https://issuer.invalid\",\"subject\":\"actor\"}","{\"scopeKind\":\"UNATTRIBUTED\"}","{\"scopeKind\":\"OPERATOR_ONLY\"}"))
            assertNotNull(LogRegistrationJson.registration(bytes(registration(scope))).scope());
        String parent=UUID.randomUUID().toString(),epoch=UUID.randomUUID().toString();
        var result=LogRegistrationJson.delegation(bytes("{\"parentProducerId\":\""+parent+"\",\"binding\":"+BINDING+",\"scope\":"+TENANT+",\"contextEpoch\":\""+epoch+"\",\"captureUntil\":\"2026-11-03T10:00:00.123456Z\"}"));
        assertEquals(UUID.fromString(parent),result.parent());assertEquals(UUID.fromString(epoch),result.epoch());
        assertEquals("tenant-a",result.scope().tenantId());assertEquals("browser-bff",result.artifact().binding().build().component());
    }
    @Test void rejectsUntrustedJsonSpellingsAndClosedSchemaViolationsWithoutRawCauses() {
        String valid=registration(TENANT);
        for(String invalid:List.of("", "null","[]",valid+" {}",valid.replace("\"binding\":","\"binding\":null,\"binding\":"),
                valid.replace("\"acceptUntil\":","\"unknown\":true,\"acceptUntil\":"),valid.replace("2026-11-03T10:00:00Z","2026-11-03T24:00:00Z"),
                valid.replace("2026-11-03T10:00:00Z","2026-02-30T10:00:00Z"),valid.replace("2026-11-03T10:00:00Z","+2026-11-03T10:00:00Z"),
                valid.replace("https://issuer.invalid","relative"),valid.replace("tenant-a",""),valid.replace("TENANT","UNKNOWN"),valid.replace(TENANT,"null"),
                valid.replace("\"binding\":"+BINDING,"\"binding\":[]"),valid.replace("\"component\":\"browser-bff\"","\"component\":42"),
                valid.replace("actor","a".repeat(513)),valid.replace(TENANT,"{\"scopeKind\":\"OPERATOR_ONLY\",\"tenantId\":\"forged\"}"))) {
            var failure=assertThrows(LogFailure.class,()->LogRegistrationJson.registration(bytes(invalid)));
            assertEquals(LogFailure.Kind.INVALID,failure.kind());assertNull(failure.getCause());assertFalse(failure.getMessage().contains("forged"));
        }
        for(byte[] invalid:new byte[][]{null,new byte[0],new byte[32769],new byte[]{(byte)0xc3,0x28}})
            assertThrows(LogFailure.class,()->LogRegistrationJson.registration(invalid));
        for(String invalid:new String[]{null,"1-1-1-1-1",new UUID(0,0).toString(),"AAAAAAAA-AAAA-4AAA-AAAA-AAAAAAAAAAAA"})
            assertThrows(LogFailure.class,()->LogRegistrationJson.id(invalid));
        assertThrows(LogFailure.class,()->LogRegistrationJson.delegation(bytes(valid)));
        String deep="[".repeat(17)+"0"+"]".repeat(17);
        assertThrows(LogFailure.class,()->LogRegistrationJson.registration(bytes(deep)));
        assertThrows(LogFailure.class,()->LogRegistrationJson.registration(bytes(valid.replace("\"subject\":\"actor\"","\"subject\":null"))));
        assertThrows(LogFailure.class,()->LogRegistrationJson.registration(bytes(valid.replace("\"gitSha\":","\"extra\":\"secret\",\"gitSha\":"))));
    }
    @Test void rejectsAutodetectedUtf16WhilePreservingActualUtf8MultibyteValues() {
        String body=registration(TENANT);
        for(var encoding:List.of(StandardCharsets.UTF_16BE,StandardCharsets.UTF_16LE))
            assertThrows(LogFailure.class,()->LogRegistrationJson.registration(body.getBytes(encoding)),encoding.name());
        var valid=LogRegistrationJson.registration(bytes(registration(TENANT.replace("actor","потребител"))));
        assertEquals("потребител",valid.scope().subject());
    }

}
