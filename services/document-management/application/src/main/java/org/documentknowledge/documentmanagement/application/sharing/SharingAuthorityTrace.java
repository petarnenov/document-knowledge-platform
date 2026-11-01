package org.documentknowledge.documentmanagement.application.sharing;

import java.util.function.Consumer;
import org.documentknowledge.identity.RequestScopedResourceAuthorityPort;

/** Finite private phase evidence; no identity, resources, source bytes or exception values. */
final class SharingAuthorityTrace {
    enum Stage { ADMITTED,FIRST_SNAPSHOT_READY,ROWS_LOCKED,FIRST_AUTHORIZED,LAST_SNAPSHOT_READY,LAST_AUTHORIZED,FINAL_CONTEXT_READY,SOURCE_FAILED,SOURCE_RETURNED }
    enum Kind { NONE,ALLOW,DENY,UNAVAILABLE,NULL }
    private SharingAuthorityTrace() { }
    static void event(Stage stage){emit(stage,Kind.NONE);}
    static void source(RequestScopedResourceAuthorityPort.Snapshot snapshot){emit(Stage.SOURCE_RETURNED,snapshot==null?Kind.NULL:switch(snapshot.route()){case ALLOW->Kind.ALLOW;case DENY->Kind.DENY;case UNAVAILABLE->Kind.UNAVAILABLE;});}
    private static void emit(Stage stage,Kind kind){emit(stage,kind,line->System.getLogger("org.documentknowledge.documentmanagement.sharing.authority").log(System.Logger.Level.WARNING,line));}
    static void emit(Stage stage,Kind kind,Consumer<String> sink){try{sink.accept("dm_sharing_authority stage="+stage.name()+" category="+kind.name());}catch(RuntimeException ignored){/* Diagnostics cannot replace established authorization or failure. */}}
}
