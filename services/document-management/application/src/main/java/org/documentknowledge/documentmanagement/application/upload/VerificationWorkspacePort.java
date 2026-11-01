package org.documentknowledge.documentmanagement.application.upload;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.identity.Actor;

/** One durable SOURCE plus one reusable scratch; capabilities are server-owned, never wire DTOs. */
public interface VerificationWorkspacePort extends AutoCloseable {
    record Identity(String tenantDigest,String actorDigest,UUID documentId,UUID uploadId,long declaredSize) {
        public Identity {
            if(tenantDigest==null||!tenantDigest.matches("[0-9a-f]{64}")||actorDigest==null||!actorDigest.matches("[0-9a-f]{64}")||documentId==null||uploadId==null||declaredSize<1||declaredSize>100_000_000)throw new DmFailure(503,"UNAVAILABLE");
        }
        public static Identity of(String tenant,Actor actor,UUID document,UUID upload,long size){
            Objects.requireNonNull(tenant);Objects.requireNonNull(actor);
            try {
                var hash=MessageDigest.getInstance("SHA-256");String tenantHash=HexFormat.of().formatHex(hash.digest(tenant.getBytes(StandardCharsets.UTF_8)));
                for(String part:new String[]{actor.issuer(),actor.subject()}){byte[] bytes=part.getBytes(StandardCharsets.UTF_8);hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());hash.update(bytes);}
                return new Identity(tenantHash,HexFormat.of().formatHex(hash.digest()),document,upload,size);
            }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        }
    }
    enum Role { COPY,READBACK }
    record ArtifactFile(Path path,long size,String sha256) {
        public ArtifactFile { Objects.requireNonNull(path);if(size<1||size>100_000_000||sha256==null||!sha256.matches("[0-9a-f]{64}"))throw new DmFailure(503,"UNAVAILABLE"); }
    }
    Workspace reserve(Identity identity,CompleteBudget.Phase phase);
    /** Operational only; never grants document authority or produces verification evidence. */
    boolean ready();
    void check(Workspace workspace,CompleteBudget.Phase phase);
    @Override void close();
    interface Workspace extends AutoCloseable {
        Buffer source();
        /** Track each local source reader (scanner/inspector/S3 PUT) through actual settlement. */
        Use retain(CompleteBudget.Phase phase);
        interface Use extends AutoCloseable { @Override void close(); }
        Buffer scratch(Role role,CompleteBudget.Phase phase);
        /** Both buffers absent and durable record removed BEFORE receipt persistence. */
        void finish(CompleteBudget.Phase phase);
        /** Marks execution inactive only. Never implicitly unlinks or releases unresolved physical bytes. */
        @Override void close();
    }
    interface Buffer {
        ArtifactFile write(InputStream input,CompleteBudget.Phase phase);
        void cleanup(CompleteBudget.Phase phase);
    }
    /** Compatibility constructor remains fail closed for COMPLETE; transfer is a separate scope. */
    static VerificationWorkspacePort unavailable(){return new VerificationWorkspacePort(){
        public Workspace reserve(Identity identity,CompleteBudget.Phase phase){throw new DmFailure(503,"UNAVAILABLE");}
        public boolean ready(){return false;}
        public void check(Workspace workspace,CompleteBudget.Phase phase){throw new DmFailure(503,"UNAVAILABLE");}
        public void close(){}
    };}
}
