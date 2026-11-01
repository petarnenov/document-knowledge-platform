package org.documentknowledge.knowledgebase.application;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.documentknowledge.knowledgebase.domain.KbFailure;

/** Private failure diagnostics: no request values, throwable rendering or dynamic log fields. */
public final class KnowledgeDiagnostics {
    public enum Stage { FACTORY,CURRENT_AUTHORITY,CATALOG,SHARE_CONFIG,SHARE_HTTP,SHARE_ASSESS,SHARE_ELIGIBILITY,SQL_ACQUIRE,SQL_READ,SQL_WRITE,SQL_COMMIT,HTTP_WORK,FINAL_RESPONSE }
    public enum Failure { UNAVAILABLE,CANCELLED,TIMEOUT,IO,SQL,UNEXPECTED }
    public enum SqlState { NONE,CONNECTION,REFERENCE_23503,DUPLICATE_23505,CHECK_23514,READ_ONLY,ABORTED_TRANSACTION,SERIALIZATION,DEADLOCK,PERMISSION,CAPACITY,CANCELLED,SHUTDOWN,INTERNAL,OTHER }
    public record Event(Stage stage,Failure failure,SqlState sqlState) {
        public Event { Objects.requireNonNull(stage);Objects.requireNonNull(failure);Objects.requireNonNull(sqlState); }
        public String line(){return "kb_failure stage="+stage.name()+" category="+failure.name()+" sql_state="+sqlState.name();}
    }
    private KnowledgeDiagnostics() { }
    public static void failure(Stage stage,Throwable failure){try{emit(classify(stage,failure),KnowledgeDiagnostics::log);}catch(RuntimeException ignored){/* Even classification failure cannot replace the original failure. */}}
    public static void sql(Stage stage,String state){emit(new Event(stage,Failure.SQL,sqlState(state)),KnowledgeDiagnostics::log);}
    static void emit(Event event,Consumer<Event> sink){if(event!=null)try{sink.accept(event);}catch(RuntimeException ignored){/* Diagnostics cannot change the established failure. */}}
    static Event classify(Stage stage,Throwable problem){
        Throwable current=problem;
        for(int depth=0;depth<4&&current!=null;depth++){
            if(current instanceof KbFailure safe)return safe.status()==503?new Event(stage,Failure.UNAVAILABLE,SqlState.NONE):null;
            if(current instanceof InterruptedException)return new Event(stage,Failure.CANCELLED,SqlState.NONE);
            if(current instanceof TimeoutException)return new Event(stage,Failure.TIMEOUT,SqlState.NONE);
            if(current instanceof IOException)return new Event(stage,Failure.IO,SqlState.NONE);
            if(current.getCause()==current)break;
            current=current.getCause();
        }
        return new Event(stage,Failure.UNEXPECTED,SqlState.NONE);
    }
    static SqlState sqlState(String state){
        if(state==null)return SqlState.OTHER;
        return switch(state){
            case "08000","08001","08003","08006","08007","08P01"->SqlState.CONNECTION;
            case "23503"->SqlState.REFERENCE_23503;
            case "23505"->SqlState.DUPLICATE_23505;
            case "23514"->SqlState.CHECK_23514;
            case "25006"->SqlState.READ_ONLY;
            case "25P02"->SqlState.ABORTED_TRANSACTION;
            case "40001"->SqlState.SERIALIZATION;
            case "40P01"->SqlState.DEADLOCK;
            case "42501"->SqlState.PERMISSION;
            case "53300","53400"->SqlState.CAPACITY;
            case "57014"->SqlState.CANCELLED;
            case "57P01","57P02","57P03"->SqlState.SHUTDOWN;
            case "58000","XX000"->SqlState.INTERNAL;
            default->SqlState.OTHER;
        };
    }
    private static void log(Event event){System.getLogger("org.documentknowledge.knowledgebase.failure").log(System.Logger.Level.WARNING,event.line());}
}
