package org.documentknowledge.knowledgebase.adapters.outbound.cursor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.documentknowledge.authorization.TrustedAuthorityFiles;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.documentknowledge.knowledgebase.domain.KbModels.Context;

/** No cursor payload or hidden-set digest is readable without the private current key. */
final class PrivateCursorCodec {
    private final Path key;
    private final TrustedAuthorityFiles files;
    private final byte[] aad;
    private final SecureRandom random=new SecureRandom();
    PrivateCursorCodec(Path key,Context context,String type,TrustedAuthorityFiles files) {
        this.key=key; this.files=files;
        try {
            var bytes=new ByteArrayOutputStream(); var out=new DataOutputStream(bytes);
            out.writeUTF("dkp-kb-cursor-v1/"+type); out.writeUTF(context.actor().issuer()); out.writeUTF(context.actor().subject()); out.writeUTF(context.tenant());
            aad=MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch(Exception failure) { throw KbFailure.unavailable(); }
    }
    private SecretKeySpec key() { return CursorKeyReadiness.load(key,files); }
    String encode(byte[] payload) {
        SecretKeySpec current=key();
        try {
            byte[] nonce=new byte[12]; random.nextBytes(nonce);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,current,new GCMParameterSpec(128,nonce)); cipher.updateAAD(aad);
            byte[] encrypted=cipher.doFinal(payload); byte[] value=new byte[nonce.length+encrypted.length];
            System.arraycopy(nonce,0,value,0,12); System.arraycopy(encrypted,0,value,12,encrypted.length);
            String token=Base64.getUrlEncoder().withoutPadding().encodeToString(value);
            if(token.length()>2048) throw KbFailure.unavailable(); return token;
        } catch(Exception failure) { throw KbFailure.unavailable(); }
    }
    byte[] decode(String token) {
        if(token==null||token.isEmpty()||token.length()>2048||!token.matches("[A-Za-z0-9_-]+")) throw bad();
        SecretKeySpec current=key();
        try {
            byte[] value=Base64.getUrlDecoder().decode(token);
            if(value.length<29||!Base64.getUrlEncoder().withoutPadding().encodeToString(value).equals(token)) throw bad();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,current,new GCMParameterSpec(128,Arrays.copyOf(value,12))); cipher.updateAAD(aad);
            return cipher.doFinal(value,12,value.length-12);
        } catch(Exception invalid) { throw bad(); }
    }
    static KbFailure bad() { return new KbFailure(400,"BAD_REQUEST"); }
    static void uuid(DataOutputStream out,UUID id)throws IOException { out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits()); }
    static UUID uuid(DataInputStream in)throws IOException { return new UUID(in.readLong(),in.readLong()); }
}
