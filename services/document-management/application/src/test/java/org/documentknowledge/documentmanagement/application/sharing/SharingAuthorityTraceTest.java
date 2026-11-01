package org.documentknowledge.documentmanagement.application.sharing;

import java.util.*;
import java.util.logging.*;
import org.documentknowledge.identity.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharingAuthorityTraceTest {
    static final String PRIVATE="private-token tenant document SQL https://private.invalid/\nforged-log";
    static final class Capture implements AutoCloseable {
        final Logger logger=Logger.getLogger("org.documentknowledge.documentmanagement.sharing.authority");
        final List<String> lines=new ArrayList<>();final List<Throwable> thrown=new ArrayList<>();
        final boolean parents=logger.getUseParentHandlers();final Level level=logger.getLevel();
        final Handler handler=new Handler(){public void publish(LogRecord r){lines.add(r.getMessage());thrown.add(r.getThrown());}public void flush(){}public void close(){}};
        Capture(){logger.setUseParentHandlers(false);logger.setLevel(Level.ALL);logger.addHandler(handler);}
        public void close(){logger.removeHandler(handler);logger.setUseParentHandlers(parents);logger.setLevel(level);}
    }
    @Test void onlyClosedPhaseAndRouteNamesAreLogged(){
        try(var c=new Capture()){
            SharingAuthorityTrace.event(SharingAuthorityTrace.Stage.ADMITTED);
            for(var route:AuthorityDecision.values())SharingAuthorityTrace.source(new RequestScopedResourceAuthorityPort.Snapshot(new Actor("https://issuer.example","user"),"tenant-a","document.share-to-collection",List.of(PRIVATE),route,Map.of(PRIVATE,route),PRIVATE));
            SharingAuthorityTrace.source(null);
            assertEquals(List.of("dm_sharing_authority stage=ADMITTED category=NONE","dm_sharing_authority stage=SOURCE_RETURNED category=ALLOW","dm_sharing_authority stage=SOURCE_RETURNED category=DENY","dm_sharing_authority stage=SOURCE_RETURNED category=UNAVAILABLE","dm_sharing_authority stage=SOURCE_RETURNED category=NULL"),c.lines);
            assertTrue(c.thrown.stream().allMatch(Objects::isNull));
        }
    }
    @Test void failingSinkCannotChangeAuthorization(){
        var lines=new ArrayList<String>();SharingAuthorityTrace.emit(SharingAuthorityTrace.Stage.SOURCE_FAILED,SharingAuthorityTrace.Kind.NONE,lines::add);assertEquals(List.of("dm_sharing_authority stage=SOURCE_FAILED category=NONE"),lines);
        assertDoesNotThrow(()->SharingAuthorityTrace.emit(SharingAuthorityTrace.Stage.SOURCE_FAILED,SharingAuthorityTrace.Kind.NONE,line->{throw new IllegalStateException(PRIVATE);}));
        assertDoesNotThrow(()->SharingAuthorityTrace.emit(SharingAuthorityTrace.Stage.SOURCE_FAILED,SharingAuthorityTrace.Kind.NONE,null));
        assertDoesNotThrow(()->SharingAuthorityTrace.emit(null,SharingAuthorityTrace.Kind.NONE,lines::add));assertDoesNotThrow(()->SharingAuthorityTrace.emit(SharingAuthorityTrace.Stage.SOURCE_FAILED,null,lines::add));
    }
}
