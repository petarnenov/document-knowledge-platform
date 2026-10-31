package org.documentknowledge.security;

import io.micronaut.context.annotation.ConfigurationProperties;
import java.net.URI;
import java.util.List;

/** Missing or unsafe configuration closes authentication; probes remain available. */
@ConfigurationProperties("dkp.security")
public class TrustConfiguration {
    private String mode = "unconfigured";
    private String issuer = "";
    private String standaloneIssuer = "";
    private String audience = "";
    private String jwksUrl = "";
    private String profile = "unconfigured";
    private List<String> authorizedParties = List.of();
    private int jwksLeaseSeconds = 30;
    private int timeoutMillis = 2000;
    public String getMode() { return mode; }
    public void setMode(String value) { mode = value; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String value) { issuer = value; }
    public String getStandaloneIssuer() { return standaloneIssuer; }
    public void setStandaloneIssuer(String value) { standaloneIssuer = value; }
    public String getAudience() { return audience; }
    public void setAudience(String value) { audience = value; }
    public String getJwksUrl() { return jwksUrl; }
    public void setJwksUrl(String value) { jwksUrl = value; }
    public String getProfile() { return profile; }
    public void setProfile(String value) { profile = value; }
    public List<String> getAuthorizedParties() { return authorizedParties; }
    public void setAuthorizedParties(List<String> value) { authorizedParties = List.copyOf(value); }
    public int getJwksLeaseSeconds() { return jwksLeaseSeconds; }
    public void setJwksLeaseSeconds(int value) { jwksLeaseSeconds = value; }
    public int getTimeoutMillis() { return timeoutMillis; }
    public void setTimeoutMillis(int value) { timeoutMillis = value; }
    public boolean usable() {
        return "resource".equals(mode) && secureEndpoint(issuer) && secureEndpoint(jwksUrl) && audience != null && !audience.isBlank()
            && !authorizedParties.isEmpty() && authorizedParties.stream().allMatch(v -> v != null && !v.isBlank())
            && jwksLeaseSeconds > 0 && jwksLeaseSeconds <= 30 && timeoutMillis > 0 && timeoutMillis <= 5000
            && ("external".equals(profile) || ("standalone".equals(profile) && issuer.equals(standaloneIssuer)));
    }
    private static boolean secureEndpoint(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                && uri.getFragment() == null && uri.getQuery() == null;
        } catch (IllegalArgumentException | NullPointerException e) { return false; }
    }
}
