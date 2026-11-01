package org.documentknowledge.documentmanagement.adapters.outbound.verification;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.ScanEvidence;

/** Actual bounded Clamd INSTREAM; only exact clean response plus fresh standard database can attest bytes. */
public final class ClamAvScanAdapter implements MalwareScanPort {
    private final InetSocketAddress endpoint;
    private final StandardDefinitions definitions;
    private final Clock clock;
    public ClamAvScanAdapter(InetSocketAddress endpoint,StandardDefinitions definitions,Clock clock) {this.endpoint=Objects.requireNonNull(endpoint);this.definitions=Objects.requireNonNull(definitions);this.clock=Objects.requireNonNull(clock);}
    /** Independently validates definitions around a pinned VERSION probe; never scans or issues evidence.
     * The network exchange has a two-second total deadline; local bounded definition hashing is additional.
     */
    public boolean ready() {
        try {
            var before=definitions.snapshot();
            version(before.version(),Duration.ofSeconds(2));
            return before.equals(definitions.snapshot());
        }catch(Exception unavailable){return false;}
    }
    @Override public ScanEvidence scan(ArtifactSource source) {
        try {
            var before=definitions.snapshot();version(before.version());
            if(Files.isSymbolicLink(source.path()) || !Files.isRegularFile(source.path(),LinkOption.NOFOLLOW_LINKS) || Files.size(source.path())!=source.sizeBytes())throw unavailable();
            var sha=StandardDefinitions.sha();long count=0;String response;
            try(var socket=new ClamdConnection(endpoint,Duration.ofSeconds(120));var input=Files.newInputStream(source.path(),LinkOption.NOFOLLOW_LINKS)) {
                socket.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));byte[] bytes=new byte[65536];int n;
                while((n=input.read(bytes))!=-1) {count+=n;if(count>100_000_000 || count>source.sizeBytes())throw unavailable();sha.update(bytes,0,n);socket.write(ByteBuffer.allocate(4).putInt(n).array());socket.write(Arrays.copyOf(bytes,n));}
                socket.write(new byte[4]);response=socket.response();
            }
            String hash=StandardDefinitions.hex(sha.digest());if(count!=source.sizeBytes() || !hash.equals(source.sha256()))throw unavailable();
            if(response.startsWith("stream: ") && response.endsWith(" FOUND") && response.length()>14)throw new DmFailure(422,"MALWARE_DETECTED");
            if(!response.equals("stream: OK"))throw unavailable();
            version(before.version());var after=definitions.snapshot();if(!before.equals(after))throw unavailable();
            return new ScanEvidence(hash,count,"1.5.4",before.version(),before.updatedAt(),before.digest(),clock.instant());
        }catch(DmFailure failure){throw failure;}catch(Exception invalid){throw unavailable();}
    }
    private void version(String expected) throws IOException {version(expected,Duration.ofSeconds(5));}
    private void version(String expected,Duration timeout) throws IOException {
        try(var socket=new ClamdConnection(endpoint,timeout)) {
            socket.write("zVERSION\0".getBytes(StandardCharsets.US_ASCII));String reply=socket.response();
            if(!reply.matches("ClamAV 1\\.5\\.4/"+expected+"/[^\\r\\n]{1,128}"))throw new IOException("Scanner version mismatch");
        }
    }
    private static DmFailure unavailable() {return new DmFailure(503,"UNAVAILABLE");}
}
