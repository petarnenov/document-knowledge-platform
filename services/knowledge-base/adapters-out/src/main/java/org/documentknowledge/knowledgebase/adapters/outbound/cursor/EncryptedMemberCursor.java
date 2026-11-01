package org.documentknowledge.knowledgebase.adapters.outbound.cursor;
import java.io.*;
import java.nio.file.Path;
import java.util.HexFormat;
import org.documentknowledge.authorization.TrustedAuthorityFiles;
import org.documentknowledge.knowledgebase.application.MemberCursorPort;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.documentknowledge.knowledgebase.domain.KbModels;
import org.documentknowledge.knowledgebase.domain.KbModels.Context;
public final class EncryptedMemberCursor implements MemberCursorPort {
    private final Context context; private final PrivateCursorCodec codec;
    public EncryptedMemberCursor(Path key,Context context) { this(key,context,TrustedAuthorityFiles.system()); }
    public EncryptedMemberCursor(Path key,Context context,TrustedAuthorityFiles files) { this.context=context; codec=new PrivateCursorCodec(key,context,"members",files); }
    public String encode(Position position) {
        try {
            if(!context.equals(position.context())) throw KbFailure.unavailable(); KbModels.revision(position.fence()); KbModels.revision(position.collectionRevision());
            var bytes=new ByteArrayOutputStream(); var out=new DataOutputStream(bytes);
            PrivateCursorCodec.uuid(out,position.collectionId()); out.writeLong(position.fence()); out.writeLong(position.collectionRevision());
            digest(out,position.candidateDigest()); digest(out,position.eligibleDigest()); PrivateCursorCodec.uuid(out,position.lastVisible()); return codec.encode(bytes.toByteArray());
        } catch(Exception failure) { throw KbFailure.unavailable(); }
    }
    private static void digest(DataOutputStream out,String value)throws IOException { if(!value.matches("[a-f0-9]{64}")) throw KbFailure.unavailable(); out.write(HexFormat.of().parseHex(value)); }
    public Position decode(String token) {
        byte[] bytes=codec.decode(token);
        try {
            var in=new DataInputStream(new ByteArrayInputStream(bytes)); var collection=PrivateCursorCodec.uuid(in); long fence=KbModels.revision(in.readLong()), revision=KbModels.revision(in.readLong());
            byte[] candidate=in.readNBytes(32),eligible=in.readNBytes(32); var last=PrivateCursorCodec.uuid(in);
            if(candidate.length!=32||eligible.length!=32||in.available()!=0) throw PrivateCursorCodec.bad();
            return new Position(context,collection,fence,revision,HexFormat.of().formatHex(candidate),HexFormat.of().formatHex(eligible),last);
        } catch(Exception invalid) { throw PrivateCursorCodec.bad(); }
    }
}
