package org.documentknowledge.documentmanagement.adapters.outbound.verification;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Reader/protocol negatives; genuine signed databases are exercised separately in real runtime acceptance. */
class StandardDefinitionsTest {
    @TempDir Path temp;
    @BeforeEach void setup() throws Exception {VerificationFixtures.definitions(temp);}
    private StandardDefinitions definitions(){return new StandardDefinitions(temp,VerificationFixtures.CLOCK);}
    @Test void independentlyHashesThreeFilesAndRejectsChangedOrAmbiguousBytes() throws Exception {
        var state=definitions().snapshot();assertEquals("27930",state.version());assertEquals(VerificationFixtures.CLOCK.instant(),state.updatedAt());assertEquals(64,state.digest().length());
        Files.writeString(temp.resolve("main.cvd"),"changed actual bytes");assertThrows(java.io.IOException.class,()->definitions().snapshot());
        VerificationFixtures.definitions(temp);Files.copy(temp.resolve("main.cvd"),temp.resolve("main.cld"));assertThrows(java.io.IOException.class,()->definitions().snapshot());
        Files.delete(temp.resolve("main.cld"));Files.delete(temp.resolve("main.cvd"));assertThrows(java.io.IOException.class,()->definitions().snapshot());
    }
    @Test void staleFutureMissingUnknownDuplicateOrOversizeManifestCannotAttest() throws Exception {
        for(Instant time:List.of(VerificationFixtures.CLOCK.instant().minusSeconds(86401),VerificationFixtures.CLOCK.instant().plusSeconds(1))) {VerificationFixtures.manifest(temp,time);assertThrows(java.io.IOException.class,()->definitions().snapshot());}
        for(String json:List.of("{}","[]","null","{\"schemaVersion\":1,\"schemaVersion\":1}","x".repeat(4097))) {Files.writeString(temp.resolve("dkp-update.json"),json);assertThrows(Exception.class,()->definitions().snapshot());}
        Files.delete(temp.resolve("dkp-update.json"));assertThrows(java.io.IOException.class,()->definitions().snapshot());
    }
    @Test void digestVersionHeaderTimestampAndPermissionsAreTrustRequirements() throws Exception {
        Path manifest=temp.resolve("dkp-update.json");String original=Files.readString(manifest);
        for(String invalid:List.of(original.replace("27930","27931"),original.replace("2026-10-31T12:00:00Z","not-time"),original.replace("\"schemaVersion\":1","\"schemaVersion\":2"),original.replace("\"databaseVersion\":\"27930\"","\"databaseVersion\":false"))) {Files.writeString(manifest,invalid);assertThrows(Exception.class,()->definitions().snapshot());}
        Files.writeString(manifest,original);Files.setPosixFilePermissions(manifest,PosixFilePermissions.fromString("rw-rw----"));assertThrows(java.io.IOException.class,()->definitions().snapshot());Files.setPosixFilePermissions(manifest,PosixFilePermissions.fromString("rw-------"));
        Path actual=temp.resolve("actual");Files.move(temp.resolve("daily.cvd"),actual);Files.createSymbolicLink(temp.resolve("daily.cvd"),actual);assertThrows(java.io.IOException.class,()->definitions().snapshot());Files.delete(temp.resolve("daily.cvd"));Files.move(actual,temp.resolve("daily.cvd"));
        Files.writeString(temp.resolve("daily.cvd"),"ClamAV-VDB:build:27930:1:90:md5:signature:builder:9999999999");assertThrows(java.io.IOException.class,()->definitions().snapshot());
    }
}
