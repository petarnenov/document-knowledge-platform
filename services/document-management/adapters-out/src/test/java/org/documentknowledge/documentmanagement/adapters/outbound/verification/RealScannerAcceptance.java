package org.documentknowledge.documentmanagement.adapters.outbound.verification;

import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.*;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.application.upload.ArtifactSource;
import org.documentknowledge.documentmanagement.domain.upload.UploadFormat;

/** Explicit real-runtime gate; never conditionally skipped or substituted into production. */
public final class RealScannerAcceptance {
    private RealScannerAcceptance() { }
    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("Expected private scanner host, port and read-only standard definitions directory");
        var clock=Clock.systemUTC();var definitions=new StandardDefinitions(Path.of(args[2]),clock);
        var scanner=new ClamAvScanAdapter(new InetSocketAddress(args[0],Integer.parseInt(args[1])),definitions,clock);
        Path directory=Files.createTempDirectory("scanner-acceptance-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        try {
            byte[] benign="synthetic benign scanner acceptance sample".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            var source=source(directory.resolve("benign"),benign);var evidence=scanner.scan(source);
            if(!evidence.sourceSha256().equals(source.sha256()) || evidence.sizeBytes()!=source.sizeBytes() || !evidence.engineVersion().equals("1.5.4") || !evidence.databaseDigest().equals(definitions.snapshot().digest()))throw new AssertionError("Standard scanner evidence mismatch");
            byte[] eicar="X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            try{scanner.scan(source(directory.resolve("eicar"),eicar));throw new AssertionError("Real scanner failed to reject EICAR");}
            catch(DmFailure detected){if(detected.status()!=422 || !detected.code().equals("MALWARE_DETECTED"))throw new AssertionError("Real EICAR was not positively detected",detected);}
            System.out.println("PASS real standard ClamAV1.5.4: benign byte-bound evidence and EICAR rejection");
        } finally {try(var files=Files.walk(directory)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static ArtifactSource source(Path path,byte[] bytes) throws Exception {Files.write(path,bytes);Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));return new ArtifactSource(path,UploadFormat.PDF,bytes.length,StandardDefinitions.hex(StandardDefinitions.sha().digest(bytes)));}
}
