package org.documentknowledge.inspector;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.documentknowledge.inspector.diagnostics.InspectorDiagnostics;
import org.documentknowledge.inspector.diagnostics.InspectorDiagnostics.*;

class InspectorApplicationTest {
    private Path directory;
    @BeforeEach void setup() throws Exception {Path root=Path.of("build/secret-test-fixtures").toAbsolutePath().normalize();Files.createDirectories(root);Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));directory=Files.createTempDirectory(root,"worker-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath();}
    @AfterEach void cleanup() throws Exception {try(var paths=Files.walk(directory)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    private void privateFile(Path path,String content) throws Exception {Files.writeString(path,content);Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));}
    @Test void actualPrivatePkcs12ServiceKeyAndTemporaryDirectoryBootHttps() throws Exception {
        var tls=new TlsFixture(directory);Path password=directory.resolve("password"),key=directory.resolve("key"),temp=Files.createDirectory(directory.resolve("tmp"));Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rwx------"));Files.setPosixFilePermissions(directory.resolve("server.p12"),PosixFilePermissions.fromString("rw-------"));privateFile(password,"synthetic-test-only\n");privateFile(key,Base64.getEncoder().encodeToString(new byte[32])+"\n");
        var config=new HashMap<String,String>();config.put("DKP_INSPECTOR_KEYSTORE_PATH",directory.resolve("server.p12").toString());config.put("DKP_INSPECTOR_KEYSTORE_PASSWORD_PATH",password.toString());config.put("DKP_INSPECTOR_SERVICE_KEY_PATH",key.toString());config.put("DKP_INSPECTOR_TEMP_DIR",temp.toString());
        Path stale=Files.createDirectory(temp.resolve("inspection-stale"));Files.writeString(stale.resolve("source"),"discarded quarantine bytes");
        String original=System.getProperty("java.io.tmpdir");var diagnostics=new StructureDiagnosticsTest.Recording();
        try(var server=InspectorApplication.start(config,new InetSocketAddress("127.0.0.1",0),diagnostics);var client=HttpClient.newBuilder().sslContext(tls.client).build()) {assertFalse(Files.exists(stale));assertEquals(200,client.send(HttpRequest.newBuilder(URI.create("https://localhost:"+server.port()+"/health/readiness")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());InspectorApplication.stop(server,diagnostics,null);assertEquals(Outcome.SUCCEEDED,diagnostics.one(Phase.STARTUP).outcome);assertEquals(Outcome.SUCCEEDED,diagnostics.one(Phase.SHUTDOWN).outcome);assertNull(diagnostics.one(Phase.STARTUP).context.traceId());}
        finally{System.setProperty("java.io.tmpdir",original);}
        try {
        byte[] manifest=unitManifest();var logged=new HashMap<>(config);
        logged.put("DKP_INSPECTOR_LOG_MANIFEST_SHA256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(manifest)));
        logged.put("DKP_INSPECTOR_LOG_RUN_ID",UUID.randomUUID().toString());logged.put("DKP_INSPECTOR_LOG_INSTANCE_ID",UUID.randomUUID().toString());
        var stream=new java.io.ByteArrayOutputStream();var errors=new java.io.ByteArrayOutputStream();var hook=new java.util.concurrent.atomic.AtomicReference<Runnable>();
        try(var running=InspectorApplication.launch(logged,new InetSocketAddress("127.0.0.1",0),new java.io.ByteArrayInputStream(manifest),stream,new java.io.PrintStream(errors),hook::set);var client=HttpClient.newBuilder().sslContext(tls.client).build()) {
            assertEquals(200,client.send(HttpRequest.newBuilder(URI.create("https://localhost:"+running.port()+"/health/readiness")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);while(stream.size()==0 && System.nanoTime()<end)Thread.sleep(2);
            assertTrue(stream.toString().startsWith("DKP-INSPECTOR-LOG/1 "));assertEquals(0,errors.size());assertNotNull(hook.get());hook.get().run();
        } finally {System.setProperty("java.io.tmpdir",original);}
        assertThrows(IllegalStateException.class,()->InspectorApplication.launch(logged,new InetSocketAddress("127.0.0.1",0),new java.io.ByteArrayInputStream(manifest),java.io.OutputStream.nullOutputStream(),new java.io.PrintStream(errors),ignored->{throw new IllegalStateException("PRIVATE_HOOK");}));
        var bad=new HashMap<>(logged);bad.remove("DKP_INSPECTOR_KEYSTORE_PATH");assertThrows(IllegalArgumentException.class,()->InspectorApplication.launch(bad,new InetSocketAddress("127.0.0.1",0),new java.io.ByteArrayInputStream(manifest),java.io.OutputStream.nullOutputStream(),new java.io.PrintStream(errors),ignored->fail("cannot install hook for failed startup")));
        assertThrows(IllegalArgumentException.class,()->InspectorApplication.launch(Map.of(),new InetSocketAddress("127.0.0.1",0),null,java.io.OutputStream.nullOutputStream(),new java.io.PrintStream(errors),ignored->fail("cannot install hook for failed startup")));
        } finally {System.setProperty("java.io.tmpdir",original);}
        Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rwxr-x---"));assertThrows(java.io.IOException.class,()->InspectorApplication.configure(config,new InetSocketAddress("127.0.0.1",0)));Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rwx------"));
        privateFile(key,Base64.getEncoder().encodeToString(new byte[31]));assertThrows(IllegalArgumentException.class,()->InspectorApplication.configure(config,new InetSocketAddress("127.0.0.1",0)));privateFile(key,Base64.getEncoder().encodeToString(new byte[32]));
        Files.writeString(temp.resolve("unexpected"),"unowned name");assertThrows(java.io.IOException.class,()->InspectorApplication.configure(config,new InetSocketAddress("127.0.0.1",0)));Files.delete(temp.resolve("unexpected"));
        config.remove("DKP_INSPECTOR_KEYSTORE_PATH");assertThrows(IllegalArgumentException.class,()->InspectorApplication.configure(config,new InetSocketAddress("127.0.0.1",0)));
    }
    /** Unit metadata only; actual original compiler/image adoption is a separate acceptance. */
    private static byte[] unitManifest() throws Exception {
        var manifest=new HashMap<String,Object>();manifest.put("schemaVersion",1);manifest.put("buildId",UUID.randomUUID().toString());manifest.put("component","structure-inspector");manifest.put("gitSha","a".repeat(40));manifest.put("sourceFingerprintAlgorithm","dkp-public-inputs-v1");manifest.put("dirty",true);manifest.put("compiledAt","2026-11-03T21:00:00Z");
        for(String key:List.of("sourceFingerprint","toolchainFingerprint","dependencyFingerprint","vocabularySha256"))manifest.put(key,"b".repeat(64));
        manifest.put("vocabulary",Map.of("events",List.of("process.started","process.stopped","operation.started","operation.succeeded","operation.failed","operation.cancelled"),"phases",Arrays.stream(Phase.values()).map(Enum::name).toList(),"errorCodes",Arrays.stream(InspectorDiagnostics.Error.values()).map(Enum::name).toList(),"exceptionClasses",List.of("java.io.IOException"),"frames",List.of(),"dependencies",List.of()));
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(manifest);
    }
    @Test void missingLoggingConfigurationIsVisibleWithoutLeakingOrPreventingParserConfiguration() throws Exception {
        var errors=new java.io.ByteArrayOutputStream();
        assertNull(InspectorApplication.openLogging(Map.of("DKP_INSPECTOR_LOG_RUN_ID","PRIVATE"),null,java.io.OutputStream.nullOutputStream(),new java.io.PrintStream(errors)));
        assertEquals("inspector diagnostics unavailable"+System.lineSeparator(),errors.toString());
        var diagnostics=new StructureDiagnosticsTest.Recording();
        var failure=assertThrows(IllegalArgumentException.class,()->InspectorApplication.start(Map.of(),new InetSocketAddress("127.0.0.1",0),diagnostics));
        assertSame(failure,diagnostics.one(Phase.STARTUP).failure);assertEquals(Outcome.FAILED,diagnostics.one(Phase.STARTUP).outcome);assertNull(diagnostics.one(Phase.STARTUP).facts.status());
        assertThrows(IllegalArgumentException.class,()->InspectorApplication.start(Map.of(),new InetSocketAddress("127.0.0.1",0),(context,phase)->{throw new IllegalStateException("PRIVATE_CALLBACK");}));
    }
    @Test void secretFilesRejectSymlinksWritableParentsPublicModesAndBounds() throws Exception {
        Path file=directory.resolve("secret");privateFile(file,"bounded");assertArrayEquals("bounded".getBytes(),PrivateFiles.read(file,7));assertThrows(java.io.IOException.class,()->PrivateFiles.read(file,6));
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r--r--"));assertThrows(java.io.IOException.class,()->PrivateFiles.read(file,7));Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
        Path link=directory.resolve("link");Files.createSymbolicLink(link,file);assertThrows(java.io.IOException.class,()->PrivateFiles.read(link,7));assertThrows(java.io.IOException.class,()->PrivateFiles.read(directory,7));
        Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwxrwx---"));assertThrows(java.io.IOException.class,()->PrivateFiles.read(file,7));Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));assertThrows(java.io.IOException.class,()->PrivateFiles.read(directory.resolve("a/../secret"),7));
    }
}
