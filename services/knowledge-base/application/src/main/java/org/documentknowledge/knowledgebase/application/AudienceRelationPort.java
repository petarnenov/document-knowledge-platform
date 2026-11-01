package org.documentknowledge.knowledgebase.application;

import org.documentknowledge.knowledgebase.domain.KbModels.*;

public interface AudienceRelationPort {
    KnowledgeAuthorityPort.Result matches(Context context,Selector selector);
}
