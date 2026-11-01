package org.documentknowledge.knowledgebase.application;
import java.util.UUID;
import org.documentknowledge.knowledgebase.domain.KbModels.Context;
public interface CollectionCursorPort {
    record Position(Context context,long fence,UUID lastVisible) { }
    String encode(Position position);
    Position decode(String token);
}
