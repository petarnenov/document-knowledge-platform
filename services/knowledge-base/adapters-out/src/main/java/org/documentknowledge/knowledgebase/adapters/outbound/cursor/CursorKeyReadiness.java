package org.documentknowledge.knowledgebase.adapters.outbound.cursor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.documentknowledge.authorization.TrustedAuthorityFiles;
import org.documentknowledge.knowledgebase.domain.KbFailure;
/** Current private-key validity, without constructing a cursor or a fictitious actor. */
public final class CursorKeyReadiness {
    private final Path path; private final TrustedAuthorityFiles files;
    public CursorKeyReadiness(Path path) { this(path,TrustedAuthorityFiles.system()); }
    /** Java-only filesystem seam; production always selects system enforcement. */
    public CursorKeyReadiness(Path path,TrustedAuthorityFiles files) { this.path=path;this.files=files; }
    public boolean ready() { try { load(path,files);return true; } catch(Exception unavailable) { return false; } }
    static SecretKeySpec load(Path path,TrustedAuthorityFiles files) {
        try {
            String encoded=new String(files.readBounded(path,45,true),StandardCharsets.US_ASCII);
            if(encoded.endsWith("\n")) encoded=encoded.substring(0,encoded.length()-1);
            byte[] value=Base64.getDecoder().decode(encoded);
            if(!Base64.getEncoder().encodeToString(value).equals(encoded)||value.length!=32) throw KbFailure.unavailable();
            return new SecretKeySpec(value,"AES");
        } catch(Exception failure) { throw KbFailure.unavailable(); }
    }
}
