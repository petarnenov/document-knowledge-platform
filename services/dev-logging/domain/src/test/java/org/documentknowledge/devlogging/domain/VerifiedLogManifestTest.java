package org.documentknowledge.devlogging.domain;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.documentknowledge.observability.BuildIdentity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerifiedLogManifestTest {
    final BuildIdentity build = new BuildIdentity(UUID.randomUUID(),"runtime-tools","a".repeat(40),"b".repeat(64));
    @Test void keepsExactImmutableReviewedBytesAndFullCapacityCharge() {
        byte[] bytes="{}".getBytes(StandardCharsets.UTF_8); String digest=VerifiedLogManifest.sha256(bytes);
        var manifest=new VerifiedLogManifest(build,digest,"c".repeat(64),bytes);
        bytes[0]=0;var copy=manifest.bytes();copy[0]=1;
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8),manifest.bytes());
        assertEquals(digest,manifest.digest());assertEquals(build,manifest.build());
        assertEquals("c".repeat(64),manifest.vocabularyDigest());assertEquals(1026,manifest.reservedBytes());
    }
    @Test void rejectsUnboundDigestsAndByteBounds() {
        byte[] bytes={1};String digest=VerifiedLogManifest.sha256(bytes);
        for(String bad:new String[]{null,"A".repeat(64),"c".repeat(64)})
            assertThrows(LogFailure.class,()->new VerifiedLogManifest(build,bad,"c".repeat(64),bytes));
        for(String bad:new String[]{null,"invalid"})
            assertThrows(LogFailure.class,()->new VerifiedLogManifest(build,digest,bad,bytes));
        for(byte[] bad:new byte[][]{null,new byte[0],new byte[VerifiedLogManifest.MAX_BYTES+1]})
            assertThrows(LogFailure.class,()->new VerifiedLogManifest(build,digest,"c".repeat(64),bad));
        assertThrows(NullPointerException.class,()->new VerifiedLogManifest(null,digest,"c".repeat(64),bytes));
    }
}
