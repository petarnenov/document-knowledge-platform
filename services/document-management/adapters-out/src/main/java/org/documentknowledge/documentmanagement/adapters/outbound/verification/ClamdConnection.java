package org.documentknowledge.documentmanagement.adapters.outbound.verification;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Deadline applies to connect, every write and read together; Socket SO_TIMEOUT does not bound writes. */
final class ClamdConnection implements AutoCloseable {
    private final SocketChannel channel;
    private final Selector selector;
    private final long deadline;
    ClamdConnection(InetSocketAddress endpoint,Duration timeout) throws IOException {
        deadline=System.nanoTime()+timeout.toNanos();channel=SocketChannel.open();channel.configureBlocking(false);selector=Selector.open();channel.register(selector,SelectionKey.OP_CONNECT);
        try {channel.connect(endpoint);while(!channel.finishConnect())await(SelectionKey.OP_CONNECT);}catch(IOException|RuntimeException failure){close();throw failure;}
    }
    void write(byte[] bytes) throws IOException {ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining()){check();if(channel.write(buffer)==0)await(SelectionKey.OP_WRITE);}}
    String response() throws IOException {
        ByteBuffer buffer=ByteBuffer.allocate(1);ByteArrayOutputStream result=new ByteArrayOutputStream();boolean terminated=false;
        while(result.size()<=1024) {
            check();buffer.clear();int n=channel.read(buffer);if(n<0){if(terminated)return result.toString(StandardCharsets.US_ASCII);throw new EOFException("Incomplete scanner response");}if(n==0){await(SelectionKey.OP_READ);continue;}
            if(terminated)throw new IOException("Unexpected scanner records");
            byte value=buffer.array()[0];if(value==0){terminated=true;continue;}
            if(value<32 || value>126)throw new IOException("Invalid scanner response");result.write(value);
        }
        throw new IOException("Scanner response limit");
    }
    private void await(int operation) throws IOException {check();channel.keyFor(selector).interestOps(operation);selector.select(Math.max(1,(deadline-System.nanoTime())/1_000_000));selector.selectedKeys().clear();check();}
    private void check() throws IOException {if(Thread.currentThread().isInterrupted() || System.nanoTime()>=deadline)throw new java.net.SocketTimeoutException("Scanner deadline");}
    @Override public void close() throws IOException {try{channel.close();}finally{selector.close();}}
}
