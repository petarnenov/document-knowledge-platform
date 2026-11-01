package org.documentknowledge.documentmanagement.adapters.outbound.verification;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.application.upload.ArtifactSource;
import org.documentknowledge.documentmanagement.domain.upload.UploadFormat;

/** Explicit real-worker acceptance. No conditional skip, mock server or production trust bypass. */
public final class RealInspectorAcceptance {
    private static final long MAX_BYTES=100_000_000;
    private RealInspectorAcceptance() { }

    public static void main(String[] args)throws Exception {
        if(args.length!=6)throw new IllegalArgumentException("Expected inspector endpoint, CA path, base64 key path, profile digest, image digest and fixtures directory");
        URI endpoint=URI.create(args[0]);Path directory=Path.of(args[5]).toRealPath();
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Fixture directory required");
        byte[] key=Base64.getDecoder().decode(new String(boundedFile(Path.of(args[2]),4096),StandardCharsets.US_ASCII).strip());
        if(key.length!=32)throw new IllegalArgumentException("Invalid service key file");
        SSLContext tls=verifiedTls(boundedFile(Path.of(args[1]),65536));
        Clock clock=Clock.systemUTC();Instant started=clock.instant();
        Map<UploadFormat,ArtifactSource> fixtures=new EnumMap<>(UploadFormat.class);
        for(UploadFormat format:UploadFormat.values())fixtures.put(format,source(directory.resolve("fixture."+format.extension()),format));
        try(var inspector=new IsolatedInspectionAdapter(endpoint,tls,key,args[3],args[4],clock)) {
            for(var entry:fixtures.entrySet()) {
                ArtifactSource source=entry.getValue();var evidence=inspector.inspect(source);
                if(evidence.format()!=entry.getKey() || !evidence.mimeType().equals(entry.getKey().mime())
                        || evidence.sizeBytes()!=source.sizeBytes() || !evidence.sha256().equals(source.sha256())
                        || !evidence.inspectorVersion().equals("1") || !evidence.profileDigest().equals(args[3])
                        || !evidence.imageDigest().equals(args[4]) || evidence.inspectedAt().isBefore(started)
                        || evidence.inspectedAt().isAfter(clock.instant())
                        || !Objects.equals(evidence.pageCount(),entry.getKey()==UploadFormat.PDF?Integer.valueOf(1):null)) {
                    throw new AssertionError("Real inspector returned inconsistent byte-bound evidence");
                }
            }
            Path malformed=Files.createTempFile("inspector-acceptance-",".pdf",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                Files.writeString(malformed,"synthetic malformed PDF without valid structure",StandardCharsets.US_ASCII);
                expectFailure(inspector,source(malformed,UploadFormat.PDF),422,"INVALID_FILE");
            }finally{Files.deleteIfExists(malformed);}
        }
        ArtifactSource valid=fixtures.get(UploadFormat.PDF);
        byte[] wrongKey=key.clone();wrongKey[0]^=1;
        try(var inspector=new IsolatedInspectionAdapter(endpoint,tls,wrongKey,args[3],args[4],clock)) {
            expectFailure(inspector,valid,503,"UNAVAILABLE");
        }
        String wrongProfile=args[3].equals("0".repeat(64))?"1".repeat(64):"0".repeat(64);
        try(var inspector=new IsolatedInspectionAdapter(endpoint,tls,key,wrongProfile,args[4],clock)) {
            expectFailure(inspector,valid,503,"UNAVAILABLE");
        }
        // An empty private trust store contains no trust anchors; never use ambient JVM trust.
        try(var inspector=new IsolatedInspectionAdapter(endpoint,verifiedTls(null),key,args[3],args[4],clock)) {
            expectFailure(inspector,valid,503,"UNAVAILABLE");
        }
        Arrays.fill(key,(byte)0);Arrays.fill(wrongKey,(byte)0);
        System.out.println("PASS real isolated inspector: PDF/DOCX/PPTX/XLSX evidence, malformed structure, credentials, profile and TLS rejection");
    }

    private static void expectFailure(IsolatedInspectionAdapter inspector,ArtifactSource source,int status,String code) {
        try{inspector.inspect(source);throw new AssertionError("Real inspector accepted a negative acceptance case");}
        catch(DmFailure failure){if(failure.status()!=status || !failure.code().equals(code))throw new AssertionError("Unexpected real inspector failure classification",failure);}
    }
    private static ArtifactSource source(Path path,UploadFormat format)throws Exception {
        if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Regular fixture file required");
        long size=Files.size(path);if(size<1 || size>MAX_BYTES)throw new IllegalArgumentException("Fixture byte limit");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;
        try(InputStream input=Files.newInputStream(path)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1){count+=n;if(count>MAX_BYTES)throw new IllegalArgumentException("Fixture byte limit");digest.update(buffer,0,n);}}
        if(count!=size)throw new IllegalArgumentException("Fixture changed while hashing");
        return new ArtifactSource(path,format,size,HexFormat.of().formatHex(digest.digest()));
    }
    private static byte[] boundedFile(Path path,int maximum)throws Exception {
        if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>maximum)throw new IllegalArgumentException("Invalid bounded configuration file");
        try(InputStream input=Files.newInputStream(path)){byte[] bytes=input.readNBytes(maximum+1);if(bytes.length>maximum)throw new IllegalArgumentException("Configuration byte limit");return bytes;}
    }
    private static SSLContext verifiedTls(byte[] ca)throws Exception {
        KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);
        if(ca!=null) {
            var certificates=CertificateFactory.getInstance("X.509").generateCertificates(new java.io.ByteArrayInputStream(ca));
            if(certificates.isEmpty())throw new IllegalArgumentException("Private CA certificate required");
            int index=0;for(var certificate:certificates){if(!(certificate instanceof X509Certificate x509)||x509.getBasicConstraints()<0)throw new IllegalArgumentException("CA certificate required");x509.checkValidity();trust.setCertificateEntry("inspector-ca-"+index++,certificate);}
        }
        var managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());managers.init(trust);
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,managers.getTrustManagers(),null);return tls;
    }
}
