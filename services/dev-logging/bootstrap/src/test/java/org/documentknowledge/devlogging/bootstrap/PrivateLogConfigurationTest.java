package org.documentknowledge.devlogging.bootstrap;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import org.documentknowledge.devlogging.adapters.outbound.persistence.ConfiguredLogReaders;
import org.documentknowledge.devlogging.domain.LogFailure;

class PrivateLogConfigurationTest {
    @TempDir Path directory;
    final Clock clock = Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneOffset.UTC);
    final String url = "jdbc:postgresql://logging-db:5432/dkp_dev_logs";
    final String id = UUID.randomUUID().toString();
    String row() { return "{\"id\":\""+id+"\",\"operator\":true,\"expiresAt\":\"2026-11-03T10:00:00Z\",\"tokenSha256\":\""+"a".repeat(64)+"\"}"; }
    Path file(String name, String value) throws Exception {
        var path = directory.resolve(name); Files.writeString(path,value);
        Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------")); return path;
    }
    PrivateLogConfiguration config(String jdbc, String json, String password, String key) throws Exception {
        return new PrivateLogConfiguration(jdbc,file("password",password).toString(),
            file("readers",json).toString(),file("key",key).toString(),clock);
    }
    String doc(String rows) { return "{\"schemaVersion\":1,\"readers\":["+rows+"]}"; }
    void safe(org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(IllegalStateException.class,action);
        assertEquals("LOG_CONFIGURATION_UNAVAILABLE",failure.getMessage()); assertNull(failure.getCause());
        assertEquals(0,failure.getSuppressed().length);
    }
    @Test void trustedOperatorAndTenantGrantsLoadWithPrivateCopies() throws Exception {
        var c = config(url,doc(row()),"p".repeat(64),"b".repeat(64)+"\n");
        assertEquals(url,c.jdbcUrl()); assertEquals("p".repeat(64),c.password());
        assertEquals(32,c.cursorKey().length); var key=c.cursorKey();key[0]=0;
        assertNotEquals(0,c.cursorKey()[0]); assertTrue(c.grants().getFirst().reader().operator());
        var tenant=row().replace("true","false").replace("}",",\"issuer\":\"https://issuer.invalid\",\"tenantId\":\"tenant-a\"}");
        assertEquals("tenant-a",config(url,doc(tenant),"p".repeat(32),"b".repeat(64)).grants().getFirst().reader().tenantId());
    }
    @Test void credentialsCannotSelectAnotherDatabaseOrSupplyUrlParameters() throws Exception {
        for(var bad:Arrays.asList(null,"jdbc:postgresql://logging-db:5432/business","jdbc:postgresql://logging-db:5432/dkp_dev_logs?password=private",
                "jdbc:postgresql://user:private@logging-db:5432/dkp_dev_logs","jdbc:postgresql://logging-db:0/dkp_dev_logs",
                "jdbc:postgresql://logging-db:65536/dkp_dev_logs"))
            safe(()->config(bad,doc(row()),"p".repeat(32),"b".repeat(64)));
        for(var bad:List.of("short","p".repeat(513),"p".repeat(32)+"\n","p".repeat(31)+"é"))
            safe(()->config(url,doc(row()),bad,"b".repeat(64)));
        for(var bad:List.of("b".repeat(63),"B".repeat(64),"b".repeat(66),"b".repeat(64)+"x"))
            safe(()->config(url,doc(row()),"p".repeat(32),bad));
    }
    @Test void restartWithExpiredReaderRetainsDenialAndAllowsAnotherLiveReader() throws Exception {
        String expiredToken="e".repeat(64),liveToken="l".repeat(64);
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        String expiredHash=HexFormat.of().formatHex(digest.digest(expiredToken.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        String liveHash=HexFormat.of().formatHex(digest.digest(liveToken.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        for(String expiry:List.of("2026-11-02T09:59:59Z","2026-11-02T10:00:00Z")) {
            String expired=row().replace("2026-11-03T10:00:00Z",expiry).replace("a".repeat(64),expiredHash);
            String live=row().replace(id,UUID.randomUUID().toString()).replace("a".repeat(64),liveHash);
            var config=config(url,doc(expired+","+live),"p".repeat(64),"b".repeat(64));
            assertEquals(2,config.grants().size());assertEquals(Instant.parse(expiry),config.grants().getFirst().reader().expiresAt());
            var authentication=new ConfiguredLogReaders(config.grants(),clock);
            assertEquals(LogFailure.Kind.EXPIRED,assertThrows(LogFailure.class,()->authentication.authenticate(expiredToken)).kind());
            assertTrue(authentication.authenticate(liveToken).operator());
            safe(()->config(url,doc(expired+","+expired),"p".repeat(64),"b".repeat(64)));
        }
    }
    @Test void duplicateKeysTrailingDocumentsAndAmbiguousGrantShapesFailBeforeAuthority() throws Exception {
        for(var bad:List.of(doc(row()).replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1"),
                doc(row().replace("\"operator\":true","\"operator\":true,\"operator\":false")),doc(row())+"{}",
                doc(row()).replace("\"schemaVersion\":1","\"schemaVersion\":1.0"),doc(row()).replace("\"readers\":["+row()+"]","\"readers\":{}"),
                "{}",doc(""),doc("null"),doc(row().replace("true","\"true\"")),
                doc(row().replace("\"id\":","\"unknown\":")),doc(row().replace("true","false")),
                doc(row().replace("}",",\"tenantId\":null}")),doc(row()+","+row()),
                doc(row().replace("2026-11-03T10:00:00Z","2026-11-03T10:00:00+00:00")),
                doc(row().replace(id,"00000000-0000-0000-0000-000000000000")),
                doc(row().replace("a".repeat(64),"private-not-a-hash")),
                doc(row().replace("\"id\":\""+id+"\"","\"id\":7")),
                doc(row()).replace("\"schemaVersion\":1","\"extra\":true,\"schemaVersion\":1")))
            safe(()->config(url,bad,"p".repeat(32),"b".repeat(64)));
    }
    @Test void oversizedAndDeepPrivateJsonIsBounded() throws Exception {
        for(var bad:List.of(" ".repeat(1024*1024+1),"[[[[[[]]]]]]",doc(row().replace(id,"x".repeat(513))),
                doc(String.join(",",Collections.nCopies(4097,row())))))
            safe(()->config(url,bad,"p".repeat(32),"b".repeat(64)));
    }
    @Test void symlinkDirectoriesMissingEmptyAndGroupReadableFilesAreRejectedSafely() throws Exception {
        var password=file("password","p".repeat(32));var grants=file("readers",doc(row()));var key=file("key","b".repeat(64));
        Files.setPosixFilePermissions(password,PosixFilePermissions.fromString("rw-r-----"));
        safe(()->new PrivateLogConfiguration(url,password.toString(),grants.toString(),key.toString(),clock));
        Files.setPosixFilePermissions(password,PosixFilePermissions.fromString("rw-------"));
        var link=directory.resolve("link");Files.createSymbolicLink(link,password);
        for(var bad:List.of(link,directory,directory.resolve("absent"),file("empty","")))
            safe(()->new PrivateLogConfiguration(url,bad.toString(),grants.toString(),key.toString(),clock));
    }
}
