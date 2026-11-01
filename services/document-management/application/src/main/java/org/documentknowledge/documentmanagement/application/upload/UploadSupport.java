package org.documentknowledge.documentmanagement.application.upload;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.*;
import java.util.*;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectKey;
final class UploadSupport {
 private UploadSupport() { }
 static String fingerprint(Object... values){try {var digest=MessageDigest.getInstance("SHA-256");for(Object value:values){byte[] b=String.valueOf(value).getBytes(StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(b.length).array());digest.update(b);}return HexFormat.of().formatHex(digest.digest());}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 static CommandKey command(DocumentCaller caller,String operation,String resource,String key){if(key==null||!key.matches("[!-~]{1,128}"))throw new DmFailure(400,"INVALID_REQUEST");return new CommandKey(caller.actor(),caller.tenant(),operation,resource,key);}
 static ObjectKey key(String tenant,UUID document,UUID artifact,boolean original){return new ObjectKey(original?"dkp-originals":"dkp-quarantine",sha(tenant.getBytes(StandardCharsets.UTF_8))+"/"+document+"/"+artifact);}
 static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 static Spool spool(InputStream input,Path root,long expected,Clock clock,int seconds){
  Path directory=null;try {
   directory=Files.createTempFile(root,"dkp-bytes-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
   Path file=directory;var digest=MessageDigest.getInstance("SHA-256");long count=0;Instant deadline=clock.instant().plusSeconds(seconds);
   try(var output=Files.newOutputStream(file,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS)) {byte[] buffer=new byte[65536];int n;
    while((n=input.read(buffer))!=-1){if(clock.instant().isAfter(deadline))throw UploadPolicy.unavailable();count=Math.addExact(count,n);
     if(count>UploadModels.MAX_BYTES)throw new DmFailure(413,"PAYLOAD_TOO_LARGE");if(count>expected)throw new DmFailure(422,"SIZE_MISMATCH");digest.update(buffer,0,n);output.write(buffer,0,n);}
   }
   if(count!=expected||count==0)throw new DmFailure(422,"SIZE_MISMATCH");return new Spool(directory,file,count,HexFormat.of().formatHex(digest.digest()));
  }catch(IOException|NoSuchAlgorithmException failure){remove(directory);throw UploadPolicy.unavailable();}catch(RuntimeException failure){remove(directory);throw failure;}
 }
 static void remove(Path directory){if(directory==null)return;try{Files.deleteIfExists(directory);}catch(IOException failure){throw UploadPolicy.unavailable();}}
 record Spool(Path directory,Path file,long size,String sha256) implements AutoCloseable {public void close(){remove(directory);}}
}
