package org.documentknowledge.knowledgebase.application;

import java.util.UUID;
import org.documentknowledge.knowledgebase.domain.KbModels.Context;

/** Adapter uses AEAD or unpredictable server references, never readable HMAC JSON. */
public interface MemberCursorPort {
    record Position(Context context,UUID collectionId,long fence,long collectionRevision,String candidateDigest,String eligibleDigest,UUID lastVisible) { }
    String encode(Position position);
    Position decode(String token);
}
