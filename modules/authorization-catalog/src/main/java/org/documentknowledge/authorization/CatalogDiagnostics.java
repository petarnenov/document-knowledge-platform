package org.documentknowledge.authorization;

import java.io.IOException;
import java.util.function.Consumer;
import tools.jackson.core.JacksonException;

/** Finite private fallback kind; never logs source bytes, paths or exception rendering. */
final class CatalogDiagnostics {
    enum Kind { IO,JSON,INVALID,OTHER }
    private CatalogDiagnostics() { }
    static void failure(Exception failure){Kind kind=failure instanceof IOException?Kind.IO:failure instanceof JacksonException?Kind.JSON:failure instanceof IllegalArgumentException?Kind.INVALID:Kind.OTHER;emit(kind,line->System.getLogger("org.documentknowledge.authorization.catalog").log(System.Logger.Level.WARNING,line));}
    static void emit(Kind kind,Consumer<String> sink){try{sink.accept("grant_catalog_failure category="+kind.name());}catch(RuntimeException ignored){/* Diagnostics cannot change fail-closed authority. */}}
}
