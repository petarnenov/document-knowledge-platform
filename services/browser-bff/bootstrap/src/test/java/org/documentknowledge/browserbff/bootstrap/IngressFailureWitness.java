package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.context.annotation.*;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import java.io.*;
import java.util.concurrent.atomic.AtomicReference;
import org.documentknowledge.browserbff.adapters.outbound.FixedUploadGateway;
import org.documentknowledge.browserbff.application.*;
import org.documentknowledge.browserbff.domain.BrowserIdentity;

/** Test-only read observer; the production gateway owns all limits, authority and HTTP operations. */
@Factory
@Requires(property="dkp338.failure-witness",value="true")
final class IngressFailureWitness {
    static final AtomicReference<Throwable> failure = new AtomicReference<>();
    @Singleton @Replaces(FixedUploadGateway.class)
    UploadGateway gateway(@Value("${dkp.bff.upstreams.dm}")String upstream,JsonMapper json) {
        var actual=new FixedUploadGateway(upstream,json);
        return new UploadGateway() {
            public DocumentGateway.Result transfer(String path,String key,InputStream source,long length,BrowserIdentity actor,String tenant,Runnable authorize) {
                return actual.transfer(path,key,new FilterInputStream(source){
                    @Override public int read(byte[] bytes,int offset,int count)throws IOException {
                        try{return in.read(bytes,offset,count);}catch(IOException|RuntimeException problem){failure.compareAndSet(null,problem);throw problem;}
                    }
                    @Override public int read()throws IOException {
                        try{return in.read();}catch(IOException|RuntimeException problem){failure.compareAndSet(null,problem);throw problem;}
                    }
                },length,actor,tenant,authorize);
            }
            public StreamResult download(String path,String range,BrowserIdentity actor,String tenant) {return actual.download(path,range,actor,tenant);}
        };
    }
}
