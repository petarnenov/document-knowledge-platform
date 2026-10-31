package org.documentknowledge.inspector;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.apache.pdfbox.pdmodel.*;

final class Fixtures {
    static byte[] pdf(int pages) throws IOException {try(var document=new PDDocument();var out=new ByteArrayOutputStream()){for(int i=0;i<pages;i++)document.addPage(new PDPage());document.save(out);return out.toByteArray();}}
    static Map<String,byte[]> office(Format format) {
        String type=switch(format){case DOCX->"wordprocessingml.document";case PPTX->"presentationml.presentation";case XLSX->"spreadsheetml.sheet";default->throw new IllegalArgumentException();};
        var entries=new LinkedHashMap<String,byte[]>();
        entries.put("[Content_Types].xml",("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/"+format.mainPart+"\" ContentType=\"application/vnd.openxmlformats-officedocument."+type+".main+xml\"/></Types>").getBytes(StandardCharsets.UTF_8));
        entries.put("_rels/.rels",("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\""+format.mainPart+"\"/></Relationships>").getBytes(StandardCharsets.UTF_8));
        entries.put(format.mainPart,("<x:"+format.root+" xmlns:x=\""+format.namespace+"\">"+switch(format){case DOCX->"<x:body><x:p/></x:body>";case PPTX->"<x:sldMasterIdLst/><x:sldIdLst/><x:sldSz cx=\"9144000\" cy=\"6858000\"/><x:notesSz cx=\"6858000\" cy=\"9144000\"/>";case XLSX->"<x:sheets/>";default->"";}+"</x:"+format.root+">").getBytes(StandardCharsets.UTF_8));return entries;
    }
    static byte[] zip(Map<String,byte[]> entries) throws IOException {var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out)){for(var entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}return out.toByteArray();}
}
