package org.documentknowledge.inspector;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MediaGrammarTest {
    @TempDir Path temp;
    private long inspect(byte[] bytes,String ext)throws IOException{return MediaInspector.inspect(Files.write(temp.resolve("image"),bytes),ext);}
    private void reject(byte[] bytes,String ext){assertThrows(Exception.class,()->inspect(bytes,ext));}
    @Test void gifCommentExtensionIsGenuineButInvalidBlocksAndTrailerDoNotDecode()throws Exception {
        byte[] gif=MediaAndZipTest.image("gif");int image=13+((gif[10]&128)!=0?3*(1<<((gif[10]&7)+1)):0);
        byte[] comment={0x21,(byte)0xfe,4,'n','o','t','e',0};byte[] with=insert(gif,image,comment);assertTrue(inspect(with,"gif")>0);
        byte[] wrong=with.clone();wrong[image+1]=(byte)0xf0;reject(wrong,"gif");wrong=with.clone();wrong[image+2]=(byte)255;reject(wrong,"gif");
        wrong=gif.clone();wrong[image]=0;reject(wrong,"gif");wrong=gif.clone();wrong[image+10]=1;reject(wrong,"gif");
        byte[] empty=Arrays.copyOf(gif,image+1);empty[image]=0x3b;reject(empty,"gif");
        reject(Arrays.copyOf(gif,5),"gif");
    }
    @Test void jpegMarkerOrderLengthsAndEofAreStrict()throws Exception {
        for(byte[] bytes:List.of(new byte[]{(byte)255,(byte)216,(byte)255,(byte)217},new byte[]{(byte)255,(byte)216,(byte)255,0},new byte[]{(byte)255,(byte)216,(byte)255,(byte)216},new byte[]{(byte)255,(byte)216,(byte)255,1},new byte[]{(byte)255,(byte)216,(byte)255,(byte)208},new byte[]{(byte)255,(byte)216,(byte)255,(byte)224,127,127}))reject(bytes,"jpeg");
        byte[] valid=MediaAndZipTest.image("jpeg");byte[] fill=insert(valid,2,new byte[]{(byte)255});assertTrue(inspect(fill,"jpeg")>0);
    }
    @Test void pngTerminationChunkAlphabetAndRequiredImageDataAreStrict()throws Exception {
        byte[] valid=MediaAndZipTest.image("png");byte[] noData=Arrays.copyOf(valid,45);System.arraycopy(chunk("IEND",new byte[0]),0,noData,33,12);reject(noData,"png");
        byte[] wrong=valid.clone();wrong[37]='0';reject(wrong,"png");wrong=valid.clone();wrong[37]=(byte)255;reject(wrong,"png");
        byte[] end=chunk("IEND",new byte[]{1});wrong=Arrays.copyOf(valid,valid.length-12+end.length);System.arraycopy(end,0,wrong,valid.length-12,end.length);reject(wrong,"png");
        wrong=Arrays.copyOf(valid,valid.length-12);reject(wrong,"png");
        assertTrue(inspect(insert(valid,33,chunk("tEXt","Comment\0safe text".getBytes())),"png")>0);
    }
    @Test void tiffTypeCountOffsetsCyclesAndStripBindingsAreBounded()throws Exception {
        byte[] valid=MediaAndZipTest.image("tiff");ByteOrder order=valid[0]=='I'?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN;var b=ByteBuffer.wrap(valid).order(order);int ifd=b.getInt(4),count=Short.toUnsignedInt(b.getShort(ifd));
        for(int tag:new int[]{256,257,273,279}){int record=findTag(valid,tag);byte[] wrong=valid.clone();ByteBuffer.wrap(wrong).order(order).putShort(record+2,(short)99);reject(wrong,"tiff");}
        int width=findTag(valid,256);byte[] wrong=valid.clone();ByteBuffer.wrap(wrong).order(order).putInt(width+4,1_000_001);reject(wrong,"tiff");
        int strips=findTag(valid,273);wrong=valid.clone();ByteBuffer.wrap(wrong).order(order).putInt(strips+8,0);reject(wrong,"tiff");
        wrong=valid.clone();ByteBuffer.wrap(wrong).order(order).putInt(ifd+2+12*count,ifd);reject(wrong,"tiff");
        wrong=valid.clone();ByteBuffer.wrap(wrong).order(order).putInt(4,0);reject(wrong,"tiff");
        int resolution=findTag(valid,282);wrong=valid.clone();var w=ByteBuffer.wrap(wrong).order(order);w.putShort(resolution,(short)330).putShort(resolution+2,(short)4).putInt(resolution+4,1).putInt(resolution+8,ifd);reject(wrong,"tiff");
        wrong=valid.clone();ByteBuffer.wrap(wrong).order(order).putInt(resolution+8,Integer.MAX_VALUE);reject(wrong,"tiff");
        reject(valid,"bmp");
    }
    @Test void genuineTiledTiffAndProgressiveJpegRemainSupported()throws Exception {
        var image=new java.awt.image.BufferedImage(64,48,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var random=new Random(115);for(int y=0;y<48;y++)for(int x=0;x<64;x++)image.setRGB(x,y,random.nextInt());
        for(String format:List.of("tiff","jpeg")) {
            var writer=javax.imageio.ImageIO.getImageWritersByFormatName(format).next();var output=new ByteArrayOutputStream();
            try(var stream=new javax.imageio.stream.MemoryCacheImageOutputStream(output)) {
                writer.setOutput(stream);var params=writer.getDefaultWriteParam();
                if(format.equals("tiff")){params.setTilingMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);params.setTiling(16,16,0,0);}
                else params.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DEFAULT);
                writer.write(null,new javax.imageio.IIOImage(image,null,null),params);
            }finally{writer.dispose();}
            byte[] bytes=output.toByteArray();assertTrue(inspect(bytes,format)>0);
            if(format.equals("tiff")) {
                ByteOrder order=bytes[0]=='I'?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN;int tile=findTag(bytes,322);
                byte[] invalid=bytes.clone();ByteBuffer.wrap(invalid).order(order).putInt(tile+4,2);reject(invalid,"tiff");
                invalid=bytes.clone();ByteBuffer.wrap(invalid).order(order).putInt(tile+8,0);reject(invalid,"tiff");
            }
        }
        image.flush();
    }
    private static int findTag(byte[] bytes,int tag){var b=ByteBuffer.wrap(bytes).order(bytes[0]=='I'?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN);int at=b.getInt(4),count=Short.toUnsignedInt(b.getShort(at));for(int i=0;i<count;i++)if(Short.toUnsignedInt(b.getShort(at+2+12*i))==tag)return at+2+12*i;throw new AssertionError("Missing fixture tag "+tag);}
    private static byte[] chunk(String type,byte[] data){var b=ByteBuffer.allocate(12+data.length);b.putInt(data.length).put(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII)).put(data);CRC32 crc=new CRC32();crc.update(b.array(),4,4+data.length);b.putInt((int)crc.getValue());return b.array();}
    private static byte[] insert(byte[] source,int at,byte[] insert){byte[] result=new byte[source.length+insert.length];System.arraycopy(source,0,result,0,at);System.arraycopy(insert,0,result,at,insert.length);System.arraycopy(source,at,result,at+insert.length,source.length-at);return result;}
}
