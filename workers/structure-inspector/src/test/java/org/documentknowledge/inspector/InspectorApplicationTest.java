package org.documentknowledge.inspector;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class InspectorApplicationTest {
    private Path directory;
    @BeforeEach void setup() throws Exception {Path root=Path.of("build/secret-test-fixtures").toAbsolutePath().normalize();Files.createDirectories(root);Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));directory=Files.createTempDirectory(root,"worker-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath();}
    @AfterEach void cleanup() throws Exception {try(var paths=Files.walk(directory)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    private void privateFile(Path path,String content) throws Exception {Files.writeString(path,content);Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));}
    @Test void actualPrivatePkcs12ServiceKeyAndTemporaryDirectoryBootHttps() throws Exception {
        var tls=new TlsFixture(directory);Path password=directory.resolve("password"),key=directory.resolve("key"),temp=Files.createDirectory(directory.resolve("tmp"));Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rwx------"));Files.setPosixFilePermissions(directory.resolve("server.p12"),PosixFilePermissions.fromString("rw-------"));privateFile(password,"synthetic-test-only\n");privateFile(key,Base64.getEncoder().encodeToString(new byte[32])+"\n");
        var config=new HashMap<String,String>();config.put("DKP_INSPECTOR_KEYSTORE_PATH",directory.resolve("server.p12").toString());config.put("DKP_INSPECTOR_KEYSTORE_PASSWORD_PATH",password.toString());config.put("DKP_INSPECTOR_SERVICE_KEY_PATH",key.toString());config.put("DKP_INSPECTOR_TEMP_DIR",temp.toString());
        Path stale=Files.createDirectory(temp.resolve("inspection-stale"));Files.writeString(stale.resolve("source"),"discarded quarantine bytes");
        String original=System.getProperty("java.io.tmpdir");
        try(var server=InspectorApplication.configure(config,new InetSocketAddress("127.0.0.1",0));var client=HttpClient.newBuilder().sslContext(tls.client).build()) {server.start();assertFalse(Files.exists(stale));assertEquals(200,client.send(HttpRequest.newBuilder(URI.create("https://localhost:"+server.port()+"/health/readiness")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());}
        finally{System.setProperty("java.io.tmpdir",original);}
        Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rwxr-x---"));assertThrows(java.io.IOException.class,()->InspectorApplication.configure(config,new InetSocketAddress("127.0.0.1",0)));Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rwx------"));
        privateFile(key,Base64.getEncoder().encodeToString(new byte[31]));assertThrows(IllegalArgumentException.class,()->InspectorApplication.configure(config,new InetSocketAddress("127.0.0.1",0)));privateFile(key,Base64.getEncoder().encodeToString(new byte[32]));
        Files.writeString(temp.resolve("unexpected"),"unowned name");assertThrows(java.io.IOException.class,()->InspectorApplication.configure(config,new InetSocketAddress("127.0.0.1",0)));Files.delete(temp.resolve("unexpected"));
        config.remove("DKP_INSPECTOR_KEYSTORE_PATH");assertThrows(IllegalArgumentException.class,()->InspectorApplication.configure(config,new InetSocketAddress("127.0.0.1",0)));
    }
    @Test void secretFilesRejectSymlinksWritableParentsPublicModesAndBounds() throws Exception {
        Path file=directory.resolve("secret");privateFile(file,"bounded");assertArrayEquals("bounded".getBytes(),PrivateFiles.read(file,7));assertThrows(java.io.IOException.class,()->PrivateFiles.read(file,6));
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r--r--"));assertThrows(java.io.IOException.class,()->PrivateFiles.read(file,7));Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
        Path link=directory.resolve("link");Files.createSymbolicLink(link,file);assertThrows(java.io.IOException.class,()->PrivateFiles.read(link,7));assertThrows(java.io.IOException.class,()->PrivateFiles.read(directory,7));
        Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwxrwx---"));assertThrows(java.io.IOException.class,()->PrivateFiles.read(file,7));Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));assertThrows(java.io.IOException.class,()->PrivateFiles.read(directory.resolve("a/../secret"),7));
    }
}
