package org.documentknowledge.documentmanagement.adapters.outbound.verification;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import tools.jackson.databind.json.JsonMapper;

/** Protocol unit fixtures only. These are never substituted for standard ClamAV acceptance. */
final class VerificationFixtures {
    static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-31T12:00:00Z"),ZoneOffset.UTC);
    static void definitions(Path dir) throws Exception {
        for(String name:List.of("bytecode","daily","main")) {
            String header="ClamAV-VDB:test build:27930:1:90:hash:signature:builder:"+CLOCK.instant().minusSeconds(60).getEpochSecond();
            Files.write(dir.resolve(name+".cvd"),Arrays.copyOf(String.format("%-512s",header).getBytes(StandardCharsets.US_ASCII),600));
        }
        manifest(dir,CLOCK.instant());
    }
    static void manifest(Path dir,Instant updated) throws Exception {
        StringBuilder canonical=new StringBuilder();
        for(String name:List.of("bytecode.cvd","daily.cvd","main.cvd"))canonical.append(StandardDefinitions.hex(StandardDefinitions.sha().digest(Files.readAllBytes(dir.resolve(name))))).append("  ").append(name).append('\n');
        String hash=StandardDefinitions.hex(StandardDefinitions.sha().digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        Files.writeString(dir.resolve("dkp-update.json"),JsonMapper.builder().build().writeValueAsString(Map.of("schemaVersion",1,"updatedAt",updated.toString(),"databaseVersion","27930","databaseDigest",hash)));
    }
    static final class FakeClamd implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0,8,InetAddress.getLoopbackAddress());
        final ExecutorService worker=Executors.newSingleThreadExecutor();
        final AtomicReference<byte[]> streamed=new AtomicReference<>();
        volatile String version="ClamAV 1.5.4/27930/test build",scan="stream: OK";
        volatile boolean partial;
        FakeClamd() throws IOException {worker.submit(()->{while(!server.isClosed())try(var socket=server.accept()){socket.setSoTimeout(2000);var input=new DataInputStream(socket.getInputStream());var command=new ByteArrayOutputStream();int b;while((b=input.read())>0 && command.size()<64)command.write(b);String type=command.toString(StandardCharsets.US_ASCII);String response;
            if(type.equals("zVERSION"))response=version;
            else if(type.equals("zINSTREAM")){var bytes=new ByteArrayOutputStream();int length;while((length=input.readInt())!=0){if(length<0||length>65536)throw new IOException("bad frame");byte[] frame=input.readNBytes(length);if(frame.length!=length)throw new EOFException();bytes.write(frame);}streamed.set(bytes.toByteArray());response=scan;}
            else response="UNKNOWN COMMAND";
            socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));if(!partial)socket.getOutputStream().write(0);socket.getOutputStream().flush();
        }catch(IOException ignored){if(server.isClosed())break;}});}
        InetSocketAddress endpoint(){return new InetSocketAddress(InetAddress.getLoopbackAddress(),server.getLocalPort());}
        public void close() throws Exception {server.close();worker.shutdownNow();worker.awaitTermination(3,TimeUnit.SECONDS);}
    }
}
