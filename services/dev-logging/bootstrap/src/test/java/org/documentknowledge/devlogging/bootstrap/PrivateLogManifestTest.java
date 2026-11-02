package org.documentknowledge.devlogging.bootstrap;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.documentknowledge.devlogging.domain.*;
import org.documentknowledge.observability.EventCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PrivateLogManifestTest {
    @TempDir Path directory;
    static Map<String,Object> vocabulary() {
        var map=new HashMap<String,Object>();
        map.put("events",Arrays.stream(EventCode.values()).map(EventCode::wireName).toList());
        map.put("phases",List.of("STARTUP"));map.put("errorCodes",List.of("DEPENDENCY_FAILED"));
        map.put("dependencies",List.of(Map.of("name","postgres","version","18.6","vendorBuildProvenance","UNKNOWN",
            "imageDigest","sha256:"+"a".repeat(64))));
        map.put("exceptionClasses",List.of("java.sql.SQLException"));
        map.put("frames",List.of(Map.of("symbol","Main.start","source","services/Main.java")));
        return map;
    }
    static Map<String,Object> manifest(Map<String,Object> vocabulary) {
        var map=new HashMap<String,Object>();map.put("schemaVersion",1);
        map.put("buildId","11111111-1111-4111-8111-111111111111");map.put("component","runtime-tools");
        map.put("gitSha","a".repeat(40));map.put("sourceFingerprint","b".repeat(64));
        map.put("compiledAt","2026-11-02T10:00:00Z");map.put("toolchainFingerprint","c".repeat(64));
        map.put("dependencyFingerprint","d".repeat(64));map.put("vocabularySha256",VerifiedLogManifest.sha256(PrivateLogManifest.canonical(vocabulary)));
        map.put("vocabulary",vocabulary);map.put("sourceFingerprintAlgorithm","dkp-public-inputs-v1");map.put("dirty",true);
        return map;
    }
    void invalid(byte[] bytes,String digest) {
        var failure=assertThrows(LogFailure.class,()->PrivateLogManifest.parse(bytes,digest));
        assertEquals(LogFailure.Kind.INVALID,failure.kind());assertEquals("INVALID",failure.getMessage());assertNull(failure.getCause());
    }
    void invalid(Map<String,Object> value) {
        // Keep invalid numeric schema values canonical without using a production serializer
        // that intentionally accepts only the schema's sole valid number, integer1.
        var encoded=new HashMap<>(value);var replacements=new HashMap<String,String>();
        value.forEach((key,item)->{if(item instanceof Number && !Integer.valueOf(1).equals(item)) {
            String marker="invalid-test-number-"+key;encoded.put(key,marker);replacements.put(marker,item.toString());
        }});
        var text=new String(PrivateLogManifest.canonical(encoded),StandardCharsets.UTF_8);
        for(var replacement:replacements.entrySet())text=text.replace("\""+replacement.getKey()+"\"",replacement.getValue());
        var bytes=text.getBytes(StandardCharsets.UTF_8);invalid(bytes,VerifiedLogManifest.sha256(bytes));
    }
    Path file(byte[] bytes) throws Exception {
        var p=directory.resolve(UUID.randomUUID()+".json");Files.write(p,bytes);
        Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------"));return p;
    }

    @Test void bindsPrivateReviewedBytesAndVocabularyToBuildIdentity() throws Exception {
        var bytes=PrivateLogManifest.canonical(manifest(vocabulary()));String digest=VerifiedLogManifest.sha256(bytes);
        var read=PrivateLogManifest.read(file(bytes),digest);
        assertArrayEquals(bytes,read.bytes());assertEquals(digest,read.digest());assertEquals("runtime-tools",read.build().component());
        assertEquals(UUID.fromString("11111111-1111-4111-8111-111111111111"),read.build().buildId());
        assertEquals(1024L+bytes.length,read.reservedBytes());
    }

    @Test void canonicalStringsFollowRfc8785WithUtf16KeyOrderAndNoNormalization() {
        // Independent known encoding: lowercase escapes, short controls, slash and Unicode untouched.
        var value="€$\u000f\nA'B\"\\/\b\t\f\r😀";
        assertEquals("\"€$\\u000f\\nA'B\\\"\\\\/\\b\\t\\f\\r😀\"",
            new String(PrivateLogManifest.canonical(value),StandardCharsets.UTF_8));
        assertEquals("{\"a\":[true,false,1],\"😀\":\"é\",\"דּ\":\"é\"}",
            new String(PrivateLogManifest.canonical(Map.of("דּ","é","😀","é","a",List.of(true,false,1))),StandardCharsets.UTF_8));
        for(var valueInvalid:new Object[]{"\ud800","\udc00","\ud800a",2,1.0,Map.of(1,"bad")})
            assertThrows(LogFailure.class,()->PrivateLogManifest.canonical(valueInvalid));
        assertThrows(LogFailure.class,()->PrivateLogManifest.canonical(null));
    }

    @Test void rawDigestCanonicalEncodingUtf8DuplicatePropertiesAndDepthAreEnforced() {
        byte[] good=PrivateLogManifest.canonical(manifest(vocabulary()));
        invalid(good,"e".repeat(64));invalid(good,null);invalid(good,"G".repeat(64));
        for(var text:List.of(" "+new String(good,StandardCharsets.UTF_8),new String(good,StandardCharsets.UTF_8)+" {}",
                "{\"schemaVersion\":1,\"schemaVersion\":1}","[".repeat(17)+"1"+"]".repeat(17),"null","[]",
                new String(good,StandardCharsets.UTF_8).replace("runtime-tools","runtime\\u002dtools"),
                new String(good,StandardCharsets.UTF_8).replace("18.6","\\ud800"))) {
            var bytes=text.getBytes(StandardCharsets.UTF_8);invalid(bytes,VerifiedLogManifest.sha256(bytes));
        }
        byte[] malformed={(byte)0xc3,(byte)0x28};invalid(malformed,VerifiedLogManifest.sha256(malformed));
        invalid(new byte[0],"a".repeat(64));invalid(new byte[VerifiedLogManifest.MAX_BYTES+1],"a".repeat(64));invalid(null,"a".repeat(64));
    }

    @Test void completeClosedRootSchemaRejectsUntrustedMetadata() {
        var mutations=List.<Map.Entry<String,Object>>of(Map.entry("schemaVersion",2),Map.entry("schemaVersion","1"),
            Map.entry("dirty","true"),Map.entry("buildId","00000000-0000-0000-0000-000000000000"),
            Map.entry("buildId","x"),Map.entry("component","A Bad"),Map.entry("gitSha","A".repeat(40)),
            Map.entry("sourceFingerprint","bad"),Map.entry("toolchainFingerprint","bad"),Map.entry("dependencyFingerprint",42),
            Map.entry("compiledAt","yesterday"),Map.entry("vocabularySha256","e".repeat(64)),
            Map.entry("sourceFingerprintAlgorithm","guessed"),Map.entry("extra",true));
        for(var mutation:mutations) {var root=manifest(vocabulary());root.put(mutation.getKey(),mutation.getValue());invalid(root);}
        var root=manifest(vocabulary());root.remove("dirty");invalid(root);
    }

    @Test void compiledAtCannotNormalizeEndOfDayOrExpandedYearsOutsideRfc3339() {
        for(String bad:List.of("2026-11-02T24:00:00Z","+12026-11-02T10:00:00Z","2026-02-30T10:00:00Z")) {
            var root=manifest(vocabulary());root.put("compiledAt",bad);invalid(root);
        }
        for(String valid:List.of("2026-11-02T10:00:00+02:00","2026-11-02T10:00:00.123456789Z","2026-11-02t10:00:00z")) {
            var root=manifest(vocabulary());root.put("compiledAt",valid);byte[] bytes=PrivateLogManifest.canonical(root);
            assertNotNull(PrivateLogManifest.parse(bytes,VerifiedLogManifest.sha256(bytes)));
        }
    }

    @Test void catalogsRejectUnknownShapesDuplicatesMissingCodesAndUnsafeFrames() {
        for(String field:List.of("events","phases","errorCodes","exceptionClasses","frames")) {
            var v=vocabulary();v.put(field,List.of(vocabulary().get(field) instanceof List<?> l?l.getFirst():"x",
                ((List<?>)vocabulary().get(field)).getFirst()));invalid(manifest(v));
        }
        for(String field:List.of("events","phases","errorCodes","dependencies","exceptionClasses","frames")) {
            var v=vocabulary();v.put(field,"not-an-array");invalid(manifest(v));
        }
        for(String source:List.of("/private/file","a/../private","../private","a\\b","a/..","")) {
            var v=vocabulary();v.put("frames",List.of(Map.of("symbol","Main.start","source",source)));invalid(manifest(v));
        }
        for(var frame:List.of(Map.of("symbol","bad symbol","source","Main.java"),Map.of("symbol","ok","source","Main.java","extra","x"))) {
            var v=vocabulary();v.put("frames",List.of(frame));invalid(manifest(v));
        }
        for(var dependency:List.of(Map.of("name","db","version","18.6"),Map.of("name","db","version","18.6","vendorBuildProvenance","FORGED"),
            Map.of("name","db","version","","vendorBuildProvenance","UNKNOWN"),Map.of("name","db","version","1","vendorBuildProvenance","UNKNOWN","imageDigest","bad"))) {
            var v=vocabulary();v.put("dependencies",List.of(dependency));invalid(manifest(v));
        }
        var v=vocabulary();v.put("dependencies",List.of(((List<?>)v.get("dependencies")).getFirst(),((List<?>)v.get("dependencies")).getFirst()));invalid(manifest(v));
        v=vocabulary();v.put("events",Arrays.stream(EventCode.values()).map(EventCode::wireName).map(x->x.equals("process.started")?"unknown.code":x).toList());invalid(manifest(v));
        for(var mutation:List.of(Map.entry("phases",List.of("bad value")),Map.entry("errorCodes",List.of("bad")),Map.entry("exceptionClasses",List.of("bad value")))) {
            v=vocabulary();v.put(mutation.getKey(),mutation.getValue());invalid(manifest(v));
        }
        v=vocabulary();v.put("extra",List.of());invalid(manifest(v));
        v=vocabulary();v.put("phases",Collections.nCopies(1025,"phase"));invalid(manifest(v));
        v=vocabulary();v.put("phases",List.of("a".repeat(129)));invalid(manifest(v));
    }

    @Test void optionalDependencyDigestEmptyCatalogsAndUnicodeVersionArePreserved() {
        var v=vocabulary();v.put("dependencies",List.of(Map.of("name","tool","version","😀é","vendorBuildProvenance","VERIFIED")));
        for(var key:List.of("phases","errorCodes","exceptionClasses","frames"))v.put(key,List.of());
        byte[] bytes=PrivateLogManifest.canonical(manifest(v));assertArrayEquals(bytes,PrivateLogManifest.parse(bytes,VerifiedLogManifest.sha256(bytes)).bytes());
    }

    @Test void filesystemRejectsSymlinksPermissionsForeignOwnerEmptyAndOversize() throws Exception {
        byte[] bytes=PrivateLogManifest.canonical(manifest(vocabulary()));String digest=VerifiedLogManifest.sha256(bytes);
        var p=file(bytes);Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-r-----"));
        assertThrows(LogFailure.class,()->PrivateLogManifest.read(p,digest));
        var link=directory.resolve("link");Files.createSymbolicLink(link,file(bytes));assertThrows(LogFailure.class,()->PrivateLogManifest.read(link,digest));
        assertThrows(LogFailure.class,()->PrivateLogManifest.read(file(new byte[0]),digest));
        assertThrows(LogFailure.class,()->PrivateLogManifest.read(file(new byte[VerifiedLogManifest.MAX_BYTES+1]),digest));
        assertThrows(LogFailure.class,()->PrivateLogManifest.read(directory,digest));
        assertThrows(LogFailure.class,()->PrivateLogManifest.read(directory.resolve("missing"),digest));
        assertThrows(LogFailure.class,()->PrivateLogManifest.read(Path.of("/etc/hosts"),digest));
    }
}
