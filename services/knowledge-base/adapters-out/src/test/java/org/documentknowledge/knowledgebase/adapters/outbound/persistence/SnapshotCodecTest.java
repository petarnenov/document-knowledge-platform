package org.documentknowledge.knowledgebase.adapters.outbound.persistence;

import java.nio.ByteBuffer;
import java.util.*;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.documentknowledge.knowledgebase.domain.KbModels.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotCodecTest {
    private ScopeEntry entry(int n){return new ScopeEntry(new UUID(1,n),new UUID(0,n),2,3,4,State.INACTIVE,Status.ARCHIVED);}
    @Test void emptyAndMaximumCompleteInactiveArchivedSetsRoundTrip(){
        assertEquals(List.of(),SnapshotCodec.decode(1,SnapshotCodec.encode(List.of())));
        var entries=new ArrayList<ScopeEntry>();for(int n=0;n<50;n++)entries.add(entry(n));
        byte[] bytes=SnapshotCodec.encode(entries);assertEquals(3308,bytes.length);assertEquals(entries,SnapshotCodec.decode(1,bytes));
        assertThrows(UnsupportedOperationException.class,()->SnapshotCodec.decode(1,bytes).clear());
        assertArrayEquals(bytes,SnapshotCodec.encode(entries));
    }
    @Test void unknownSchemaMagicLengthCountAndTrailingBytesFailUnavailable(){
        byte[] valid=SnapshotCodec.encode(List.of(entry(1)));
        bad(2,valid);bad(1,null);bad(1,new byte[7]);bad(1,new byte[4097]);
        var bad=valid.clone();bad[0]=0;bad(1,bad);
        for(int count:new int[]{-1,51}){bad=valid.clone();ByteBuffer.wrap(bad).putInt(4,count);bad(1,bad);}
        bad(1,Arrays.copyOf(valid,valid.length-1));bad(1,Arrays.copyOf(valid,valid.length+1));
    }
    @Test void invalidEnumsAndRevisionCorruptionFail503RatherThanClient400(){
        byte[] valid=SnapshotCodec.encode(List.of(entry(1)));
        for(int offset:new int[]{72,73}){var bad=valid.clone();bad[offset]=9;bad(1,bad);}
        for(int offset:new int[]{40,48,56,64}){var bad=valid.clone();ByteBuffer.wrap(bad).putLong(offset,-1);bad(1,bad);}
        var bad=valid.clone();ByteBuffer.wrap(bad).putLong(64,0);bad(1,bad);
    }
    @Test void duplicateMembershipCollectionAndNonCanonicalOrderAreRejected(){
        var a=entry(1);var b=entry(2);
        assertThrows(KbFailure.class,()->SnapshotCodec.encode(List.of(b,a)));
        assertThrows(KbFailure.class,()->SnapshotCodec.encode(List.of(a,a)));
        var duplicateMembership=new ScopeEntry(a.membershipId(),b.collectionId(),1,1,1,State.ACTIVE,Status.ACTIVE);
        assertThrows(KbFailure.class,()->SnapshotCodec.encode(List.of(a,duplicateMembership)));
        var duplicateCollection=new ScopeEntry(b.membershipId(),a.collectionId(),1,1,1,State.ACTIVE,Status.ACTIVE);
        assertThrows(KbFailure.class,()->SnapshotCodec.encode(List.of(a,duplicateCollection)));
        assertThrows(KbFailure.class,()->SnapshotCodec.encode(null));
        assertThrows(KbFailure.class,()->SnapshotCodec.encode(Collections.nCopies(51,a)));
        assertThrows(KbFailure.class,()->SnapshotCodec.encode(Arrays.asList((ScopeEntry)null)));
        assertThrows(KbFailure.class,()->SnapshotCodec.encode(List.of(new ScopeEntry(a.membershipId(),a.collectionId(),1,1,0,State.ACTIVE,Status.ACTIVE))));
    }
    private void bad(int version,byte[] bytes){assertEquals(503,assertThrows(KbFailure.class,()->SnapshotCodec.decode(version,bytes)).status());}
}
