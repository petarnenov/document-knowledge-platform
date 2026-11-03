package org.documentknowledge.loggingclient;

import java.util.*;

/** Outward adapter: captures only registered symbols, never exception text or authority. */
public final class LogExceptionCapture {
    public record Captured(LogDiagnostics.Failure failure,LogDiagnostics.Counts counts) { }
    private LogExceptionCapture() { }
    public static Captured capture(Throwable failure,LogDiagnosticCatalog catalog) {
        Objects.requireNonNull(failure);Objects.requireNonNull(catalog);
        long unknown=0,redacted=1,truncated=0;
        String root=failure.getClass().getName();
        if(!catalog.exceptionClasses().contains(root)) {root=null;unknown++;}
        var causes=new ArrayList<String>();
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());seen.add(failure);
        Throwable cause=failure.getCause();
        for(int index=0;index<4 && cause!=null;index++) {
            if(!seen.add(cause))break;
            redacted++;String name=cause.getClass().getName();
            if(catalog.exceptionClasses().contains(name))causes.add(name);else unknown++;
            cause=cause.getCause();
        }
        if(cause!=null)truncated++;
        var frames=new ArrayList<LogDiagnostics.Frame>();
        StackTraceElement[] actual=failure.getStackTrace();truncated+=Math.max(0,actual.length-32);
        for(int index=0;index<Math.min(actual.length,32);index++) {
            var item=actual[index];var registered=catalog.frame(item.getClassName()+"."+item.getMethodName());
            if(registered==null) {unknown++;continue;}
            String source=null;
            if(!item.isNativeMethod() && item.getFileName()!=null) {
                String expected=registered.source().substring(registered.source().lastIndexOf('/')+1);
                if(!expected.equals(item.getFileName())) {unknown++;continue;}
                source=registered.source();
            }
            frames.add(new LogDiagnostics.Frame(registered.symbol(),source,source==null || item.getLineNumber()<0?null:(long)item.getLineNumber()));
        }
        return new Captured(new LogDiagnostics.Failure(root,causes,frames),new LogDiagnostics.Counts(redacted,truncated,unknown));
    }
}
