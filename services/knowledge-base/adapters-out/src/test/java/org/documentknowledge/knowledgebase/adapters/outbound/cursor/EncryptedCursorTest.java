package org.documentknowledge.knowledgebase.adapters.outbound.cursor;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.documentknowledge.authorization.TrustedAuthorityFiles;
import org.documentknowledge.knowledgebase.application.*;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.documentknowledge.knowledgebase.domain.KbModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class EncryptedCursorTest {
    @TempDir Path directory;
    Path key; Context actor=new Context(new Principal("https://issuer.example","staff"),"tenant-a");
    TrustedAuthorityFiles files=new TrustedAuthorityFiles(new TrustedAuthorityFiles.Platform("Linux",false,new com.sun.security.auth.module.UnixSystem().getUid()));
    EncryptedCollectionCursor collections; EncryptedMemberCursor members;
    UUID collection=UUID.randomUUID(),last=UUID.randomUUID(); String digest="a".repeat(64);
    @BeforeEach void setup()throws Exception {key=directory.resolve("cursor.key"); write(new byte[32]); collections=new EncryptedCollectionCursor(key,actor,files); members=new EncryptedMemberCursor(key,actor,files);}
    void write(byte[] value)throws Exception {Files.writeString(key,Base64.getEncoder().encodeToString(value)+"\n");Files.setPosixFilePermissions(key,PosixFilePermissions.fromString("rw-------"));}
    void failure(int status,Runnable action){assertEquals(status,assertThrows(KbFailure.class,action::run).status());}
    @Test void bothTypesRoundTripPrivatelyWithRandomNonceAndTrustedContext() {
        var cp=new CollectionCursorPort.Position(actor,7,last); String first=collections.encode(cp); assertEquals(cp,collections.decode(first)); assertNotEquals(first,collections.encode(cp));
        var mp=new MemberCursorPort.Position(actor,collection,7,12,digest,"b".repeat(64),last); String token=members.encode(mp); assertEquals(mp,members.decode(token));
        assertTrue(token.length()<2048); assertFalse(token.contains(digest)); assertFalse(new String(Base64.getUrlDecoder().decode(token),java.nio.charset.StandardCharsets.UTF_8).contains(digest));
        failure(400,()->members.decode(first)); failure(400,()->collections.decode(token));
    }
    @Test void actorTenantIssuerAndPayloadTamperNeverDecode() {
        String token=collections.encode(new CollectionCursorPort.Position(actor,7,last));
        for(Context other:List.of(new Context(actor.actor(),"tenant-b"),new Context(new Principal(actor.actor().issuer(),"other"),actor.tenant()),new Context(new Principal("https://other.example","staff"),actor.tenant()))) failure(400,()->new EncryptedCollectionCursor(key,other,files).decode(token));
        byte[] changed=Base64.getUrlDecoder().decode(token); changed[changed.length-1]^=1; failure(400,()->collections.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(changed)));
        for(String bad:List.of("","!","a","a".repeat(2049),token+"=","AA")) failure(400,()->collections.decode(bad));
        failure(400,()->collections.decode(null));
        failure(503,()->collections.encode(new CollectionCursorPort.Position(new Context(actor.actor(),"tenant-b"),0,last)));
        failure(503,()->members.encode(new MemberCursorPort.Position(actor,collection,-1,0,digest,digest,last)));
        failure(503,()->members.encode(new MemberCursorPort.Position(actor,collection,0,0,"bad",digest,last)));
    }
    @Test void currentKeyReplacementMissingMalformedAndPermissionsNeverFallback()throws Exception {
        String token=collections.encode(new CollectionCursorPort.Position(actor,7,last)); byte[] replacement=new byte[32];Arrays.fill(replacement,(byte)1);write(replacement);failure(400,()->collections.decode(token));
        for(String bad:List.of("", "not-base64",Base64.getEncoder().encodeToString(new byte[31]),Base64.getEncoder().encodeToString(new byte[33])," "+Base64.getEncoder().encodeToString(new byte[32]),"x".repeat(46))) {
            Files.writeString(key,bad);failure(503,()->collections.encode(new CollectionCursorPort.Position(actor,0,last)));failure(503,()->collections.decode(token));
        }
        write(replacement);Files.setPosixFilePermissions(key,PosixFilePermissions.fromString("rw-r--r--"));failure(503,()->collections.decode(token));
        Files.delete(key);failure(503,()->collections.decode(token));
        failure(503,()->new EncryptedCollectionCursor(key,actor).encode(new CollectionCursorPort.Position(actor,0,last)));
        failure(503,()->new EncryptedMemberCursor(key,actor).encode(new MemberCursorPort.Position(actor,collection,0,0,digest,digest,last)));
    }
    @Test void operationalKeyValidityIsFreshAndDoesNotConstructCursors()throws Exception {
        var readiness=new CursorKeyReadiness(key,files);assertTrue(readiness.ready());
        write(new byte[31]);assertFalse(readiness.ready());write(new byte[32]);assertTrue(readiness.ready());
        Files.setPosixFilePermissions(key,PosixFilePermissions.fromString("rw-r--r--"));assertFalse(readiness.ready());
        Files.delete(key);assertFalse(readiness.ready());assertFalse(new CursorKeyReadiness(key).ready());
    }
    @Test void productionSystemFactoryRemainsFailClosedOnUnsupportedPlatform() {
        var position=new CollectionCursorPort.Position(actor,0,last);
        if(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux")) {
            var production=new EncryptedCollectionCursor(key,actor); assertEquals(position,production.decode(production.encode(position)));
            var memberPosition=new MemberCursorPort.Position(actor,collection,0,0,digest,digest,last);
            var productionMembers=new EncryptedMemberCursor(key,actor); assertEquals(memberPosition,productionMembers.decode(productionMembers.encode(memberPosition)));
        } else failure(503,()->new EncryptedCollectionCursor(key,actor).encode(position));
    }
    @Test void authenticatedButMalformedInternalPayloadsAreRejectedNotPartiallyRead()throws Exception {
        var cc=new PrivateCursorCodec(key,actor,"collections",files); var mc=new PrivateCursorCodec(key,actor,"members",files);
        for(byte[] payload:List.of(new byte[0],new byte[1],new byte[25],new byte[24])) {
            if(payload.length==24) Arrays.fill(payload,(byte)255);
            failure(400,()->collections.decode(cc.encode(payload)));
        }
        for(int size:List.of(0,48,111,113)) failure(400,()->members.decode(mc.encode(new byte[size])));
        failure(503,()->cc.encode(new byte[2048]));
        failure(503,()->new PrivateCursorCodec(key,null,"collections",files));
    }
}
