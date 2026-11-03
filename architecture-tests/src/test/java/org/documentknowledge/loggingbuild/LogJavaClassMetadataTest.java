package org.documentknowledge.loggingbuild;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real javac output, not hand-authored expected compiler metadata. */
class LogJavaClassMetadataTest {
    @TempDir Path temporary;
    Path root;
    @BeforeEach void canonicalRoot() throws Exception {root=temporary.toRealPath();}

    Path compile(String filename,String source,String... options) throws Exception {
        Path input=root.resolve(filename);Files.writeString(input,source);
        var arguments=new ArrayList<>(List.of("-encoding","UTF-8","-d",root.toString()));
        arguments.addAll(List.of(options));arguments.add(input.toString());
        var errors=new ByteArrayOutputStream();
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,errors,errors,arguments.toArray(String[]::new)),errors.toString(StandardCharsets.UTF_8));
        return root.resolve(filename.replace(".java",".class"));
    }
    Path index(Path... paths) throws Exception {
        Path input=root.resolve("inputs.txt");Files.writeString(input,String.join("\n",Arrays.stream(paths).map(Path::toString).toList())+"\n");return input;
    }
    Path actualFailure(String... options) throws Exception {
        return compile("ActualFailure.java","""
            public class ActualFailure extends java.io.IOException {
                static { if (Boolean.getBoolean("dkp.metadata.must-not-initialize")) throw new AssertionError("Initialization forbidden"); }
                public void operation() throws java.io.IOException { throw new java.io.IOException("private-content-canary"); }
                public void operation(int attempt) {}
                static class Nested {}
            }
            """,options);
    }
    @Test void realClassSourceInnerOverloadsAndSuperclassWithoutInitializationOrConstantLeak() throws Exception {
        Path actual=actualFailure();Path input=index(actual,root.resolve("ActualFailure$Nested.class"));
        System.setProperty("dkp.metadata.must-not-initialize","true");
        try {
            String output=LogJavaClassMetadata.read(input);
            assertTrue(output.contains("\"name\":\"ActualFailure\",\"superclass\":\"java.io.IOException\",\"sourceFile\":\"ActualFailure.java\""));
            assertTrue(output.contains("\"name\":\"ActualFailure$Nested\""));
            assertEquals(1,output.split("operation",-1).length-1);assertFalse(output.contains("canary"));
            var stream=new ByteArrayOutputStream();assertEquals(0,LogJavaClassMetadata.execute(new String[]{input.toString()},new PrintStream(stream)));
            assertEquals(output+System.lineSeparator(),stream.toString(StandardCharsets.UTF_8));
        } finally {System.clearProperty("dkp.metadata.must-not-initialize");}
    }
    @Test void missingSourcesAndUnsupportedSymbolsAreVisible() throws Exception {
        Path actual=actualFailure("-g:none");String output=LogJavaClassMetadata.read(index(actual));
        assertTrue(output.contains("\"sourceFile\":null"));assertTrue(output.contains("\"missingSources\":1"));
        compile("Unicode.java","class Unicode { void μέθοδος() {} }");
        output=LogJavaClassMetadata.read(index(root.resolve("Unicode.class")));assertTrue(output.contains("\"unsupportedSymbols\":1"));
        compile("Ω.java","class Ω {} class Plain {} ");
        output=LogJavaClassMetadata.read(index(root.resolve("Ω.class"),root.resolve("Plain.class")));
        assertTrue(output.contains("\"unsupportedSymbols\":1"));assertTrue(output.contains("\"missingSources\":1"));
        assertFalse(output.contains("Ω"));
    }
    @Test void objectRootAndModuleMetadataAreHandledWithoutGuessing() throws Exception {
        Path object=root.resolve("Object.class");
        try(var input=Object.class.getResourceAsStream("Object.class")) {assertNotNull(input);Files.copy(input,object);}
        String output=LogJavaClassMetadata.read(index(object));assertTrue(output.contains("\"name\":\"java.lang.Object\",\"superclass\":null"));
        compile("module-info.java","module fixture.metadata {}");
        output=LogJavaClassMetadata.read(index(root.resolve("module-info.class")));
        assertTrue(output.contains("\"classes\":[]"));assertTrue(output.contains("\"unsupportedSymbols\":1"));
    }
    @Test void jdkBytesUseOnlyTheReadOnlyCurrentJvmModuleImage() throws Exception {
        Path input=root.resolve("jdk-inputs.txt");
        Files.writeString(input,"jrt:/java.base/java/lang/Object.class\n");
        String output=LogJavaClassMetadata.read(input);assertTrue(output.contains("\"name\":\"java.lang.Object\",\"superclass\":null"));
        Files.writeString(input,"jdk:java.sql.SQLException\n");
        assertTrue(LogJavaClassMetadata.read(input).contains("\"name\":\"java.sql.SQLException\""));
        for(String value:List.of("jdk:../private-canary","jdk:java.lang.DoesNotExist","jrt:/java.base/../../private-canary","jrt:/java.base/java/lang/Object.class?private-canary","https://localhost/private-canary")) {
            Files.writeString(input,value+"\n");assertThrows(Exception.class,()->LogJavaClassMetadata.read(input));
        }
    }
    @Test void malformedVersionsBytesPathsAndDuplicateInputsHaveFixedRefusal() throws Exception {
        Path actual=actualFailure();byte[] good=Files.readAllBytes(actual);
        for(byte[] bad:List.of(new byte[]{1,2,3},Arrays.copyOf(good,20))) {
            Files.write(actual,bad);assertThrows(Exception.class,()->LogJavaClassMetadata.read(index(actual)));
        }
        for(int version:List.of(51,70)) {
            byte[] bad=good.clone();bad[6]=0;bad[7]=(byte)version;Files.write(actual,bad);
            assertThrows(Exception.class,()->LogJavaClassMetadata.read(index(actual)));
        }
        Files.write(actual,good);assertThrows(Exception.class,()->LogJavaClassMetadata.read(index(actual,actual)));
        Path link=root.resolve("link.class");Files.createSymbolicLink(link,actual);
        assertThrows(Exception.class,()->LogJavaClassMetadata.read(index(link)));
        assertThrows(Exception.class,()->LogJavaClassMetadata.read(index(root)));
        for(String text:List.of("", "relative.class\n",actual+"\tprivate-canary\n")) {
            Path input=root.resolve("bad-index");Files.writeString(input,text);
            assertThrows(Exception.class,()->LogJavaClassMetadata.read(input));
        }
        var output=new ByteArrayOutputStream();
        assertEquals(1,LogJavaClassMetadata.execute(new String[]{"private-canary"},new PrintStream(output)));
        assertEquals("{\"result\":\"FAIL\",\"code\":\"LOG_JAVA_METADATA_INVALID\"}"+System.lineSeparator(),output.toString(StandardCharsets.UTF_8));
        assertEquals(1,LogJavaClassMetadata.execute(new String[]{},new PrintStream(OutputStream.nullOutputStream())));
    }
    @Test void ordinaryInputBoundsRefuseBeforeUnboundedRead() throws Exception {
        Path actual=actualFailure();Path input=root.resolve("inputs.txt");
        Files.writeString(input,(actual+"\n").repeat(4097));assertThrows(Exception.class,()->LogJavaClassMetadata.read(input));
        try(var file=new RandomAccessFile(input.toFile(),"rw")) {file.setLength(2L*1024*1024+1);}
        assertThrows(Exception.class,()->LogJavaClassMetadata.read(input));
        try(var file=new RandomAccessFile(actual.toFile(),"rw")) {file.setLength(8L*1024*1024+1);}
        assertThrows(Exception.class,()->LogJavaClassMetadata.read(index(actual)));
    }
}
