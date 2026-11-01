package org.documentknowledge.inspector;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StructureInspectorTest {
    @TempDir Path temp;
    private StructureInspector inspector;
    @BeforeEach void setup(){inspector=new StructureInspector(temp);}
    private StructureInspector.Result inspect(byte[] bytes,Format format) throws IOException {return inspector.inspect(new ByteArrayInputStream(bytes),format);}
    private void rejected(byte[] bytes,Format format){assertThrows(InspectionFailure.class,()->inspect(bytes,format));assertClean();}
    private void assertClean(){try(var paths=Files.list(temp)){assertEquals(0,paths.count());}catch(IOException failure){throw new AssertionError(failure);}}
    @Test void independentlyHashesRealValidFourFormatsAndCleansPrivateFiles() throws Exception {
        for(Format format:Format.values()) {
            byte[] source=format==Format.PDF?Fixtures.pdf(1):Fixtures.zip(Fixtures.office(format));var result=inspect(source,format);
            assertEquals(format.name(),result.format());assertEquals(format.mime(),result.mimeType());assertEquals(source.length,result.sizeBytes());assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source)),result.sha256());assertEquals(format==Format.PDF?1:null,result.pageCount());assertEquals(1,result.schemaVersion());assertEquals("1",result.inspectorVersion());assertEquals(StructureInspector.PROFILE_DIGEST,result.profileDigest());assertClean();
        }
    }
    @Test void pdfPageBoundaryAndStrictTruncation() throws Exception {assertEquals(1000,inspect(Fixtures.pdf(1000),Format.PDF).pageCount());rejected(Fixtures.pdf(1001),Format.PDF);byte[] pdf=Fixtures.pdf(1);rejected(Arrays.copyOf(pdf,pdf.length-30),Format.PDF);rejected("%PDF-1.7\ninvalid".getBytes(),Format.PDF);}
    @Test void encryptedAndActivePdfAreRejected() throws Exception {
        try(var document=new PDDocument();var out=new ByteArrayOutputStream()) {document.addPage(new PDPage());document.protect(new StandardProtectionPolicy("owner","",new AccessPermission()));document.save(out);rejected(out.toByteArray(),Format.PDF);}
        for(String dangerous:List.of("AcroForm","XFA","JavaScript","EmbeddedFiles","OpenAction","URI","RichMedia")) {
            try(var document=new PDDocument();var out=new ByteArrayOutputStream()){document.addPage(new PDPage());document.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName(dangerous),new COSString("never execute"));document.save(out);rejected(out.toByteArray(),Format.PDF);}
        }
    }
    @Test void pdfObjectWalkerBoundsCyclesDepthAndActions() {
        var cycle=new COSDictionary();cycle.setItem(COSName.PARENT,cycle);assertDoesNotThrow(()->PdfInspector.walk(cycle,0,Collections.newSetFromMap(new IdentityHashMap<>())));
        COSArray nested=new COSArray(),root=nested;for(int i=0;i<66;i++){var child=new COSArray();nested.add(child);nested=child;}var deep=root;assertThrows(InspectionFailure.class,()->PdfInspector.walk(deep,0,Collections.newSetFromMap(new IdentityHashMap<>())));
        var action=new COSDictionary();action.setItem(COSName.S,COSName.getPDFName("Launch"));assertThrows(InspectionFailure.class,()->PdfInspector.walk(action,0,Collections.newSetFromMap(new IdentityHashMap<>())));
        action.setItem(COSName.S,COSName.getPDFName("GoTo"));assertDoesNotThrow(()->PdfInspector.walk(action,0,Collections.newSetFromMap(new IdentityHashMap<>())));
    }
    @Test void officeFamilySignatureMainPartAndContentTypesCannotBeSubstituted() throws Exception {
        rejected(Fixtures.zip(Fixtures.office(Format.DOCX)),Format.PPTX);rejected(Fixtures.pdf(1),Format.DOCX);rejected(new byte[22],Format.DOCX);
        var missing=Fixtures.office(Format.DOCX);missing.remove("word/document.xml");rejected(Fixtures.zip(missing),Format.DOCX);
        var wrong=Fixtures.office(Format.DOCX);wrong.put("word/document.xml","<wrong/>".getBytes());rejected(Fixtures.zip(wrong),Format.DOCX);
        var types=Fixtures.office(Format.DOCX);types.put("[Content_Types].xml","<Wrong/>".getBytes());rejected(Fixtures.zip(types),Format.DOCX);
    }
    @Test void officeActivePackagesExternalRelationshipsAndNestedArchivesRejected() throws Exception {
        for(String name:List.of("word/vbaProject.bin","word/embeddings/object.bin","word/activeX/item.xml","nested.zip","evil.exe","page.html")) {
            var entries=Fixtures.office(Format.DOCX);entries.put(name,"untrusted".getBytes());rejected(Fixtures.zip(entries),Format.DOCX);
        }
        var entries=Fixtures.office(Format.DOCX);entries.put("_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"x\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" TargetMode=\"External\" Target=\"https://never.invalid/\"/></Relationships>".getBytes());rejected(Fixtures.zip(entries),Format.DOCX);
        var nested=Fixtures.office(Format.DOCX);nested.put("image.png",Fixtures.zip(Map.of("x.xml","<x/>".getBytes())));rejected(Fixtures.zip(nested),Format.DOCX);
        var macro=Fixtures.office(Format.DOCX);macro.put("[Content_Types].xml",new String(macro.get("[Content_Types].xml"),StandardCharsets.UTF_8).replace("wordprocessingml.document.main+xml","word.ms-word.document.macroEnabled.main+xml").getBytes());rejected(Fixtures.zip(macro),Format.DOCX);
    }
    @Test void xmlEntitiesIncludesMalformedAndDepthAttributeLimitsRejected() throws Exception {
        for(String xml:List.of("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>","<x xmlns:xi=\"http://www.w3.org/2001/XInclude\"><xi:include href=\"file:///etc/passwd\"/></x>","<x>","<x>".repeat(65)+"</x>".repeat(65),"<x "+java.util.stream.IntStream.range(0,101).mapToObj(i->"a"+i+"=\""+i+"\" ").collect(java.util.stream.Collectors.joining())+"/>")) {var entries=Fixtures.office(Format.DOCX);entries.put("extra.xml",xml.getBytes());rejected(Fixtures.zip(entries),Format.DOCX);}
    }
    @Test void zipTraversalDuplicateEncryptionSymlinkAndBombsRejected() throws Exception {
        for(String name:List.of("../escape.xml","/absolute.xml","a/../../escape.xml","a\\b.xml","C:evil.xml","a//b.xml","a/%2e.xml")) {var entries=Fixtures.office(Format.DOCX);entries.put(name,"<x/>".getBytes());rejected(Fixtures.zip(entries),Format.DOCX);}
        var bomb=Fixtures.office(Format.DOCX);bomb.put("bomb.xml",("<x>"+"a".repeat(100000)+"</x>").getBytes());rejected(Fixtures.zip(bomb),Format.DOCX);
        var duplicate=Fixtures.office(Format.DOCX);duplicate.put("a.xml","<x/>".getBytes());duplicate.put("b.xml","<x/>".getBytes());byte[] raw=Fixtures.zip(duplicate);for(int i=0;i<raw.length-5;i++)if(raw[i]=='b'&&raw[i+1]=='.'&&raw[i+2]=='x'&&raw[i+3]=='m'&&raw[i+4]=='l')raw[i]='a';rejected(raw,Format.DOCX);
        for(boolean symlink:List.of(false,true)) {raw=Fixtures.zip(Fixtures.office(Format.DOCX));var buffer=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<raw.length-46;i++)if(buffer.getInt(i)==0x02014b50){if(symlink)buffer.putInt(i+38,0120777<<16);else buffer.putShort(i+8,(short)1);break;}rejected(raw,Format.DOCX);}
    }
    @Test void officeMalformedRelationshipsTypesReferencesAndDirectoryAccounting() throws Exception {
        var benign=Fixtures.office(Format.DOCX);benign.put("word/media/",new byte[0]);benign.put("word/media/image.png",MediaAndZipTest.image("png"));assertNotNull(inspect(Fixtures.zip(benign),Format.DOCX));
        for(String root:List.of("<Wrong/>","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"x\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"missing.xml\"/></Relationships>","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"x\" Type=\"https://evil.invalid/type\" Target=\"word/document.xml\"/></Relationships>","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"x\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/package\" Target=\"word/document.xml\"/></Relationships>","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship/></Relationships>")) {var entries=Fixtures.office(Format.DOCX);entries.put("_rels/.rels",root.getBytes());rejected(Fixtures.zip(entries),Format.DOCX);}
        for(String target:List.of("/absolute.xml","../escape.xml","https://evil.invalid/x","word/%64ocument.xml","word/document.xml#fragment")) {var entries=Fixtures.office(Format.DOCX);entries.put("_rels/.rels",new String(entries.get("_rels/.rels")).replace("word/document.xml",target).getBytes());rejected(Fixtures.zip(entries),Format.DOCX);}
        for(String content:List.of("application/x-executable","application/vnd.openxmlformats-officedocument.oleObject","application/vnd.openxmlformats-officedocument.activeX","application/vnd.ms-word.document.macroEnabled.main+xml")) {var entries=Fixtures.office(Format.DOCX);entries.put("[Content_Types].xml",new String(entries.get("[Content_Types].xml")).replace("application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",content).getBytes());rejected(Fixtures.zip(entries),Format.DOCX);}
        var missing=Fixtures.office(Format.DOCX);missing.remove("_rels/.rels");rejected(Fixtures.zip(missing),Format.DOCX);
        var folded=Fixtures.office(Format.DOCX);folded.put("WORD/DOCUMENT.XML","<x/>".getBytes());rejected(Fixtures.zip(folded),Format.DOCX);
        byte[] original=Fixtures.zip(Fixtures.office(Format.DOCX));for(int changed:List.of(4,10,12,16,20)) {byte[] raw=original.clone();int end=raw.length-22;raw[end+changed]=(byte)0xff;rejected(raw,Format.DOCX);}
        rejected(Arrays.copyOf(original,original.length-4),Format.DOCX);
    }
    @Test void bodyEmptyIoFailureAndOversizeCannotLeaveTemporaryContent() throws Exception {
        rejected(new byte[0],Format.PDF);
        assertThrows(InspectionFailure.class,()->inspector.inspect(new InputStream(){@Override public int read() throws IOException{throw new IOException("private diagnostic");}},Format.PDF));assertClean();
        assertThrows(InspectionFailure.class,()->inspector.inspect(new InputStream(){long remaining=100_000_001;public int read(){return remaining-->0?0:-1;}@Override public int read(byte[] bytes,int off,int len){if(remaining==0)return -1;int n=(int)Math.min(len,remaining);remaining-=n;return n;}},Format.PDF));assertClean();
    }
}
