package org.documentknowledge.loggingclient;

import java.util.*;

/** Closed optional v1 context. No arbitrary text/map/Throwable is retained. */
public record LogDiagnostics(Integer status,Long remainingDeadlineMs,String errorCode,String sqlState,
        LogDiagnosticCatalog.Dependency dependency,Failure failure,Attributes attributes,Counts counts) {
    public record Frame(String symbol,String source,Long line) {
        public Frame {LogDiagnosticCatalog.text(symbol,256,"[A-Za-z0-9_.$<>-]+");if(source!=null)LogDiagnosticCatalog.relative(source);number(line);}
    }
    public record Failure(String exceptionClass,List<String> causeClasses,List<Frame> stackFrames) {
        public Failure {
            if(exceptionClass!=null)LogDiagnosticCatalog.text(exceptionClass,256,"[A-Za-z0-9_.$]+");
            causeClasses=List.copyOf(causeClasses);stackFrames=List.copyOf(stackFrames);
            LogDiagnosticCatalog.require(causeClasses.size()<=4 && stackFrames.size()<=32);
            causeClasses.forEach(name->LogDiagnosticCatalog.text(name,256,"[A-Za-z0-9_.$]+"));
        }
    }
    public record Attributes(Long bytes,Long queueDepth,Boolean cancelRequested) {
        public Attributes {number(bytes);number(queueDepth);}
    }
    public record Counts(long redactedFields,long truncatedFields,long unregisteredSymbols) {
        public static final Counts ZERO=new Counts(0,0,0);
        public Counts {number(redactedFields);number(truncatedFields);number(unregisteredSymbols);}
    }
    public LogDiagnostics {
        LogDiagnosticCatalog.require(status==null || (status>=100 && status<=599));number(remainingDeadlineMs);
        if(errorCode!=null)LogDiagnosticCatalog.text(errorCode,96,"[A-Z0-9_]+");
        if(sqlState!=null)LogDiagnosticCatalog.text(sqlState,5,"[A-Z0-9]{5}");
        counts=counts==null?Counts.ZERO:counts;
    }
    static void number(Long value) {LogDiagnosticCatalog.require(value==null || (value>=0 && value<=LifecycleEvent.MAX_INTEGER));}
}
