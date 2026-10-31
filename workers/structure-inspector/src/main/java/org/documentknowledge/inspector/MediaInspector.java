package org.documentknowledge.inspector;

import java.io.*;
import java.nio.*;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.CRC32;
import javax.imageio.*;
import javax.imageio.stream.FileImageInputStream;

/** Closed raster containers, exact termination and bounded genuine decoding inside the worker sandbox. */
final class MediaInspector {
    private static final long MAX_DECODED=512_000_000;
    private MediaInspector() { }
    static long inspect(Path path,String extension) throws IOException {
        String format=switch(extension){case "png"->"png";case "jpg","jpeg"->"jpeg";case "gif"->"gif";case "tif","tiff"->"tiff";default->throw new InspectionFailure("UNSUPPORTED_CONTENT");};
        try(var file=new RandomAccessFile(path.toFile(),"r")) {
            switch(format){case "png"->png(file);case "jpeg"->jpeg(file);case "gif"->gif(file);case "tiff"->tiff(file);default->throw new AssertionError();}
        }
        // File-backed input avoids an unbounded ImageIO cache. Only the JDK's pinned built-in readers exist.
        var readers=ImageIO.getImageReadersByFormatName(format);if(!readers.hasNext())bad();ImageReader reader=readers.next();
        try(var input=new FileImageInputStream(path.toFile())) {
            reader.setInput(input,false,true);reader.addIIOReadWarningListener((source,warning)->{throw new InspectionFailure("INVALID_FILE");});
            int count=reader.getNumImages(true);if(count<1||count>10000)limit();long decoded=0;
            // Validate every frame's allocation budget before decoding even the first frame.
            for(int i=0;i<count;i++){long width=reader.getWidth(i),height=reader.getHeight(i);var raw=reader.getRawImageType(i);if(raw==null)bad();long pixelBytes=Math.max(8,(long)raw.getSampleModel().getNumDataElements()*java.awt.image.DataBuffer.getDataTypeSize(raw.getSampleModel().getDataType())/8);if(width<1||height<1||width>MAX_DECODED/pixelBytes/height)limit();decoded=Math.addExact(decoded,width*height*pixelBytes);if(decoded>MAX_DECODED)limit();}
            for(int i=0;i<count;i++){var image=reader.read(i);if(image==null)bad();image.flush();}
            return decoded;
        }finally{reader.dispose();}
    }
    private static void png(RandomAccessFile f)throws IOException {
        if(f.length()<33||f.readLong()!=0x89504e470d0a1a0aL)bad();boolean first=true,data=false;byte[] buffer=new byte[65536];
        while(f.getFilePointer()<f.length()) {
            long length=Integer.toUnsignedLong(f.readInt());int type=f.readInt();if(length>f.length()-f.getFilePointer()-4)bad();
            if(first&&(type!=0x49484452||length!=13)||!first&&type==0x49484452)bad();first=false;
            byte[] name=ByteBuffer.allocate(4).putInt(type).array();for(byte c:name)if(c<'A'||c>'Z'&&c<'a'||c>'z')bad();
            CRC32 crc=new CRC32();crc.update(name);long remaining=length;
            while(remaining>0){int n=(int)Math.min(remaining,buffer.length);f.readFully(buffer,0,n);crc.update(buffer,0,n);remaining-=n;}
            if(crc.getValue()!=Integer.toUnsignedLong(f.readInt()))bad();
            if(type==0x49444154)data=true;
            if(type==0x49454e44){if(length!=0||!data||f.getFilePointer()!=f.length())bad();return;}
        }bad();
    }
    private static void jpeg(RandomAccessFile f)throws IOException {
        if(f.length()<4||f.readUnsignedShort()!=0xffd8)bad();boolean scanned=false;
        while(f.getFilePointer()<f.length()) {
            if(f.readUnsignedByte()!=255)bad();int marker;do{marker=f.readUnsignedByte();}while(marker==255);
            if(marker==0xd9){if(!scanned||f.getFilePointer()!=f.length())bad();return;}
            if(marker==0||marker==0xd8||marker==1||marker>=0xd0&&marker<=0xd7)bad();
            int length=f.readUnsignedShort();if(length<2||f.getFilePointer()+length-2>f.length())bad();f.seek(f.getFilePointer()+length-2);
            if(marker==0xda){scanned=true;while(f.getFilePointer()<f.length())if(f.readUnsignedByte()==255){int next;do{next=f.readUnsignedByte();}while(next==255);if(next!=0&&!(next>=0xd0&&next<=0xd7)){f.seek(f.getFilePointer()-2);break;}}}
        }bad();
    }
    private static void gif(RandomAccessFile f)throws IOException {
        byte[] signature=new byte[6];f.readFully(signature);if(!Arrays.equals(signature,"GIF87a".getBytes(java.nio.charset.StandardCharsets.US_ASCII))&&!Arrays.equals(signature,"GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))bad();
        f.skipBytes(4);int packed=f.readUnsignedByte();f.skipBytes(2);if((packed&128)!=0)skip(f,3L*(1<<((packed&7)+1)));
        boolean image=false;
        while(f.getFilePointer()<f.length()) {
            int type=f.readUnsignedByte();
            if(type==0x3b){if(!image||f.getFilePointer()!=f.length())bad();return;}
            if(type==0x2c){skip(f,8);packed=f.readUnsignedByte();if((packed&128)!=0)skip(f,3L*(1<<((packed&7)+1)));int code=f.readUnsignedByte();if(code<2||code>8)bad();blocks(f);image=true;}
            else if(type==0x21){int label=f.readUnsignedByte();if(!Set.of(0xf9,0xfe,0xff,1).contains(label))bad();blocks(f);}
            else bad();
        }bad();
    }
    private static void blocks(RandomAccessFile f)throws IOException {int length;while((length=f.readUnsignedByte())!=0)skip(f,length);}
    private static void skip(RandomAccessFile f,long n)throws IOException {if(n<0||f.getFilePointer()+n>f.length())bad();f.seek(f.getFilePointer()+n);}
    /** Classic TIFF: account for every IFD, out-of-line value and image strip/tile, with only zero alignment gaps. */
    private static void tiff(RandomAccessFile f)throws IOException {
        if(f.length()<8)bad();int order=f.readUnsignedShort();if(order!=0x4949&&order!=0x4d4d)bad();boolean little=order==0x4949;
        if(shortAt(f,2,little)!=42)bad();var ranges=new TreeMap<Long,Long>();range(ranges,0,8,f.length());
        var pending=new ArrayDeque<Long>();pending.add(intAt(f,4,little));var seen=new HashSet<Long>();int fields=0;
        while(!pending.isEmpty()) {
            long at=pending.remove();if(at==0)continue;if(!seen.add(at)||seen.size()>10000)bad();int count=shortAt(f,at,little);fields+=count;if(fields>100000)limit();
            range(ranges,at,2L+12L*count+4,f.length());Map<Integer,long[]> offsets=new HashMap<>();
            for(int i=0;i<count;i++) {
                long record=at+2L+12L*i;int tag=shortAt(f,record,little),type=shortAt(f,record+2,little);long countValues=intAt(f,record+4,little);
                int unit=switch(type){case 1,2,6,7->1;case 3,8->2;case 4,9,11,13->4;case 5,10,12->8;default->throw new InspectionFailure("INVALID_FILE");};
                if(countValues>1_000_000)limit();long size=countValues*unit,value=size>4?intAt(f,record+8,little):record+8;
                if(size>4)range(ranges,value,size,f.length());
                if(Set.of(273,279,322,323,324,325,330,34665,34853,40965).contains(tag)) {
                    if(type!=3&&type!=4&&type!=13||countValues==0)bad();long[] values=new long[(int)countValues];for(int j=0;j<values.length;j++)values[j]=unit==2?shortAt(f,value+2L*j,little):intAt(f,value+4L*j,little);
                    if(offsets.put(tag,values)!=null)bad();if(Set.of(330,34665,34853,40965).contains(tag))for(long next:values)pending.add(next);
                }
            }
            if(offsets.containsKey(322)||offsets.containsKey(323)) {
                long[] width=offsets.get(322),height=offsets.get(323);
                if(width==null||height==null||width.length!=1||height.length!=1||width[0]<1||height[0]<1||width[0]>MAX_DECODED/8/height[0])limit();
            }
            for(int tag:new int[]{273,324})if(offsets.containsKey(tag)) {
                long[] starts=offsets.get(tag),lengths=offsets.get(tag==273?279:325);if(lengths==null||lengths.length!=starts.length)bad();
                for(int i=0;i<starts.length;i++)range(ranges,starts[i],lengths[i],f.length());
            }
            long next=intAt(f,at+2L+12L*count,little);if(next!=0)pending.add(next);
        }
        long end=0;for(var r:ranges.entrySet()){if(r.getKey()-end>7)bad();f.seek(end);while(f.getFilePointer()<r.getKey())if(f.readUnsignedByte()!=0)bad();end=r.getValue();}if(end!=f.length())bad();
    }
    private static void range(TreeMap<Long,Long> ranges,long start,long length,long maximum){if(start<0||length<1||start>maximum-length)bad();Long old=ranges.get(start);if(old!=null&&old==start+length)return;var lower=ranges.floorEntry(start);var higher=ranges.ceilingEntry(start);if(lower!=null&&lower.getValue()>start||higher!=null&&higher.getKey()<start+length)bad();ranges.put(start,start+length);}
    private static int shortAt(RandomAccessFile f,long at,boolean little)throws IOException {if(at<0||at>f.length()-2)bad();f.seek(at);int v=f.readUnsignedShort();return little?Short.toUnsignedInt(Short.reverseBytes((short)v)):v;}
    private static long intAt(RandomAccessFile f,long at,boolean little)throws IOException {if(at<0||at>f.length()-4)bad();f.seek(at);int v=f.readInt();return Integer.toUnsignedLong(little?Integer.reverseBytes(v):v);}
    private static void bad(){throw new InspectionFailure("INVALID_FILE");}
    private static void limit(){throw new InspectionFailure("STRUCTURE_LIMIT");}
}
