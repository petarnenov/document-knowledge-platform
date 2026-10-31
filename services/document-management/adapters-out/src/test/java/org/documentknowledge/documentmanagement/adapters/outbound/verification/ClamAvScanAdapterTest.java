package org.documentknowledge.documentmanagement.adapters.outbound.verification;

import java.io.*;
import java.nio.file.*;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.application.upload.ArtifactSource;
import org.documentknowledge.documentmanagement.domain.upload.UploadFormat;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual TCP framing/protocol units; fake replies are NOT malware/scanner acceptance evidence. */
class ClamAvScanAdapterTest {
    @TempDir Path temp;
    private ArtifactSource source;
    @BeforeEach void setup() throws Exception {Path defs=Files.createDirectory(temp.resolve("defs"));VerificationFixtures.definitions(defs);byte[] bytes="untrusted source bytes".getBytes();Path path=temp.resolve("source");Files.write(path,bytes);source=new ArtifactSource(path,UploadFormat.PDF,bytes.length,StandardDefinitions.hex(StandardDefinitions.sha().digest(bytes)));}
    private ClamAvScanAdapter adapter(VerificationFixtures.FakeClamd server){return new ClamAvScanAdapter(server.endpoint(),new StandardDefinitions(temp.resolve("defs"),VerificationFixtures.CLOCK),VerificationFixtures.CLOCK);}
    @Test void actualLengthFramingAndHashBindProtocolCleanEvidence() throws Exception {
        try(var server=new VerificationFixtures.FakeClamd()) {var evidence=adapter(server).scan(source);assertArrayEquals(Files.readAllBytes(source.path()),server.streamed.get());assertEquals(source.sha256(),evidence.sourceSha256());assertEquals(source.sizeBytes(),evidence.sizeBytes());assertEquals("1.5.4",evidence.engineVersion());assertEquals("27930",evidence.databaseVersion());assertEquals(VerificationFixtures.CLOCK.instant(),evidence.databaseUpdatedAt());assertEquals(VerificationFixtures.CLOCK.instant(),evidence.scannedAt());}
    }
    @Test void infectedLimitErrorPartialAndUnsupportedEngineAreNeverClean() throws Exception {
        for(String reply:new String[]{"stream: Win.Test.EICAR FOUND","stream: Heuristics.Limits.Exceeded FOUND","stream: size limit exceeded. ERROR","stream: O","OK","stream: OK extra","stream: OK\0stream: unexpected FOUND","stream: OK\n","x".repeat(1025)})try(var server=new VerificationFixtures.FakeClamd()){server.scan=reply;assertThrows(DmFailure.class,()->adapter(server).scan(source));}
        try(var server=new VerificationFixtures.FakeClamd()){server.partial=true;assertThrows(DmFailure.class,()->adapter(server).scan(source));}
        try(var server=new VerificationFixtures.FakeClamd()){server.version="ClamAV 1.5.3/27930/test";assertThrows(DmFailure.class,()->adapter(server).scan(source));}
    }
    @Test void staleDefinitionsChangedSourceAndGuessedPathFailClosed() throws Exception {
        try(var server=new VerificationFixtures.FakeClamd()) {
            var wrongHash=new ArtifactSource(source.path(),source.format(),source.sizeBytes(),"0".repeat(64));assertThrows(DmFailure.class,()->adapter(server).scan(wrongHash));
            var wrongSize=new ArtifactSource(source.path(),source.format(),source.sizeBytes()+1,source.sha256());assertThrows(DmFailure.class,()->adapter(server).scan(wrongSize));
            Path link=temp.resolve("link");Files.createSymbolicLink(link,source.path());assertThrows(DmFailure.class,()->adapter(server).scan(new ArtifactSource(link,source.format(),source.sizeBytes(),source.sha256())));
            VerificationFixtures.manifest(temp.resolve("defs"),VerificationFixtures.CLOCK.instant().minusSeconds(86401));assertThrows(DmFailure.class,()->adapter(server).scan(source));
        }
    }
    @Test void readinessRequiresFreshDefinitionsAndPinnedLoadedVersionWithoutScanning() throws Exception {
        try(var server=new VerificationFixtures.FakeClamd()) {
            assertTrue(adapter(server).ready());assertNull(server.streamed.get());
            server.version="ClamAV 1.5.3/27930/test";assertFalse(adapter(server).ready());
            server.version="ClamAV 1.5.4/27931/test";assertFalse(adapter(server).ready());
            server.version="ClamAV 1.5.4/27930/test";
            VerificationFixtures.manifest(temp.resolve("defs"),VerificationFixtures.CLOCK.instant().minusSeconds(86401));
            assertFalse(adapter(server).ready());
            Files.delete(temp.resolve("defs/dkp-update.json"));assertFalse(adapter(server).ready());
        }
    }
    @Test void readinessRejectsDefinitionsChangedDuringVersionProbe() throws Exception {
        try(var listener=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())) {
            var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var reply=executor.submit(()->{try(var socket=listener.accept()) {
                    while(socket.getInputStream().read()>0) { }
                    VerificationFixtures.manifest(temp.resolve("defs"),VerificationFixtures.CLOCK.instant().minusSeconds(1));
                    socket.getOutputStream().write("ClamAV 1.5.4/27930/test\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                }return null;});
                var scanner=new ClamAvScanAdapter(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(),listener.getLocalPort()),new StandardDefinitions(temp.resolve("defs"),VerificationFixtures.CLOCK),VerificationFixtures.CLOCK);
                assertFalse(scanner.ready());reply.get(3,java.util.concurrent.TimeUnit.SECONDS);
            }finally{executor.shutdownNow();}
        }
    }
    @Test void readinessNetworkDeadlineRejectsUnresponsiveScanner() throws Exception {
        try(var listener=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())) {
            var scanner=new ClamAvScanAdapter(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(),listener.getLocalPort()),new StandardDefinitions(temp.resolve("defs"),VerificationFixtures.CLOCK),VerificationFixtures.CLOCK);
            long start=System.nanoTime();assertFalse(scanner.ready());
            long elapsed=java.time.Duration.ofNanos(System.nanoTime()-start).toMillis();
            assertTrue(elapsed>=1500 && elapsed<3000,"Readiness network deadline must remain two seconds");
        }
    }
    @Test void nonblockingProtocolDeadlineAlsoBoundsUnresponsiveConnectReadAndPartialFrame() throws Exception {
        try(var listener=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress());var connection=new ClamdConnection(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(),listener.getLocalPort()),java.time.Duration.ofMillis(100));var accepted=listener.accept()) {
            long start=System.nanoTime();assertThrows(java.net.SocketTimeoutException.class,connection::response);assertTrue(java.time.Duration.ofNanos(System.nanoTime()-start).toMillis()<1000);
        }
    }
}
