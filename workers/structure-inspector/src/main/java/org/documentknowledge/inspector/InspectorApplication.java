package org.documentknowledge.inspector;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.net.ssl.*;
import org.documentknowledge.inspector.diagnostics.InspectorDiagnostics;
import org.documentknowledge.inspector.diagnostics.StdoutDiagnostics;
import org.documentknowledge.inspector.diagnostics.InspectorDiagnostics.*;

public final class InspectorApplication {
    private InspectorApplication() { }
    public static void main(String[] ignored) {
        try {
            launch(System.getenv(),new InetSocketAddress("0.0.0.0",8443),InspectorApplication.class.getResourceAsStream(StdoutDiagnostics.RESOURCE),System.out,System.err,hook->Runtime.getRuntime().addShutdownHook(new Thread(hook)));
        }catch(Exception unavailable) {System.err.println("inspector unavailable");System.exit(1);}
    }
    static InspectorServer launch(Map<String,String> env,InetSocketAddress address,InputStream manifest,OutputStream target,PrintStream errors,java.util.function.Consumer<Runnable> shutdown) throws Exception {
        var output=openLogging(env,manifest,target,errors);
        InspectorDiagnostics diagnostics=output==null?InspectorDiagnostics.NOOP:InspectorDiagnostics.safe(output);
        InspectorServer server=null;
        try {
            java.util.logging.Logger.getLogger("org.apache.pdfbox").setLevel(java.util.logging.Level.OFF);
            server=start(env,address,diagnostics);var running=server;
            shutdown.accept(()->stop(running,diagnostics,output));return server;
        }catch(Exception unavailable) {if(server!=null)stop(server,diagnostics,output);else if(output!=null)output.close();throw unavailable;}
    }
    static StdoutDiagnostics openLogging(Map<String,String> env,InputStream manifest,OutputStream output,PrintStream errors) {
        try {
            return StdoutDiagnostics.open(manifest,env.get("DKP_INSPECTOR_LOG_MANIFEST_SHA256"),env.get("DKP_INSPECTOR_LOG_RUN_ID"),env.get("DKP_INSPECTOR_LOG_INSTANCE_ID"),output);
        } catch(IOException invalid) {errors.println("inspector diagnostics unavailable");return null;}
    }
    static InspectorServer start(Map<String,String> env,InetSocketAddress address,InspectorDiagnostics diagnostics) throws Exception {
        var observation=InspectorDiagnostics.safe(diagnostics).started(Context.fresh(),Phase.STARTUP);
        InspectorServer server=null;
        try {
            server=configure(env,address,diagnostics);server.start();observation.completed(Outcome.SUCCEEDED,Facts.EMPTY);return server;
        } catch(Exception unavailable) {
            observation.failure(unavailable);observation.completed(Outcome.FAILED,new Facts(null,null,0,false,InspectorDiagnostics.Error.OPERATION_FAILED));
            if(server!=null)server.close();throw unavailable;
        }
    }
    static void stop(InspectorServer server,InspectorDiagnostics diagnostics,StdoutDiagnostics output) {
        var observation=InspectorDiagnostics.safe(diagnostics).started(Context.fresh(),Phase.SHUTDOWN);
        try {server.close();observation.completed(Outcome.SUCCEEDED,new Facts(null,null,0,true,null));}
        catch(RuntimeException unavailable) {observation.failure(unavailable);observation.completed(Outcome.FAILED,new Facts(null,null,0,false,InspectorDiagnostics.Error.CLEANUP_FAILED));throw unavailable;}
        finally {if(output!=null)output.close();}
    }
    static InspectorServer configure(Map<String,String> env,InetSocketAddress address) throws Exception {
        return configure(env,address,InspectorDiagnostics.NOOP);
    }
    static InspectorServer configure(Map<String,String> env,InetSocketAddress address,InspectorDiagnostics diagnostics) throws Exception {
            // Pinned JDK25 server limits also cover stalled headers/TLS connections before routing.
            System.setProperty("jdk.httpserver.maxConnections","10");
            System.setProperty("sun.net.httpserver.maxReqHeaders","32");
            System.setProperty("sun.net.httpserver.maxReqHeaderSize","8192");
            System.setProperty("sun.net.httpserver.maxReqTime","60");
            System.setProperty("sun.net.httpserver.maxRspTime","60");
            System.setProperty("sun.net.httpserver.timerMillis","1000");
            System.setProperty("sun.net.httpserver.idleInterval","30");
            char[] password=new String(PrivateFiles.read(path(env,"DKP_INSPECTOR_KEYSTORE_PASSWORD_PATH"),4096),StandardCharsets.UTF_8).strip().toCharArray();
            KeyStore store=KeyStore.getInstance("PKCS12");store.load(new ByteArrayInputStream(PrivateFiles.read(path(env,"DKP_INSPECTOR_KEYSTORE_PATH"),1_048_576)),password);
            var keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(store,password);Arrays.fill(password,'\0');
            SSLContext tls=SSLContext.getInstance("TLS");tls.init(keys.getKeyManagers(),null,null);
            byte[] secret=Base64.getDecoder().decode(new String(PrivateFiles.read(path(env,"DKP_INSPECTOR_SERVICE_KEY_PATH"),128),StandardCharsets.US_ASCII).strip());
            Path temp=path(env,"DKP_INSPECTOR_TEMP_DIR");
            if(Files.isSymbolicLink(temp) || !Files.isDirectory(temp) || !Files.isWritable(temp) || Files.getPosixFilePermissions(temp).stream().anyMatch(p->p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")))throw new IOException("unsafe temporary directory");
            // Only this private inspector tmpfs is disposable; never follow stale symlinks.
            try(var children=Files.list(temp)) {
                for(Path child:children.toList()) {
                    if(!child.getFileName().toString().startsWith("inspection-"))throw new IOException("unexpected temporary content");
                    try(var paths=Files.walk(child)) { for(Path item:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(item); }
                }
            }
            System.setProperty("java.io.tmpdir",temp.toString());
            return new InspectorServer(address,tls,secret,new StructureInspector(temp,diagnostics),diagnostics);
    }
    private static Path path(Map<String,String> env,String key) {String value=env.get(key);if(value==null || value.isBlank())throw new IllegalArgumentException("configuration");return Path.of(value);}
}
