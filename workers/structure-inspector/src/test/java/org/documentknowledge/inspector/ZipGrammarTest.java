package org.documentknowledge.inspector;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ZipGrammarTest {
    @TempDir Path temp;
    private Set<String> validate(byte[] bytes)throws IOException{return StrictZip.validate(Files.write(temp.resolve("zip"),bytes));}
    private void reject(byte[] bytes){assertThrows(Exception.class,()->validate(bytes));}
    private byte[] zip(byte[] extra)throws IOException{return MediaAndZipTest.archive(Map.of("a.xml","<x/>".getBytes(StandardCharsets.UTF_8)),false,0,extra);}
    @Test void validatedUnicodePathExtraMatchesBothRawNamesAndCrc()throws Exception {
        byte[] name="a.xml".getBytes(StandardCharsets.UTF_8);CRC32 crc=new CRC32();crc.update(name);
        ByteBuffer b=ByteBuffer.allocate(9+name.length).order(ByteOrder.LITTLE_ENDIAN);b.putShort((short)0x7075).putShort((short)(5+name.length)).put((byte)1).putInt((int)crc.getValue()).put(name);
        byte[] extra=b.array();assertEquals(Set.of("a.xml"),validate(zip(extra)));
        for(int at:new int[]{2,4,5,9}){byte[] wrong=extra.clone();wrong[at]^=1;reject(zip(wrong));}
        reject(zip(new byte[]{0x75,0x70,1,0,1}));reject(zip(new byte[]{1,0,0,0}));reject(zip(new byte[]{1,2,3}));
        assertEquals(Set.of("a.xml"),validate(zip(new byte[]{0x55,0x54,1,0,0})));
    }
    @Test void unsupportedFlagsMethodsVersionsAndNonUtf8NamesAreRejected()throws Exception {
        for(int flag:new int[]{1,16,32,64,0x1000}){byte[] raw=zip(new byte[0]);var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);b.putShort(find(raw,0x02014b50)+8,(short)flag);reject(raw);}
        byte[] original=zip(new byte[0]);int central=find(original,0x02014b50);
        for(int at:new int[]{central+6,central+10,central+34,central+28,central+30,central+42,4,26,28}){byte[] raw=original.clone();ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putShort(at,(short)0xffff);reject(raw);}
        byte[] raw=original.clone();raw[central+46]=(byte)0xff;reject(raw);
        raw=original.clone();raw[central+46]='.';raw[central+47]='.';raw[central+48]='/';reject(raw);
        raw=original.clone();ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(central+38,0120777<<16);reject(raw);
        reject(new byte[10]);reject(new byte[22]);
    }
    @Test void overlappingDuplicateOffsetsGapsTruncatedPayloadAndInvalidDeflateFail()throws Exception {
        byte[] original=MediaAndZipTest.archive(new LinkedHashMap<>(Map.of("a.xml","<x/>".getBytes(),"b.xml","<y/>".getBytes())),false,0);int first=find(original,0x02014b50),second=find(original,0x02014b50,first+4);
        byte[] raw=original.clone();ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(second+42,0);reject(raw);
        raw=original.clone();ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(first+42,1);reject(raw);
        raw=original.clone();ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(first+20,Integer.MAX_VALUE);reject(raw);
        raw=original.clone();raw[35]=(byte)0xff;reject(raw);
        raw=original.clone();ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(first+24,512_000_001);reject(raw);
        raw=original.clone();ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(first+24,10000);reject(raw);
    }
    @Test void unsignedDescriptorCrcMayEqualOptionalSignatureWithoutAmbiguity()throws Exception {
        byte[] payload=java.util.HexFormat.of().parseHex("ac0a7ad5");CRC32 crc=new CRC32();crc.update(payload);assertEquals(0x08074b50L,crc.getValue());
        for(int descriptor:new int[]{1,2})assertEquals(Set.of("image.bin"),validate(MediaAndZipTest.archive(Map.of("image.bin",payload),false,descriptor)));
    }
    @Test void crcAndStoredLengthCannotBeForgedTogether()throws Exception {
        byte[] raw=MediaAndZipTest.archive(Map.of("a.xml","<x/>".getBytes()),true,0);int central=find(raw,0x02014b50);var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);b.putInt(14,99);b.putInt(central+16,99);reject(raw);
        raw=MediaAndZipTest.archive(Map.of("a.xml","<x/>".getBytes()),true,0);b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);central=find(raw,0x02014b50);b.putInt(22,3);b.putInt(central+24,3);reject(raw);
    }
    @Test void declaredAggregateLimitIsPreflightedBeforeAnyLocalPayload()throws Exception {
        // Deliberately corrupt the first local signature: crossing the central-directory
        // budget must win before this local record (or any deflate stream) is examined.
        byte[] raw=MediaAndZipTest.archive(new LinkedHashMap<>(Map.of("a.bin",new byte[]{1},"b.bin",new byte[]{2})),false,0);
        int first=find(raw,0x02014b50),second=find(raw,0x02014b50,first+4);
        var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);b.putInt(0,0);
        b.putInt(first+24,256_000_000);b.putInt(second+24,256_000_000);
        assertEquals("INVALID_FILE",assertThrows(InspectionFailure.class,()->validate(raw)).code(),"Exactly512MB proceeds to independent local-record validation");
        b.putInt(second+24,256_000_001);
        assertEquals("STRUCTURE_LIMIT",assertThrows(InspectionFailure.class,()->validate(raw)).code());
        b.putInt(first+24,-2); // UnsignedZIP32 declaration must not overflow or bypass the sum.
        assertEquals("STRUCTURE_LIMIT",assertThrows(InspectionFailure.class,()->validate(raw)).code());
    }
    @Test void everyXmlAndRelationshipDeclarationHasAnInclusivePreflightLimit()throws Exception {
        for(String name:List.of("part.xml","part.XML","_rels/.rels","part.RELS")) {
            byte[] raw=MediaAndZipTest.archive(Map.of(name,new byte[]{1}),false,0);
            int central=find(raw,0x02014b50);var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);b.putInt(0,0);
            b.putInt(central+24,32_000_000);
            assertEquals("INVALID_FILE",assertThrows(InspectionFailure.class,()->validate(raw)).code(),"Exact32MB is not rejected as a size-limit violation");
            b.putInt(central+24,32_000_001);
            assertEquals("STRUCTURE_LIMIT",assertThrows(InspectionFailure.class,()->validate(raw)).code());
        }
        // The XML limit must not accidentally become a blanket32MB binary-part limit.
        byte[] binary=MediaAndZipTest.archive(Map.of("media/image.bin",new byte[]{1}),false,0);
        var b=ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);b.putInt(0,0);b.putInt(find(binary,0x02014b50)+24,32_000_001);
        assertEquals("INVALID_FILE",assertThrows(InspectionFailure.class,()->validate(binary)).code());
    }
    private static int find(byte[] raw,int signature){return find(raw,signature,0);}
    private static int find(byte[] raw,int signature,int start){var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);for(int at=start;at<=raw.length-4;at++)if(b.getInt(at)==signature)return at;throw new AssertionError();}
}
