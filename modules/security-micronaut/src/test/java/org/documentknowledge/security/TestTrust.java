package org.documentknowledge.security;
import java.util.List;
final class TestTrust {
    static TrustConfiguration config() {
        var c=new TrustConfiguration();c.setMode("resource");c.setIssuer("https://issuer.example/realms/dkp");
        c.setStandaloneIssuer(c.getIssuer());c.setJwksUrl("https://issuer.example/realms/dkp/certs");
        c.setAudience("dkp-dm");c.setAuthorizedParties(List.of("dkp-bff"));c.setProfile("standalone");return c;
    }
}
