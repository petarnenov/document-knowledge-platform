package org.documentknowledge.inspector;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.io.*;
import org.apache.pdfbox.pdfparser.PDFParser;

final class PdfInspector {
    private static final Set<String> FORBIDDEN=Set.of("AcroForm","XFA","JS","JavaScript","Launch","EmbeddedFiles","EmbeddedFile","EF","RichMedia","AA","OpenAction","SubmitForm","ImportData","GoToR","URI","Movie","Sound","3D","Rendition");
    private PdfInspector() { }
    private static final class StrictParser extends PDFParser {
        StrictParser(RandomAccessRead read,Path temporaryDirectory) throws IOException { super(read,"",null,null,()->new ScratchFile(MemoryUsageSetting.setupTempFileOnly(900_000_000).setTempDir(temporaryDirectory.toFile())));setLenient(false); }
        /** COSObject.getObject catches IOException and returns null. Never let a parse failure cross
         * that swallowing boundary as IOException; explicit COSNull remains a legitimate PDF value.
         */
        @Override protected COSBase parseDirObject() throws IOException {
            COSBase value=super.parseDirObject();
            if(value==null)throw new InspectionFailure("INVALID_FILE");
            return value;
        }
        @Override protected synchronized COSBase parseObjectDynamically(COSObjectKey key,boolean required) throws IOException {
            try {
                COSBase value=super.parseObjectDynamically(key,required);
                if(value==null && document.getXrefTable().containsKey(key))throw new InspectionFailure("INVALID_FILE");
                return value;
            }catch(IOException malformed){throw new InspectionFailure("INVALID_FILE");}
        }

    }
    static int inspect(Path path) throws IOException {
        try(var header=Files.newInputStream(path)) { if(!Arrays.equals(header.readNBytes(5),new byte[]{'%','P','D','F','-'}))throw new InspectionFailure("INVALID_FILE"); }
        try(var read=new RandomAccessReadBufferedFile(path.toFile());var document=new StrictParser(read,path.getParent()).parse(false)) {
            if(document.isEncrypted())throw new InspectionFailure("UNSUPPORTED_CONTENT");
            var cos=document.getDocument();
            if(cos.getXrefTable().size()>100000)throw new InspectionFailure("STRUCTURE_LIMIT");
            Set<COSBase> seen=Collections.newSetFromMap(new IdentityHashMap<>());
            walk(cos.getTrailer(),0,seen);
            for(var key:cos.getXrefTable().keySet())walk(cos.getObjectFromPool(key),0,seen);
            int pages=document.getNumberOfPages();if(pages<1 || pages>1000)throw new InspectionFailure("STRUCTURE_LIMIT");
            return pages;
        }
    }
    static void walk(COSBase value,int depth,Set<COSBase> seen) {
        if(value==null || seen.contains(value))return;
        if(depth>64 || seen.size()>1_000_000)throw new InspectionFailure("STRUCTURE_LIMIT");
        seen.add(value);
        if(value instanceof COSObject object)walk(object.getObject(),depth+1,seen);
        else if(value instanceof COSDictionary dictionary) {
            for(var entry:dictionary.entrySet()) {
                if(FORBIDDEN.contains(entry.getKey().getName()))throw new InspectionFailure("UNSUPPORTED_CONTENT");
                if(entry.getValue() instanceof COSName name && FORBIDDEN.contains(name.getName()))throw new InspectionFailure("UNSUPPORTED_CONTENT");
                if(entry.getKey().getName().equals("S") && entry.getValue() instanceof COSName action && !action.getName().equals("GoTo"))throw new InspectionFailure("UNSUPPORTED_CONTENT");
                walk(entry.getValue(),depth+1,seen);
            }
        } else if(value instanceof COSArray array)for(COSBase item:array)walk(item,depth+1,seen);
    }
}
