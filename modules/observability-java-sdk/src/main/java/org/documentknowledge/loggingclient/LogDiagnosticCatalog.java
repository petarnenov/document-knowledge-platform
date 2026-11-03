package org.documentknowledge.loggingclient;

import java.util.*;

/** Immutable original-manifest vocabulary. It confers no caller or tenant authority. */
public final class LogDiagnosticCatalog {
    public enum Provenance { VERIFIED, UNKNOWN }
    public record Dependency(String name,String version,String imageDigest,Provenance provenance) {
        public Dependency {
            text(name,128,"[A-Za-z][A-Za-z0-9_.-]*");text(version,128,"[\\s\\S]+");
            if(imageDigest!=null)text(imageDigest,71,"sha256:[0-9a-f]{64}");Objects.requireNonNull(provenance);
        }
    }
    public record Frame(String symbol,String source) {
        public Frame {text(symbol,256,"[A-Za-z0-9_.$<>-]+");relative(source);}
    }
    public static final LogDiagnosticCatalog EMPTY=new LogDiagnosticCatalog(Set.of(),Map.of(),Set.of(),Set.of());
    private final Set<String> errorCodes,exceptionClasses;
    private final Map<String,Dependency> dependencies;
    private final Map<String,Frame> frames;

    public LogDiagnosticCatalog(Set<String> errorCodes,Map<String,Dependency> dependencies,
                                Set<String> exceptionClasses,Set<Frame> frames) {
        this.errorCodes=Set.copyOf(errorCodes);this.dependencies=Map.copyOf(dependencies);
        this.exceptionClasses=Set.copyOf(exceptionClasses);
        require(this.errorCodes.size()<=1024 && this.dependencies.size()<=256 && this.exceptionClasses.size()<=8192 && frames.size()<=32768);
        this.errorCodes.forEach(code->text(code,96,"[A-Z0-9_]+"));
        this.exceptionClasses.forEach(code->text(code,256,"[A-Za-z0-9_.$]+"));
        this.dependencies.forEach((name,value)->require(name.equals(value.name())));
        var indexed=new HashMap<String,Frame>();
        for(var frame:frames)require(indexed.put(frame.symbol(),frame)==null);
        this.frames=Map.copyOf(indexed);
    }
    public Set<String> errorCodes() {return errorCodes;}
    public Map<String,Dependency> dependencies() {return dependencies;}
    public Set<String> exceptionClasses() {return exceptionClasses;}
    public Collection<Frame> frames() {return frames.values();}
    public Frame frame(String symbol) {return frames.get(symbol);}

    /** Only a parsed original manifest is a catalog source; captured errors never call this. */
    static LogDiagnosticCatalog fromManifest(Map<?,?> catalog) {
        require(catalog.keySet().equals(Set.of("events","phases","errorCodes","dependencies","exceptionClasses","frames")));
        var errors=strings(catalog.get("errorCodes"),1024);
        var classes=strings(catalog.get("exceptionClasses"),8192);
        var dependencies=new HashMap<String,Dependency>();
        for(Object item:list(catalog.get("dependencies"),256)) {
            require(item instanceof Map);var row=(Map<?,?>)item;
            require(row.keySet().containsAll(Set.of("name","version","vendorBuildProvenance"))
                && Set.of("name","version","vendorBuildProvenance","imageDigest").containsAll(row.keySet()));
            var dependency=new Dependency((String)row.get("name"),(String)row.get("version"),(String)row.get("imageDigest"),
                Provenance.valueOf((String)row.get("vendorBuildProvenance")));
            require(dependencies.put(dependency.name(),dependency)==null);
        }
        var frames=new HashSet<Frame>();
        for(Object item:list(catalog.get("frames"),32768)) {
            require(item instanceof Map);var row=(Map<?,?>)item;require(row.keySet().equals(Set.of("symbol","source")));
            require(frames.add(new Frame((String)row.get("symbol"),(String)row.get("source"))));
        }
        return new LogDiagnosticCatalog(errors,dependencies,classes,frames);
    }
    private static Set<String> strings(Object value,int maximum) {
        var result=new HashSet<String>();for(Object item:list(value,maximum))require(item instanceof String && result.add((String)item));
        return result;
    }
    private static List<?> list(Object value,int maximum) {require(value instanceof List<?> && ((List<?>)value).size()<=maximum);return (List<?>)value;}
    static void require(boolean value) {if(!value)throw new IllegalArgumentException("INVALID_LOG_DIAGNOSTICS");}
    static void text(String value,int maximum,String pattern) {require(value!=null && !value.isEmpty() && value.codePointCount(0,value.length())<=maximum && value.matches(pattern));}
    static void relative(String value) {
        text(value,256,"[A-Za-z0-9_./$-]+");require(!value.startsWith("/")
            && Arrays.stream(value.split("/",-1)).noneMatch(part->part.isEmpty() || part.equals(".") || part.equals("..")));
    }
}
