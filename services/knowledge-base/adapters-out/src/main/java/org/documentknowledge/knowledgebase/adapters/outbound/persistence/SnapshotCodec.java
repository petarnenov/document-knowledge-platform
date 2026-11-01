package org.documentknowledge.knowledgebase.adapters.outbound.persistence;

import java.io.*;
import java.util.*;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.documentknowledge.knowledgebase.domain.KbModels.*;

/** Private bounded binary evidence, never a Java serialization stream or public token. */
final class SnapshotCodec {
    private static final int MAGIC=0x4b425331;
    private SnapshotCodec() { }
    static byte[] encode(List<ScopeEntry> entries) {
        validate(entries);
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            out.writeInt(MAGIC);out.writeInt(entries.size());
            for(var entry:entries){uuid(out,entry.membershipId());uuid(out,entry.collectionId());out.writeLong(entry.collectionRevision());out.writeLong(entry.collectionMembershipRevision());out.writeLong(entry.membershipRevision());out.writeLong(entry.audienceRevision());out.writeByte(entry.state().ordinal());out.writeByte(entry.status().ordinal());}
            out.flush();return bytes.toByteArray();
        }catch(IOException impossible){throw KbFailure.unavailable();}
    }
    static List<ScopeEntry> decode(int version,byte[] bytes) {
        if(version!=1||bytes==null||bytes.length<8||bytes.length>4096)throw KbFailure.unavailable();
        try {
            var in=new DataInputStream(new ByteArrayInputStream(bytes));
            if(in.readInt()!=MAGIC)throw KbFailure.unavailable();
            int size=in.readInt();if(size<0||size>50)throw KbFailure.unavailable();
            var result=new ArrayList<ScopeEntry>();
            for(int i=0;i<size;i++)result.add(new ScopeEntry(uuid(in),uuid(in),in.readLong(),in.readLong(),in.readLong(),in.readLong(),state(in.readUnsignedByte()),status(in.readUnsignedByte())));
            if(in.read()!=-1)throw KbFailure.unavailable();validate(result);return List.copyOf(result);
        }catch(IOException|RuntimeException failure){throw KbFailure.unavailable();}
    }
    private static void validate(List<ScopeEntry> entries){
        if(entries==null||entries.size()>50)throw KbFailure.unavailable();
        UUID previous=null;var memberships=new HashSet<UUID>();
        for(var entry:entries){if(entry==null||entry.audienceRevision()<1||!memberships.add(entry.membershipId())||previous!=null&&previous.toString().compareTo(entry.collectionId().toString())>=0)throw KbFailure.unavailable();previous=entry.collectionId();}
    }
    private static State state(int n){if(n>=State.values().length)throw KbFailure.unavailable();return State.values()[n];}
    private static Status status(int n){if(n>=Status.values().length)throw KbFailure.unavailable();return Status.values()[n];}
    private static void uuid(DataOutputStream out,UUID id)throws IOException{out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());}
    private static UUID uuid(DataInputStream in)throws IOException{return new UUID(in.readLong(),in.readLong());}
}
