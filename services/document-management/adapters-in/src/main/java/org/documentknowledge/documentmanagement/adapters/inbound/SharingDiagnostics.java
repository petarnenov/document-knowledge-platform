package org.documentknowledge.documentmanagement.adapters.inbound;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.documentknowledge.documentmanagement.application.DmFailure;

/** Private finite sharing diagnostics; never renders exceptions or request/peer values. */
final class SharingDiagnostics {
    enum Stage { CONTROLLER_FAILURE,FILTER_RESPONSE,FILTER_FAILURE }
    enum Kind { NOT_FOUND,UNAVAILABLE,OTHER,TIMEOUT,CANCELLED,DOMAIN_FAILURE,UNEXPECTED }
    private SharingDiagnostics() { }
    static void controller(DmFailure failure){status(Stage.CONTROLLER_FAILURE,failure.status());}
    static void unexpected(){emit(Stage.CONTROLLER_FAILURE,Kind.UNEXPECTED);}
    static void response(int status){if(status!=200)status(Stage.FILTER_RESPONSE,status);}
    static void failure(Throwable failure){
        Kind kind=failure instanceof TimeoutException?Kind.TIMEOUT:failure instanceof CancellationException?Kind.CANCELLED:failure instanceof DmFailure?Kind.DOMAIN_FAILURE:Kind.UNEXPECTED;
        emit(Stage.FILTER_FAILURE,kind);
    }
    private static void status(Stage stage,int status){emit(stage,switch(status){case 404->Kind.NOT_FOUND;case 503->Kind.UNAVAILABLE;default->Kind.OTHER;});}
    private static void emit(Stage stage,Kind kind){emit(stage,kind,line->System.getLogger("org.documentknowledge.documentmanagement.sharing").log(System.Logger.Level.WARNING,line));}
    static void emit(Stage stage,Kind kind,Consumer<String> sink){
        try{sink.accept("dm_sharing stage="+stage.name()+" category="+kind.name());}catch(RuntimeException ignored){/* Diagnostics cannot replace the established response or failure. */}
    }
}
