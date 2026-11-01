package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.net.URI;
import org.documentknowledge.browserbff.application.KnowledgeWire;

/** Retains the JDK absolute-URI parser at the technical boundary, with no I/O. */
@Factory
public final class KnowledgeWireFactory {
    @Singleton public KnowledgeWire wire(){
        return new KnowledgeWire(value->{
            try{return URI.create(value).isAbsolute();}
            catch(IllegalArgumentException invalid){return false;}
        });
    }
}
