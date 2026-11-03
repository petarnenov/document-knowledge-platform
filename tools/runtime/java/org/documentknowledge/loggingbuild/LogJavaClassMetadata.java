package org.documentknowledge.loggingbuild;

import java.io.*;
import java.lang.classfile.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Build-only metadata reader. Never loads or initializes the inspected application classes. */
public final class LogJavaClassMetadata {
    private static final long CLASS_BYTES=8L*1024*1024, TOTAL_BYTES=128L*1024*1024;
    private static final int INPUT_BYTES=2*1024*1024, OUTPUT_CHARS=16*1024*1024;
    private LogJavaClassMetadata() { }

    public static void main(String[] args) {if(execute(args,System.out)!=0)System.exit(1);}

    /** Fixed refusal output deliberately excludes parser messages, paths and input bytes. */
    public static int execute(String[] args,PrintStream output) {
        try {
            require(args.length==1);output.println(read(Path.of(args[0])));return 0;
        } catch(Exception invalid) {
            output.println("{\"result\":\"FAIL\",\"code\":\"LOG_JAVA_METADATA_INVALID\"}");return 1;
        }
    }

    public static String read(Path index) throws IOException {
        byte[] input=bytes(index,INPUT_BYTES);
        var decoder=StandardCharsets.UTF_8.newDecoder();
        String text=decoder.decode(java.nio.ByteBuffer.wrap(input)).toString();
        var names=text.lines().toList();require(!names.isEmpty() && names.size()<=4096);
        var paths=new ArrayList<Path>();var seen=new HashSet<Path>();long total=0;
        for(String name:names) {
            require(!name.isEmpty() && name.indexOf('\t')<0 && name.indexOf('\r')<0);
            Path path=selectedPath(name);var attributes=attributes(path);require(seen.add(path));
            require(attributes.size()>0 && attributes.size()<=CLASS_BYTES);
            total+=attributes.size();require(total<=TOTAL_BYTES);paths.add(path);
        }
        var rows=new ArrayList<String>();long omitted=0,missingSource=0;int methods=0;
        for(int position=0;position<paths.size();position++) {
            var model=ClassFile.of().parse(bytes(paths.get(position),CLASS_BYTES));
            require(model.majorVersion()>=52 && model.majorVersion()<=69 && model.minorVersion()==0);
            if(model.isModuleInfo()) {omitted++;continue;}
            String name=model.thisClass().asInternalName().replace('/','.');
            String parent=model.superclass().map(value->value.asInternalName().replace('/','.')).orElse(null);
            if(!binaryName(name) || (parent!=null && !binaryName(parent))) {omitted++;continue;}
            String source=model.findAttribute(Attributes.sourceFile()).map(value->value.sourceFile().stringValue()).orElse(null);
            if(source==null || source.length()>256 || !source.matches("[A-Za-z0-9_$-]+\\.java")) {source=null;missingSource++;}
            var symbols=new TreeSet<String>();
            for(var method:model.methods()) {
                String symbol=method.methodName().stringValue();
                if(symbol.isEmpty() || symbol.length()>256 || !symbol.matches("[A-Za-z0-9_.$<>-]+")) {omitted++;continue;}
                symbols.add(symbol);
            }
            methods+=symbols.size();require(methods<=32768);
            rows.add("{\"inputIndex\":"+position+",\"name\":"+quoted(name)+",\"superclass\":"+quoted(parent)
                +",\"sourceFile\":"+quoted(source)+",\"methods\":["+String.join(",",symbols.stream().map(LogJavaClassMetadata::quoted).toList())+"]}");
        }
        String result="{\"schemaVersion\":1,\"classes\":["+String.join(",",rows)+"],\"unsupportedSymbols\":"+omitted+",\"missingSources\":"+missingSource+"}";
        require(result.length()<=OUTPUT_CHARS);return result;
    }

    private static boolean binaryName(String value) {
        return value.length()<=256 && value.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    }
    // Every non-null string has already passed an ASCII grammar excluding quotes/control characters.
    private static String quoted(String value) {return value==null?"null":"\""+value+"\"";}
    private static Path selectedPath(String name) throws IOException {
        if(name.startsWith("jdk:")) {
            String binary=name.substring(4);require(binaryName(binary));
            Path modules=FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules");
            Path found=null;int count=0;
            try(var entries=Files.newDirectoryStream(modules)) {
                for(Path module:entries) {
                    require(++count<=128);Path candidate=module.resolve(binary.replace('.','/')+".class");
                    if(Files.isRegularFile(candidate)) {require(found==null);found=candidate;}
                }
            }
            require(found!=null);return found;
        }
        if(name.startsWith("jrt:")) {
            require(name.matches("jrt:/[a-z][a-z0-9.]*/(?:[A-Za-z0-9_$]+/)*[A-Za-z0-9_$]+\\.class"));
            return Path.of(URI.create(name));
        }
        return Path.of(name);
    }
    private static BasicFileAttributes attributes(Path path) throws IOException {
        require(path.isAbsolute() && path.toRealPath().equals(path));
        var attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        require(attributes.isRegularFile());return attributes;
    }
    private static byte[] bytes(Path path,long maximum) throws IOException {
        var before=attributes(path);require(before.size()>0 && before.size()<=maximum);
        byte[] value;
        // jrt is the pinned JVM's immutable module image, not an operating-system symlink path.
        boolean jrt=path.getFileSystem().provider().getScheme().equals("jrt");
        try(var input=jrt?Files.newInputStream(path,StandardOpenOption.READ)
                :Files.newInputStream(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            value=input.readNBytes(Math.toIntExact(maximum+1));
        }
        var after=attributes(path);
        require(value.length==before.size() && before.size()==after.size() && Objects.equals(before.fileKey(),after.fileKey())
            && before.lastModifiedTime().equals(after.lastModifiedTime()));return value;
    }
    private static void require(boolean condition) {if(!condition)throw new IllegalArgumentException("LOG_JAVA_METADATA_INVALID");}
}
