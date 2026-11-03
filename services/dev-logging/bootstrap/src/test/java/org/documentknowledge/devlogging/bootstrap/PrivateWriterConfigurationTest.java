package org.documentknowledge.devlogging.bootstrap;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import org.documentknowledge.devlogging.adapters.outbound.persistence.ConfiguredLogRegistrars;
import org.documentknowledge.devlogging.adapters.outbound.persistence.ConfiguredLogObserver;
import org.documentknowledge.devlogging.domain.LogFailure;
import static org.junit.jupiter.api.Assertions.*;

class PrivateWriterConfigurationTest {
    @TempDir Path directory;
    final Clock clock=Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"),ZoneOffset.UTC);
    final JsonMapper json=JsonMapper.builder().build();
    final UUID readerId=UUID.randomUUID(),registrarId=UUID.randomUUID(),delegatorId=UUID.randomUUID(),observerId=UUID.randomUUID();
    Path file(String name,byte[] bytes) throws Exception {
        var path=directory.resolve(name);Files.write(path,bytes);Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));return path;
    }
    Path file(String name,String value) throws Exception{return file(name,value.getBytes(StandardCharsets.UTF_8));}
    Map<String,Object> grant(UUID id,String hash){return new HashMap<>(Map.of("id",id.toString(),"expiresAt","2026-11-03T10:00:00Z","tokenSha256",hash.repeat(64)));}
    Map<String,Object> registrar(){return grant(registrarId,"b");}
    Map<String,Object> delegator(){var row=grant(delegatorId,"c");row.putAll(Map.of("parents",List.of(UUID.randomUUID().toString()),"bridgeManifests",List.of("e".repeat(64)),
        "permissions",List.of(Map.of("kind","TENANT","issuer","https://issuer.invalid","tenantId","tenant-a"),Map.of("kind","AUTHENTICATED_UNSELECTED","issuer","https://issuer.invalid"),Map.of("kind","UNATTRIBUTED"),Map.of("kind","OPERATOR_ONLY"))));return row;}
    Map<String,Object> registration(){return new HashMap<>(Map.of("schemaVersion",1,"registrars",List.of(registrar()),"delegators",List.of(delegator())));}
    Map<String,Object> observer(){var row=grant(observerId,"d");row.put("schemaVersion",1);return row;}
    PrivateLogConfiguration readers() throws Exception {
        var row=grant(readerId,"a");row.put("operator",true);
        return new PrivateLogConfiguration("jdbc:postgresql://logging-db:5432/dkp_dev_logs",file("reader-password","r".repeat(64)).toString(),
            file("readers",json.writeValueAsBytes(Map.of("schemaVersion",1,"readers",List.of(row)))).toString(),file("cursor","f".repeat(64)).toString(),clock);
    }
    PrivateWriterConfiguration load(byte[] registrations,byte[] observer,String password,String volume) throws Exception {
        return new PrivateWriterConfiguration(file("writer-password",password).toString(),file("registrations",registrations).toString(),
            file("observer",observer).toString(),volume,readers(),clock);
    }
    PrivateWriterConfiguration load(Map<String,Object> registrations,Map<String,Object> observer) throws Exception {
        return load(json.writeValueAsBytes(registrations),json.writeValueAsBytes(observer),"w".repeat(64),"f".repeat(64));
    }
    void safe(org.junit.jupiter.api.function.Executable code){var failure=assertThrows(IllegalStateException.class,code);assertEquals("LOG_CONFIGURATION_UNAVAILABLE",failure.getMessage());assertNull(failure.getCause());}
    @Test void exactTrustedGrantsAndCopiesPreserveExplicitPermissions() throws Exception {
        var config=load(registration(),observer());assertEquals("w".repeat(64),config.password());assertEquals("f".repeat(64),config.volumeIdentity());
        assertEquals(registrarId,config.registrars().getFirst().authority().id());assertEquals(4,config.delegators().getFirst().authority().permissions().size());
        assertEquals(observerId,config.observer().id());assertEquals(4,config.nonproducerHashes().size());var copy=config.nonproducerHashes();copy.getFirst()[0]=0;
        assertNotEquals(0,config.nonproducerHashes().getFirst()[0]);assertThrows(UnsupportedOperationException.class,()->config.registrars().clear());
        var empty=load(new HashMap<>(Map.of("schemaVersion",1,"registrars",List.of(),"delegators",List.of())),observer());assertTrue(empty.registrars().isEmpty());assertTrue(empty.delegators().isEmpty());
    }
    @Test void duplicateCrossRoleIdentifiersAndHashesNeverCreateAuthority() throws Exception {
        for(String field:List.of("id","tokenSha256")) {
            var ob=observer();ob.put(field,field.equals("id")?readerId.toString():"a".repeat(64));safe(()->load(registration(),ob));
            var r=registrar();r.put(field,field.equals("id")?observerId.toString():"d".repeat(64));var root=registration();root.put("registrars",List.of(r));safe(()->load(root,observer()));
            var duplicate=registration();duplicate.put("registrars",List.of(registrar(),registrar()));safe(()->load(duplicate,observer()));
            var d=delegator();d.put(field,field.equals("id")?registrarId.toString():"b".repeat(64));var root2=registration();root2.put("delegators",List.of(d));safe(()->load(root2,observer()));
        }
    }
    @Test void restartRetainsExpiredGrantsButRejectsTheirCredentialsWithoutBlockingLiveGrants() throws Exception {
        String expiredRegistrar="r".repeat(64),liveRegistrar="s".repeat(64),expiredDelegator="t".repeat(64),liveDelegator="u".repeat(64),expiredObserver="v".repeat(64);
        for(String expiry:List.of("2026-11-02T09:59:59Z","2026-11-02T10:00:00Z")) {
            var oldRegistrar=registrar();oldRegistrar.put("expiresAt",expiry);oldRegistrar.put("tokenSha256",hash(expiredRegistrar));
            var newRegistrar=registrar();newRegistrar.put("id",UUID.randomUUID().toString());newRegistrar.put("tokenSha256",hash(liveRegistrar));
            var oldDelegator=delegator();oldDelegator.put("expiresAt",expiry);oldDelegator.put("tokenSha256",hash(expiredDelegator));
            var newDelegator=delegator();newDelegator.put("id",UUID.randomUUID().toString());newDelegator.put("tokenSha256",hash(liveDelegator));
            var root=registration();root.put("registrars",List.of(oldRegistrar,newRegistrar));root.put("delegators",List.of(oldDelegator,newDelegator));
            var ob=observer();ob.put("expiresAt",expiry);ob.put("tokenSha256",hash(expiredObserver));
            var config=load(root,ob);
            assertEquals(6,config.nonproducerHashes().size());
            assertEquals(Instant.parse(expiry),config.registrars().getFirst().authority().expiresAt());
            assertEquals(Instant.parse(expiry),config.delegators().getFirst().authority().expiresAt());
            assertEquals(Instant.parse(expiry),config.observer().expiresAt());
            var authentication=new ConfiguredLogRegistrars(config.registrars(),config.delegators(),clock);
            assertEquals(LogFailure.Kind.EXPIRED,assertThrows(LogFailure.class,()->authentication.registrar(expiredRegistrar)).kind());
            assertEquals(LogFailure.Kind.EXPIRED,assertThrows(LogFailure.class,()->authentication.delegator(expiredDelegator)).kind());
            assertEquals(UUID.fromString((String)newRegistrar.get("id")),authentication.registrar(liveRegistrar).id());
            assertEquals(UUID.fromString((String)newDelegator.get("id")),authentication.delegator(liveDelegator).id());
            var observation=new ConfiguredLogObserver(config.observer(),clock);
            assertEquals(LogFailure.Kind.EXPIRED,assertThrows(LogFailure.class,()->observation.authenticate(expiredObserver)).kind());
            // Expired hashes still reserve their role: a duplicate is not hidden by expiry.
            ob.put("tokenSha256",hash(liveRegistrar));safe(()->load(root,ob));
        }
    }
    static String hash(String token) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
    }
    @Test void malformedOrUnboundedPrivateJsonRejectsBeforeAuthority() throws Exception {
        String good=json.writeValueAsString(registration()),ob=json.writeValueAsString(observer());
        for(String bad:List.of("null","[]","{}",good+"{}",good.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1"),
                good.replace("\"schemaVersion\":1","\"schemaVersion\":1.0"),good.replace("\"schemaVersion\":1","\"schemaVersion\":2"),
                good.replace("\"schemaVersion\":1","\"extra\":true,\"schemaVersion\":1"),good.replace("\"registrars\":[","\"registrars\":{\"x\":["),
                " ".repeat(1048577),"[[[[[[[[[[]]]]]]]]]]"))
            safe(()->load(bad.getBytes(StandardCharsets.UTF_8),ob.getBytes(StandardCharsets.UTF_8),"w".repeat(64),"f".repeat(64)));
        for(var bytes:List.of(good.getBytes(StandardCharsets.UTF_16BE),good.getBytes(StandardCharsets.UTF_16LE),new byte[]{(byte)0xff}))
            safe(()->load(bytes,ob.getBytes(StandardCharsets.UTF_8),"w".repeat(64),"f".repeat(64)));
        var root=registration();root.put("registrars",Collections.nCopies(4097,registrar()));safe(()->load(root,observer()));
        var badRow=registration();badRow.put("registrars",List.of(7));safe(()->load(badRow,observer()));
        for(String key:List.of("parents","bridgeManifests","permissions")) {
            var d=delegator();var rows=(List<?>)d.get(key);d.put(key,List.of(rows.getFirst(),rows.getFirst()));var r=registration();r.put("delegators",List.of(d));safe(()->load(r,observer()));
            var e=delegator();e.put(key,Collections.nCopies(4097,rows.getFirst()));var r2=registration();r2.put("delegators",List.of(e));safe(()->load(r2,observer()));
        }
    }
    @Test void invalidGrantFieldsExpiryPermissionsAndSecretsAreFixedFailures() throws Exception {
        for(Object bad:List.of(7,"INVALID","00000000-0000-0000-0000-000000000000")) {
            var row=registrar();row.put("id",bad);var root=registration();root.put("registrars",List.of(row));safe(()->load(root,observer()));
        }
        for(String date:List.of("2026-11-03T24:00:00Z","2026-11-03T10:60:00Z","2026-11-03T10:00:60Z","2026-11-03T10:00:00+00:00","private")) {
            var row=observer();row.put("expiresAt",date);safe(()->load(registration(),row));
        }
        for(var permission:List.of(Map.of("kind","UNKNOWN"),Map.of("kind","TENANT"),Map.of("kind","UNATTRIBUTED","issuer","https://issuer.invalid"))) {
            var d=delegator();d.put("permissions",List.of(permission));var r=registration();r.put("delegators",List.of(d));safe(()->load(r,observer()));
        }
        var ob=observer();ob.put("tokenSha256","private-not-a-hash");safe(()->load(registration(),ob));
        var ob2=observer();ob2.put("extra",true);safe(()->load(registration(),ob2));
        for(String bad:List.of("short","w".repeat(513),"w".repeat(32)+"\n","w".repeat(31)+"é"))
            safe(()->load(json.writeValueAsBytes(registration()),json.writeValueAsBytes(observer()),bad,"f".repeat(64)));
        safe(()->load(json.writeValueAsBytes(registration()),json.writeValueAsBytes(observer()),"w".repeat(64),"invalid"));
        var d=delegator();d.put("parents",List.of(7));var r=registration();r.put("delegators",List.of(d));safe(()->load(r,observer()));
    }
}
