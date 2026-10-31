package org.documentknowledge.inspector;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.DataFormatException;

/** ZIP32 package grammar: every physical local record belongs to exactly one central entry. */
final class StrictZip {
    private StrictZip() { }
    private record Entry(String name,byte[] raw,int flags,int method,long crc,long compressed,long expanded,long offset) { }
    static Set<String> validate(Path path) throws IOException {
        try(var file=new RandomAccessFile(path.toFile(),"r")) {
            long length=file.length();if(length<22)bad();
            int tailLength=(int)Math.min(length,65557);byte[] tail=new byte[tailLength];file.seek(length-tailLength);file.readFully(tail);
            ByteBuffer end=ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);int e=-1;
            for(int i=tailLength-22;i>=0;i--)if(end.getInt(i)==0x06054b50 && i+22+u16(end,i+20)==tailLength){e=i;break;}
            if(e<0)bad();
            int count=u16(end,e+10);long centralSize=u32(end,e+12),centralOffset=u32(end,e+16);
            if(u16(end,e+4)!=0||u16(end,e+6)!=0||u16(end,e+8)!=count||count==0||count>10000||centralOffset+centralSize!=length-tailLength+e)bad();
            file.seek(centralOffset);var entries=new ArrayList<Entry>();Set<String> names=new HashSet<>(),folded=new HashSet<>();long declaredExpanded=0;
            for(int i=0;i<count;i++) {
                ByteBuffer h=read(file,46,centralOffset+centralSize);if(h.getInt(0)!=0x02014b50)bad();
                int flags=u16(h,8),method=u16(h,10),nameLength=u16(h,28),extraLength=u16(h,30),comment=u16(h,32);
                long compressed=u32(h,20),expanded=u32(h,24),offset=u32(h,42);
                if(u16(h,6)>20||u16(h,34)!=0||compressed==0xffffffffL||expanded==0xffffffffL||offset==0xffffffffL||nameLength<1||nameLength>1024||!supported(flags,method))bad();
                int mode=(int)(u32(h,38)>>>16);if((mode&0170000)==0120000)throw new InspectionFailure("UNSUPPORTED_CONTENT");
                byte[] raw=read(file,nameLength,centralOffset+centralSize).array();String name=name(raw);OfficeInspector.canonical(name);
                if(!names.add(name)||!folded.add(name.toLowerCase(Locale.ROOT)))bad();
                // Entire central-directory budget is checked before any local payload is inflated.
                // Actual expanded sizes and CRCs are still verified independently below.
                String lower=name.toLowerCase(Locale.ROOT);
                if(expanded>512_000_000L-declaredExpanded
                        ||((lower.endsWith(".xml")||lower.endsWith(".rels"))&&expanded>32_000_000L))throw new InspectionFailure("STRUCTURE_LIMIT");
                declaredExpanded+=expanded;
                extra(read(file,extraLength,centralOffset+centralSize).array(),raw);read(file,comment,centralOffset+centralSize);
                entries.add(new Entry(name,raw,flags,method,u32(h,16),compressed,expanded,offset));
            }
            if(file.getFilePointer()!=centralOffset+centralSize)bad();
            entries.sort(Comparator.comparingLong(Entry::offset));long next=0;
            for(int index=0;index<entries.size();index++) {
                Entry entry=entries.get(index);long boundary=index+1<entries.size()?entries.get(index+1).offset():centralOffset;
                // No prefix, holes, overlap, duplicate offsets or unreferenced local records.
                if(entry.offset()!=next)bad();file.seek(next);ByteBuffer h=read(file,30,centralOffset);
                if(h.getInt(0)!=0x04034b50||u16(h,4)>20||u16(h,6)!=entry.flags()||u16(h,8)!=entry.method())bad();
                byte[] raw=read(file,u16(h,26),centralOffset).array();String local=name(raw);OfficeInspector.canonical(local);
                if(!Arrays.equals(raw,entry.raw())||!local.equals(entry.name()))bad();
                extra(read(file,u16(h,28),centralOffset).array(),raw);
                boolean descriptor=(entry.flags()&8)!=0;
                if(descriptor) {
                    if(!zeroOr(u32(h,14),entry.crc())||!zeroOr(u32(h,18),entry.compressed())||!zeroOr(u32(h,22),entry.expanded()))bad();
                }else if(u32(h,14)!=entry.crc()||u32(h,18)!=entry.compressed()||u32(h,22)!=entry.expanded())bad();
                if(file.getFilePointer()+entry.compressed()>centralOffset)bad();
                verifyContent(file,entry);
                if(descriptor) {
                    long descriptorBytes=boundary-file.getFilePointer();
                    if(descriptorBytes!=12&&descriptorBytes!=16)bad();
                    long crc=u32(read(file,4,boundary),0);
                    if(descriptorBytes==16){if(crc!=0x08074b50L)bad();crc=u32(read(file,4,boundary),0);}
                    ByteBuffer sizes=read(file,8,boundary);
                    if(crc!=entry.crc()||u32(sizes,0)!=entry.compressed()||u32(sizes,4)!=entry.expanded())bad();
                }
                next=file.getFilePointer();
            }
            if(next!=centralOffset)bad();return Set.copyOf(names);
        }
    }
    private static void verifyContent(RandomAccessFile file,Entry entry)throws IOException {
        if(entry.expanded()>512_000_000L||entry.expanded()>Math.max(1,entry.compressed())*100L)throw new InspectionFailure("STRUCTURE_LIMIT");
        CRC32 crc=new CRC32();long left=entry.compressed(),expanded=0;byte[] input=new byte[65536],output=new byte[65536];
        Inflater inflater=entry.method()==8?new Inflater(true):null;
        try {
            while(left>0) {
                int count=(int)Math.min(left,input.length);file.readFully(input,0,count);left-=count;
                if(inflater==null){crc.update(input,0,count);expanded+=count;}
                else {
                    if(inflater.finished())bad();inflater.setInput(input,0,count);
                    while(!inflater.needsInput()&&!inflater.finished()) {
                        int n=inflater.inflate(output);crc.update(output,0,n);expanded+=n;
                        if(expanded>entry.expanded())bad();if(n==0&&!inflater.finished()&&!inflater.needsInput())bad();
                    }
                    if(inflater.finished()&&(left!=0||inflater.getRemaining()!=0))bad();
                }
            }
            if(inflater!=null&&!inflater.finished()||expanded!=entry.expanded()||crc.getValue()!=entry.crc())bad();
        }catch(DataFormatException malformed){throw new InspectionFailure("INVALID_FILE");}
        finally{if(inflater!=null)inflater.end();}
    }
    private static boolean supported(int flags,int method){return (method==0||method==8)&&(flags&~(0x800|8|(method==8?6:0)))==0;}
    private static boolean zeroOr(long value,long expected){return value==0||value==expected;}
    private static ByteBuffer read(RandomAccessFile file,int length,long end)throws IOException {if(length<0||file.getFilePointer()+length>end)bad();byte[] bytes=new byte[length];file.readFully(bytes);return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);}
    private static String name(byte[] raw){try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();}catch(CharacterCodingException invalid){throw new InspectionFailure("INVALID_FILE");}}
    private static void extra(byte[] bytes,byte[] raw) {
        ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);int at=0;
        while(at<bytes.length) {
            if(at+4>bytes.length)bad();int id=u16(b,at),length=u16(b,at+2);at+=4;if(at+length>bytes.length||id==1)bad();
            // Unicode alternate names must describe exactly the same canonical UTF-8 path.
            if(id==0x7075) {if(length<5||bytes[at]!=1)bad();CRC32 crc=new CRC32();crc.update(raw);if(u32(b,at+1)!=crc.getValue()||!Arrays.equals(Arrays.copyOfRange(bytes,at+5,at+length),raw))bad();}
            at+=length;
        }
    }
    private static int u16(ByteBuffer b,int at){return Short.toUnsignedInt(b.getShort(at));}
    private static long u32(ByteBuffer b,int at){return Integer.toUnsignedLong(b.getInt(at));}
    private static void bad(){throw new InspectionFailure("INVALID_FILE");}
}
