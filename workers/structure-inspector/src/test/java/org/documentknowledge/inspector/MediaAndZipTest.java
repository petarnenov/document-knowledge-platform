package org.documentknowledge.inspector;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MediaAndZipTest {
    @TempDir Path temp;
    static byte[] image(String format)throws IOException {
        var image=new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB);image.setRGB(1,1,0x123456);
        try(var output=new ByteArrayOutputStream()){assertTrue(ImageIO.write(image,format,output));return output.toByteArray();}finally{image.flush();}
    }
    private void accepted(byte[] bytes)throws IOException {var result=new StructureInspector(temp).inspect(new ByteArrayInputStream(bytes),Format.DOCX);assertNotNull(result);clean();}
    private void rejected(byte[] bytes){assertThrows(InspectionFailure.class,()->new StructureInspector(temp).inspect(new ByteArrayInputStream(bytes),Format.DOCX));clean();}
    private void clean(){try(var files=Files.list(temp)){assertEquals(0,files.count());}catch(IOException failure){throw new AssertionError(failure);}}
    private byte[] media(byte[] bytes,String extension)throws IOException {var parts=Fixtures.office(Format.DOCX);parts.put("word/media/image."+extension,bytes);return Fixtures.zip(parts);}
    @Test void genuinePngJpegGifTiffPassInEveryOfficeFamilyAndExactBytesRemainBound()throws Exception {
        for(String format:List.of("png","jpeg","gif","tiff"))for(Format office:List.of(Format.DOCX,Format.PPTX,Format.XLSX)){
            var parts=Fixtures.office(office);parts.put("media/image."+format,image(format));byte[] bytes=Fixtures.zip(parts);
            assertEquals(bytes.length,new StructureInspector(temp).inspect(new ByteArrayInputStream(bytes),office).sizeBytes());clean();
        }
        accepted(media(image("jpeg"),"jpg"));accepted(media(image("tiff"),"tif"));
    }
    @Test void renamedArchivesExecutablesRandomBytesAndTruncatedImageSignaturesNeverQualifyAsMedia()throws Exception {
        var gzip=new ByteArrayOutputStream();try(var out=new GZIPOutputStream(gzip)){out.write("nested archive".getBytes(StandardCharsets.UTF_8));}
        var containers=List.of(gzip.toByteArray(),new byte[]{0x37,0x7a,(byte)0xbc,(byte)0xaf,0x27,0x1c},"Rar!\u001a\u0007\u0000".getBytes(StandardCharsets.ISO_8859_1),new byte[]{'B','Z','h','9'},new byte[]{(byte)0xfd,'7','z','X','Z',0},Fixtures.zip(Map.of("x.xml","<x/>".getBytes())),new byte[]{(byte)0xd0,(byte)0xcf,0x11,(byte)0xe0},"MZ-not-an-image".getBytes(),new byte[512]);
        for(String ext:List.of("png","jpeg","gif","tiff"))for(byte[] bytes:containers)rejected(media(bytes,ext));
        for(String ext:List.of("png","jpeg","gif","tiff")){byte[] valid=image(ext);rejected(media(Arrays.copyOf(valid,Math.min(10,valid.length)),ext));rejected(media(Arrays.copyOf(valid,valid.length-1),ext));}
    }
    @Test void trailingContainersOrGarbageAndMismatchedImageFamiliesAreRejected()throws Exception {
        for(String ext:List.of("png","jpeg","gif","tiff")) {
            byte[] valid=image(ext),trailing=Arrays.copyOf(valid,valid.length+20);System.arraycopy("GZIP archive tail".getBytes(),0,trailing,valid.length,17);rejected(media(trailing,ext));
            for(String other:List.of("png","jpeg","gif","tiff"))if(!ext.equals(other))rejected(media(valid,other));
        }
    }
    @Test void pngCrcLengthChunksAndImageExpansionAreCheckedBeforeDecode()throws Exception {
        byte[] png=image("png");byte[] crc=png.clone();crc[29]^=1;rejected(media(crc,"png"));
        byte[] length=png.clone();ByteBuffer.wrap(length).putInt(8,Integer.MAX_VALUE);rejected(media(length,"png"));
        byte[] dimensions=png.clone();ByteBuffer b=ByteBuffer.wrap(dimensions);b.putInt(16,1000000);b.putInt(20,1000000);CRC32 checksum=new CRC32();checksum.update(dimensions,12,17);b.putInt(29,(int)checksum.getValue());rejected(media(dimensions,"png"));
        byte[] wrong=png.clone();wrong[12]='X';rejected(media(wrong,"png"));
    }
    @Test void jpegGifAndTiffMalformedStructuresFailClosed()throws Exception {
        byte[] jpeg=image("jpeg");byte[] broken=jpeg.clone();broken[3]=0;rejected(media(broken,"jpeg"));broken=jpeg.clone();broken[4]=0;broken[5]=1;rejected(media(broken,"jpeg"));
        byte[] gif=image("gif");for(int at:new int[]{0,10,gif.length-1}){broken=gif.clone();broken[at]=(byte)0xff;rejected(media(broken,"gif"));}
        byte[] tiff=image("tiff");for(int at:new int[]{0,2,4}){broken=tiff.clone();broken[at]=(byte)0xff;rejected(media(broken,"tiff"));}
    }
    @Test void storedAndDeflatedEntriesSignedAndUnsignedDescriptorsAreCompatible()throws Exception {
        for(boolean stored:List.of(true,false))for(int descriptor:new int[]{0,1,2})accepted(archive(Fixtures.office(Format.DOCX),stored,descriptor));
    }
    @Test void localTraversalNamesFlagsMethodCrcAndSizesCannotDisagreeWithCentralDirectory()throws Exception {
        byte[] original=archive(Fixtures.office(Format.DOCX),false,0);
        for(int at:new int[]{6,8,14,18,22,30}){byte[] bytes=original.clone();bytes[at]^=1;rejected(bytes);}
        byte[] traversal=original.clone();traversal[30]='.';traversal[31]='.';traversal[32]='/';rejected(traversal);
        byte[] descriptor=archive(Fixtures.office(Format.DOCX),false,1);int at=find(descriptor,0x08074b50);descriptor[at+4]^=1;rejected(descriptor);
        descriptor=archive(Fixtures.office(Format.DOCX),false,2);at=find(descriptor,0x02014b50);descriptor[at-1]^=1;rejected(descriptor);
    }
    @Test void matchingForgedCrcStillFailsActualExpansionIntegrity()throws Exception {
        byte[] bytes=archive(Fixtures.office(Format.DOCX),false,0);int central=find(bytes,0x02014b50);ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);b.putInt(14,123);b.putInt(central+16,123);rejected(bytes);
    }
    @Test void centralOffsetsCountsAndHiddenPhysicalRecordsCannotCreateAlternateViews()throws Exception {
        byte[] original=archive(Fixtures.office(Format.DOCX),false,0);int central=find(original,0x02014b50);
        for(int at:new int[]{central+8,central+10,central+20,central+24,central+34,central+42,original.length-14,original.length-10,original.length-6}){byte[] bytes=original.clone();bytes[at]^=1;rejected(bytes);}
        // Leave a complete first local entry physically present but remove its central record.
        var b=ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN);int n=46+Short.toUnsignedInt(b.getShort(central+28))+Short.toUnsignedInt(b.getShort(central+30))+Short.toUnsignedInt(b.getShort(central+32));
        byte[] hidden=new byte[original.length-n];System.arraycopy(original,0,hidden,0,central);System.arraycopy(original,central+n,hidden,central,original.length-central-n);
        b=ByteBuffer.wrap(hidden).order(ByteOrder.LITTLE_ENDIAN);int end=hidden.length-22;b.putShort(end+8,(short)2);b.putShort(end+10,(short)2);b.putInt(end+12,b.getInt(end+12)-n);rejected(hidden);
    }
    private static int find(byte[] bytes,int value){var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<=bytes.length-4;i++)if(b.getInt(i)==value)return i;throw new AssertionError("record");}
    /** Genuine ZIP32 fixture writer independent of the production parser; descriptor0 absent/1signed/2unsigned. */
    static byte[] archive(Map<String,byte[]> entries,boolean stored,int descriptor)throws IOException {return archive(entries,stored,descriptor,new byte[0]);}
    static byte[] archive(Map<String,byte[]> entries,boolean stored,int descriptor,byte[] extra)throws IOException {
        var output=new ByteArrayOutputStream();var central=new ByteArrayOutputStream();int count=0;
        for(var entry:entries.entrySet()) {
            byte[] raw=entry.getKey().getBytes(StandardCharsets.UTF_8),expanded=entry.getValue(),compressed=expanded;CRC32 crc=new CRC32();crc.update(expanded);
            if(!stored){var deflater=new Deflater(6,true);try{var out=new ByteArrayOutputStream();try(var deflated=new DeflaterOutputStream(out,deflater)){deflated.write(expanded);}compressed=out.toByteArray();}finally{deflater.end();}}
            int flags=0x800|(descriptor==0?0:8),method=stored?0:8,offset=output.size();
            var local=ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);local.putInt(0x04034b50).putShort((short)20).putShort((short)flags).putShort((short)method).putInt(0).putInt(descriptor==0?(int)crc.getValue():0).putInt(descriptor==0?compressed.length:0).putInt(descriptor==0?expanded.length:0).putShort((short)raw.length).putShort((short)extra.length);output.write(local.array());output.write(raw);output.write(extra);output.write(compressed);
            if(descriptor!=0){var d=ByteBuffer.allocate(descriptor==1?16:12).order(ByteOrder.LITTLE_ENDIAN);if(descriptor==1)d.putInt(0x08074b50);d.putInt((int)crc.getValue()).putInt(compressed.length).putInt(expanded.length);output.write(d.array());}
            var h=ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);h.putInt(0x02014b50).putShort((short)20).putShort((short)20).putShort((short)flags).putShort((short)method).putInt(0).putInt((int)crc.getValue()).putInt(compressed.length).putInt(expanded.length).putShort((short)raw.length).putShort((short)extra.length).putShort((short)0).putShort((short)0).putShort((short)0).putInt(0).putInt(offset);central.write(h.array());central.write(raw);central.write(extra);count++;
        }
        int offset=output.size();output.write(central.toByteArray());var end=ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);end.putInt(0x06054b50).putInt(0).putShort((short)count).putShort((short)count).putInt(central.size()).putInt(offset).putShort((short)0);output.write(end.array());return output.toByteArray();
    }
}
