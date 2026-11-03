package org.documentknowledge.browserbff.adapters.outbound;

import java.io.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.documentknowledge.browserbff.application.BrowserLogContexts;
import org.documentknowledge.browserbff.application.BrowserLogGateway;

/** Private recovery format. Never includes browser/session credentials or restores session authority. */
final class BrowserLogScopeCheckpoint {
    private static final long MAGIC = 0x444b504253435031L;
    record Binding(URI endpoint, UUID parent, String caSha256, Map<UUID, BrowserLogContexts.Artifact> artifacts) {
        Binding {
            Objects.requireNonNull(endpoint); id(parent);
            require("https".equals(endpoint.getScheme()) && endpoint.getHost() != null && endpoint.getUserInfo() == null
                && endpoint.getRawQuery() == null && endpoint.getRawFragment() == null
                && "/api/v1/dev-logs".equals(endpoint.getRawPath()) && caSha256 != null && caSha256.matches("[0-9a-f]{64}"));
            artifacts = Map.copyOf(artifacts);
            require(!artifacts.isEmpty() && artifacts.size() <= 64
                && artifacts.entrySet().stream().allMatch(row -> row.getKey().equals(row.getValue().buildId())));
        }
        @Override public String toString() { return "BrowserLogRecoveryBinding[REDACTED]"; }
    }
    record Recovered(BrowserLogGateway.Grant grant, UUID scopeId, String token, Instant replayUntil) {
        Recovered {
            Objects.requireNonNull(grant); id(scopeId); id(grant.contextEpoch()); id(grant.runId());
            require(token != null && token.matches("[A-Za-z0-9_-]{32,512}")
                && replayUntil != null && !replayUntil.isBefore(grant.captureUntil()));
        }
        @Override public String toString() { return "BrowserLogRecoveredScope[REDACTED]"; }
    }
    private BrowserLogScopeCheckpoint() { }
    static byte[] encode(Binding binding, Recovered scope) {
        try {
            var grant = scope.grant(); var artifact = grant.artifact(); var authority = grant.authority();
            require(artifact.equals(binding.artifacts().get(artifact.buildId())));
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeLong(MAGIC); output.writeUTF(binding.endpoint().toASCIIString());
                output.writeUTF(binding.parent().toString()); output.writeUTF(binding.caSha256());
                output.writeUTF(scope.scopeId().toString()); output.writeUTF(grant.contextEpoch().toString());
                output.writeUTF(artifact.buildId().toString()); output.writeUTF(grant.runId().toString());
                output.writeUTF(artifact.component()); output.writeUTF(artifact.gitSha()); output.writeUTF(artifact.sourceFingerprint());
                output.writeUTF(artifact.manifestSha256()); output.writeUTF(artifact.vocabularySha256());
                output.writeUTF(authority.issuer()); output.writeUTF(authority.subject());
                output.writeBoolean(authority.tenantId() != null);
                if (authority.tenantId() != null) output.writeUTF(authority.tenantId());
                output.writeUTF(grant.captureUntil().toString()); output.writeUTF(scope.replayUntil().toString());
                output.writeUTF(scope.token());
            }
            require(bytes.size() <= 8192); return bytes.toByteArray();
        } catch (Exception invalid) { throw failure(); }
    }
    static Recovered decode(Binding binding, byte[] bytes) {
        try {
            require(bytes != null && bytes.length > 0 && bytes.length <= 8192);
            try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                require(input.readLong() == MAGIC && input.readUTF().equals(binding.endpoint().toASCIIString())
                    && input.readUTF().equals(binding.parent().toString()) && input.readUTF().equals(binding.caSha256()));
                UUID scope = uuid(input), epoch = uuid(input), build = uuid(input), run = uuid(input);
                var artifact = new BrowserLogContexts.Artifact(build, input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF());
                require(artifact.equals(binding.artifacts().get(build)));
                String issuer = input.readUTF(), subject = input.readUTF(), tenant = input.readBoolean() ? input.readUTF() : null;
                Instant capture = Instant.parse(input.readUTF()), replay = Instant.parse(input.readUTF()); String token = input.readUTF();
                require(input.available() == 0);
                // This synthetic identifier is local to the outbound handle; it never enters session/context storage.
                var authority = new BrowserLogContexts.Authority(epoch.toString(), issuer, subject, tenant, capture);
                return new Recovered(new BrowserLogGateway.Grant(epoch, artifact, run, authority, capture), scope, token, replay);
            }
        } catch (Exception invalid) { throw failure(); }
    }
    private static UUID uuid(DataInputStream input) throws IOException {
        String text = input.readUTF(); UUID value = UUID.fromString(text); id(value); require(value.toString().equals(text)); return value;
    }
    private static void id(UUID value) { require(value != null && !value.equals(new UUID(0, 0))); }
    private static void require(boolean value) { if (!value) throw failure(); }
    private static IllegalArgumentException failure() { return new IllegalArgumentException("INVALID_BROWSER_LOG_CHECKPOINT"); }
}
