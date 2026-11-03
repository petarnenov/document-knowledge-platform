package org.documentknowledge.inspector.diagnostics;

import java.util.*;

/** Capture registered original symbols synchronously; no Throwable or raw text leaves this method. */
final class WorkerExceptionCapture {
    record Captured(Map<String,Object> failure,Map<String,Object> counts) { }
    private WorkerExceptionCapture() { }
    static Captured capture(Throwable failure,WorkerLogManifest manifest) {
        Objects.requireNonNull(failure);long unknown=0,redacted=1,truncated=0;
        var result=new HashMap<String,Object>();String root=failure.getClass().getName();
        if(manifest.exceptions.contains(root))result.put("exceptionClass",root);else unknown++;
        var causes=new ArrayList<String>();Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());seen.add(failure);
        Throwable cause=failure.getCause();
        for(int index=0;index<4 && cause!=null;index++) {
            if(!seen.add(cause))break;
            redacted++;String name=cause.getClass().getName();
            if(manifest.exceptions.contains(name))causes.add(name);else unknown++;
            cause=cause.getCause();
        }
        if(cause!=null)truncated++;
        var frames=new ArrayList<Map<String,Object>>();StackTraceElement[] actual=failure.getStackTrace();
        truncated+=Math.max(0,actual.length-32);
        for(int index=0;index<Math.min(actual.length,32);index++) {
            var item=actual[index];String symbol=item.getClassName()+"."+item.getMethodName(),source=manifest.frames.get(symbol);
            if(source==null){unknown++;continue;}
            var frame=new HashMap<String,Object>();frame.put("symbol",symbol);
            if(!item.isNativeMethod() && item.getFileName()!=null) {
                if(!source.substring(source.lastIndexOf('/')+1).equals(item.getFileName())){unknown++;continue;}
                frame.put("source",source);if(item.getLineNumber()>=0)frame.put("line",(long)item.getLineNumber());
            }
            frames.add(Map.copyOf(frame));
        }
        result.put("causeClasses",List.copyOf(causes));result.put("stackFrames",List.copyOf(frames));
        return new Captured(Map.copyOf(result),Map.of("redactedFields",redacted,"truncatedFields",truncated,"unregisteredSymbols",unknown));
    }
}
