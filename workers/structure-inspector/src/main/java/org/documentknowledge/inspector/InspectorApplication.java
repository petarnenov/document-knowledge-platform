package org.documentknowledge.inspector;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.net.ssl.*;

public final class InspectorApplication {
    private InspectorApplication() { }
    public static void main(String[] ignored) {
        try {
            java.util.logging.Logger.getLogger("org.apache.pdfbox").setLevel(java.util.logging.Level.OFF);
            var server=configure(System.getenv(),new InetSocketAddress("0.0.0.0",8443));
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));server.start();
        }catch(Exception unavailable) {System.err.println("inspector unavailable");System.exit(1);}
    }
    static InspectorServer configure(Map<String,String> env,InetSocketAddress address) throws Exception {
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
            return new InspectorServer(address,tls,secret,new StructureInspector(temp));
    }
    private static Path path(Map<String,String> env,String key) {String value=env.get(key);if(value==null || value.isBlank())throw new IllegalArgumentException("configuration");return Path.of(value);}
}
