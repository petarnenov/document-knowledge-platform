package org.documentknowledge.documentmanagement.adapters.outbound.verification;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.time.*;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Reads the updater-owned manifest and independently hashes all three actual standard databases. */
public final class StandardDefinitions {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Path directory;
    private final Clock clock;
    public record Snapshot(String version,Instant updatedAt,String digest) { }
    public StandardDefinitions(Path directory,Clock clock) {this.directory=directory;this.clock=clock;}
    public Snapshot snapshot() throws IOException {
        safe(directory,false);Path manifest=directory.resolve("dkp-update.json");safe(manifest,true);
        byte[] metadata;try(var input=Files.newInputStream(manifest,LinkOption.NOFOLLOW_LINKS)){metadata=input.readNBytes(4097);}if(metadata.length>4096)throw new IOException("Manifest limit");
        JsonNode value=JSON.readTree(metadata);Set<String> fields=new HashSet<>();if(value==null || !value.isObject())throw new IOException("Invalid manifest");value.propertyNames().forEach(fields::add);
        if(!fields.equals(Set.of("schemaVersion","updatedAt","databaseVersion","databaseDigest")) || !value.get("schemaVersion").isIntegralNumber() || !value.get("schemaVersion").canConvertToInt() || value.get("schemaVersion").intValue()!=1)throw new IOException("Invalid manifest");
        String version=text(value,"databaseVersion"),expected=text(value,"databaseDigest");Instant updated;
        try{updated=Instant.parse(text(value,"updatedAt"));}catch(RuntimeException invalid){throw new IOException("Invalid update time");}
        Instant now=clock.instant();if(updated.isAfter(now) || updated.isBefore(now.minus(Duration.ofHours(24))) || !version.matches("[0-9]{1,20}") || !expected.matches("[0-9a-f]{64}"))throw new IOException("Stale definitions");
        List<Path> files=new ArrayList<>();String dailyVersion=null;long total=0;
        for(String family:List.of("bytecode","daily","main")) {
            Path cvd=directory.resolve(family+".cvd"),cld=directory.resolve(family+".cld");boolean a=Files.exists(cvd,LinkOption.NOFOLLOW_LINKS),b=Files.exists(cld,LinkOption.NOFOLLOW_LINKS);
            if(a==b)throw new IOException("Ambiguous standard database");Path path=a?cvd:cld;safe(path,true);total+=Files.size(path);if(total>1_000_000_000L)throw new IOException("Database limit");
            String header;try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){header=new String(input.readNBytes(512),StandardCharsets.US_ASCII).strip();}
            String[] parts=header.split(":");if(parts.length<9 || !parts[0].equals("ClamAV-VDB") || !parts[2].matches("[0-9]{1,20}"))throw new IOException("Not standard definitions");
            try{if(Instant.ofEpochSecond(Long.parseLong(parts[8].strip())).isAfter(now))throw new IOException("Future definitions");}catch(NumberFormatException invalid){throw new IOException("Invalid definitions time");}
            if(family.equals("daily"))dailyVersion=parts[2];files.add(path);
        }
        if(!version.equals(dailyVersion))throw new IOException("Database version mismatch");files.sort(Comparator.comparing(p->p.getFileName().toString()));
        StringBuilder canonical=new StringBuilder();
        for(Path file:files) {
            var before=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);MessageDigest digest=sha();long bytes=0;
            try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1){bytes+=n;if(bytes>1_000_000_000L)throw new IOException("Database limit");digest.update(buffer,0,n);}}
            var after=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(bytes!=before.size() || !Objects.equals(before.fileKey(),after.fileKey()) || before.size()!=after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()))throw new IOException("Definitions changed");
            canonical.append(hex(digest.digest())).append("  ").append(file.getFileName()).append('\n');
        }
        String actual=hex(sha().digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));if(!actual.equals(expected))throw new IOException("Definitions digest mismatch");
        return new Snapshot(version,updated,actual);
    }
    private static String text(JsonNode node,String field) throws IOException {JsonNode value=node.get(field);if(value==null || !value.isString())throw new IOException("Invalid manifest");return value.asString();}
    private static void safe(Path path,boolean file) throws IOException {if(Files.isSymbolicLink(path) || (file?!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS):!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)))throw new IOException("Unsafe definitions");var p=Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS);if(p.contains(PosixFilePermission.GROUP_WRITE)||p.contains(PosixFilePermission.OTHERS_WRITE))throw new IOException("Unsafe definitions");}
    static MessageDigest sha() {try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    static String hex(byte[] bytes) {return HexFormat.of().formatHex(bytes);}
}
