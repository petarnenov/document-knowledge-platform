package org.documentknowledge.documentmanagement.application.sharing;

import java.util.*;
import java.util.function.Function;
import org.documentknowledge.documentmanagement.application.sharing.SharingModels.Document;

/** Read-only domain work under sorted owning DM row locks; callback may read LOCAL authority only. */
public interface DocumentShareStore {
    <T>T locked(String tenant,List<UUID> documents,SharingDeadline deadline,Function<List<Document>,T> work);
}
