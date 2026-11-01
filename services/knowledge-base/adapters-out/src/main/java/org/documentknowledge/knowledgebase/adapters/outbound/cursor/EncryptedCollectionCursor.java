package org.documentknowledge.knowledgebase.adapters.outbound.cursor;
import java.io.*;
import java.nio.file.Path;
import org.documentknowledge.authorization.TrustedAuthorityFiles;
import org.documentknowledge.knowledgebase.application.CollectionCursorPort;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.documentknowledge.knowledgebase.domain.KbModels;
import org.documentknowledge.knowledgebase.domain.KbModels.Context;
public final class EncryptedCollectionCursor implements CollectionCursorPort {
    private final Context context; private final PrivateCursorCodec codec;
    public EncryptedCollectionCursor(Path key,Context context) { this(key,context,TrustedAuthorityFiles.system()); }
    public EncryptedCollectionCursor(Path key,Context context,TrustedAuthorityFiles files) { this.context=context; codec=new PrivateCursorCodec(key,context,"collections",files); }
    public String encode(Position position) {
        try {
            if(!context.equals(position.context())) throw KbFailure.unavailable(); KbModels.revision(position.fence());
            var bytes=new ByteArrayOutputStream(); var out=new DataOutputStream(bytes); out.writeLong(position.fence()); PrivateCursorCodec.uuid(out,position.lastVisible()); return codec.encode(bytes.toByteArray());
        } catch(Exception failure) { throw KbFailure.unavailable(); }
    }
    public Position decode(String token) {
        byte[] bytes=codec.decode(token);
        try { var in=new DataInputStream(new ByteArrayInputStream(bytes)); long fence=KbModels.revision(in.readLong()); var id=PrivateCursorCodec.uuid(in); if(in.available()!=0) throw PrivateCursorCodec.bad(); return new Position(context,fence,id); }
        catch(Exception invalid) { throw PrivateCursorCodec.bad(); }
    }
}
