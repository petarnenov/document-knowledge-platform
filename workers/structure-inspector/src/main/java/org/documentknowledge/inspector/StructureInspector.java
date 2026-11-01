package org.documentknowledge.inspector;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.HexFormat;

/** Independent content hashing and bounded, disposable local inspection. No caller paths are accepted. */
public final class StructureInspector {
    public static final long MAX_BYTES=100_000_000;
    public static final String PROFILE="dkp-004-v1;bytes=100000000;pdfPages=1000;pdfObjects=100000;depth=64;zipBytes=512000000;entries=10000;xmlBytes=32000000;ratio=100;attributes=100;active=deny;zipGrammar=2;zipPreflight=1;mediaGrammar=1;mediaDecodedBytes=512000000;pdfLazyErrors=deny";
    public static final String PROFILE_DIGEST=hex(digest().digest(PROFILE.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    private final Path temporaryRoot;
    public StructureInspector(Path temporaryRoot) { this.temporaryRoot=temporaryRoot; }
    public record Result(int schemaVersion, String format, String mimeType, long sizeBytes, String sha256, Integer pageCount, String inspectorVersion, String profileDigest) { }
    public Result inspect(InputStream source, Format format) throws IOException {
        Path directory=Files.createTempDirectory(temporaryRoot,"inspection-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path file=directory.resolve("source");
        try {
            MessageDigest sha=digest();long count=0;
            try(OutputStream out=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
                byte[] buffer=new byte[65536];int n;
                while((n=source.read(buffer))!=-1) { count+=n;if(count>MAX_BYTES)throw new InspectionFailure("PAYLOAD_TOO_LARGE");sha.update(buffer,0,n);out.write(buffer,0,n); }
            } catch(IOException unavailable) { throw new InspectionFailure("UNAVAILABLE"); }
            if(count==0)throw new InspectionFailure("INVALID_FILE");
            Integer pages;
            if(format==Format.PDF) pages=PdfInspector.inspect(file);
            else { OfficeInspector.inspect(file,format);pages=null; }
            return new Result(1,format.name(),format.mime,count,hex(sha.digest()),pages,"1",PROFILE_DIGEST);
        } catch(InspectionFailure failure) { throw failure; }
        catch(IOException | RuntimeException invalid) { throw new InspectionFailure("INVALID_FILE"); }
        finally { Files.deleteIfExists(file);Files.deleteIfExists(directory); }
    }
    static MessageDigest digest() { try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);} }
    static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
}
