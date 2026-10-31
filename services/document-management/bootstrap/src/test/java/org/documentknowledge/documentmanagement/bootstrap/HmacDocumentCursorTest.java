package org.documentknowledge.documentmanagement.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.application.PagePosition;
import org.documentknowledge.identity.Actor;
import org.junit.jupiter.api.Test;

class HmacDocumentCursorTest {
    private final byte[] key = new byte[32];
    private final Actor actor = new Actor("https://issuer.example/realms/test", "subject-a");
    private final PagePosition position = new PagePosition(Instant.parse("2026-10-31T12:30:00.123456789Z"), UUID.randomUUID());
    private final HmacDocumentCursor cursors = new HmacDocumentCursor(key);

    @Test void roundTripPreservesNanosecondsAndUuidWithoutExposingActor() {
        String cursor = cursors.encode(position, actor, "tenant-a");
        assertEquals(124, cursor.length());
        assertFalse(cursor.contains("="));
        assertEquals(position, cursors.decode(cursor, actor, "tenant-a"));
        assertNull(cursors.decode(null, actor, "tenant-a"));
    }

    @Test void actorIssuerSubjectAndTenantCannotReuseAnotherScope() {
        String cursor = cursors.encode(position, actor, "tenant-a");
        invalid(() -> cursors.decode(cursor, new Actor("https://other.example", actor.subject()), "tenant-a"));
        invalid(() -> cursors.decode(cursor, new Actor(actor.issuer(), "subject-b"), "tenant-a"));
        invalid(() -> cursors.decode(cursor, actor, "tenant-b"));
        invalid(() -> cursors.decode(cursor, actor, ""));
        invalid(() -> cursors.decode(cursor, actor, null));
        invalid(() -> cursors.decode(cursor, actor, "x".repeat(257)));
    }

    @Test void authenticatedBindingIsNotAmbiguousAcrossComponentBoundaries() {
        Actor first = new Actor("https://issuer.example/a", "bc");
        Actor second = new Actor("https://issuer.example/ab", "c");
        String cursor = cursors.encode(position, first, "tenant-a");
        invalid(() -> cursors.decode(cursor, second, "tenant-a"));
    }

    @Test void rotationAndCallerMutationCannotKeepAnOldKeyValid() {
        byte[] supplied = new byte[32];
        HmacDocumentCursor before = new HmacDocumentCursor(supplied);
        String cursor = before.encode(position, actor, "tenant-a");
        supplied[0] = 1;
        assertEquals(position, before.decode(cursor, actor, "tenant-a"));
        HmacDocumentCursor after = new HmacDocumentCursor(supplied);
        invalid(() -> after.decode(cursor, actor, "tenant-a"));
    }

    @Test void malformedOversizedTruncatedOrModifiedTokensAreRejected() {
        for (String value : new String[] {"", " ", "!", "A", "A".repeat(2049), "AA", "AA=="}) {
            invalid(() -> cursors.decode(value, actor, "tenant-a"));
        }
        byte[] bytes = Base64.getUrlDecoder().decode(cursors.encode(position, actor, "tenant-a"));
        bytes[40] ^= 1;
        invalid(() -> cursors.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), actor, "tenant-a"));
    }

    @Test void evenAuthenticatedMalformedVersionsAndInstantsAreRejected() throws Exception {
        byte[] valid = Base64.getUrlDecoder().decode(cursors.encode(position, actor, "tenant-a"));
        byte[] wrongVersion = valid.clone();
        wrongVersion[0] = 2;
        invalid(() -> cursors.decode(authenticate(wrongVersion), actor, "tenant-a"));
        for (int nanos : new int[] {-1, 1_000_000_000}) {
            byte[] invalidTime = valid.clone();
            ByteBuffer.wrap(invalidTime).putInt(41, nanos);
            invalid(() -> cursors.decode(authenticate(invalidTime), actor, "tenant-a"));
        }
        byte[] outOfRange = valid.clone();
        ByteBuffer.wrap(outOfRange).putLong(33, Long.MAX_VALUE);
        invalid(() -> cursors.decode(authenticate(outOfRange), actor, "tenant-a"));
    }

    @Test void invalidKeysAndMissingPositionsCannotProduceCursors() {
        for (byte[] invalid : new byte[][] {null, new byte[0], new byte[31], new byte[65]}) {
            assertThrows(IllegalArgumentException.class, () -> new HmacDocumentCursor(invalid));
        }
        assertThrows(NullPointerException.class, () -> cursors.encode(null, actor, "tenant-a"));
        assertThrows(NullPointerException.class, () -> cursors.encode(new PagePosition(null, UUID.randomUUID()), actor, "tenant-a"));
        assertThrows(NullPointerException.class, () -> cursors.encode(new PagePosition(Instant.EPOCH, null), actor, "tenant-a"));
    }

    private String authenticate(byte[] bytes) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] signed = mac.doFinal(Arrays.copyOf(bytes, 61));
            System.arraycopy(signed, 0, bytes, 61, 32);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static void invalid(Runnable operation) {
        DmFailure failure = assertThrows(DmFailure.class, operation::run);
        assertEquals(400, failure.status());
        assertEquals("INVALID_CURSOR", failure.code());
    }
}
