package org.documentknowledge.inspector;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import javax.net.ssl.*;

final class TlsFixture {
    final SSLContext server,client;
    TlsFixture(Path directory) throws Exception {
        Path store=directory.resolve("server.p12");String password="synthetic-test-only";
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),"-genkeypair","-alias","inspector","-keyalg","EC","-groupname","secp256r1","-validity","2","-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-storetype","PKCS12","-keystore",store.toString(),"-storepass",password,"-keypass",password,"-noprompt").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if(!process.waitFor(20,java.util.concurrent.TimeUnit.SECONDS)||process.exitValue()!=0)throw new IOException("Test TLS setup");
        KeyStore keys=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(store)){keys.load(in,password.toCharArray());}
        var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,password.toCharArray());server=SSLContext.getInstance("TLS");server.init(km.getKeyManagers(),null,null);
        KeyStore trust=KeyStore.getInstance("PKCS12");trust.load(null,null);trust.setCertificateEntry("inspector",keys.getCertificate("inspector"));var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);client=SSLContext.getInstance("TLS");client.init(null,tm.getTrustManagers(),null);
    }
}
