package org.documentknowledge.knowledgebase.adapters.outbound.remote;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.*;
import org.documentknowledge.authorization.TrustedAuthorityFiles;
import org.documentknowledge.knowledgebase.domain.KbFailure;
final class ScopedDmTls {
    private ScopedDmTls() { }
    static SSLContext load(Path ca) {
        try {
            byte[] bytes=TrustedAuthorityFiles.system().readBounded(ca,65536,true);
            var certificates=CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(bytes));
            if(certificates.isEmpty()||certificates.size()>8) throw KbFailure.unavailable();
            KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);int i=0;
            for(var certificate:certificates) trust.setCertificateEntry("kb-dm-"+(i++),certificate);
            TrustManagerFactory managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());managers.init(trust);
            SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,managers.getTrustManagers(),null);return tls;
        } catch(Exception unavailable) { throw KbFailure.unavailable(); }
    }
}
