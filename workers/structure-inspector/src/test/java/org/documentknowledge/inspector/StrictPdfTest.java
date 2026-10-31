package org.documentknowledge.inspector;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StrictPdfTest {
    @TempDir Path temp;
    @Test void malformedLazyIndirectObjectsNeverBecomeSuccessfulNull()throws Exception {
        for(String object:List.of("not-a-valid-PDF-object","<< /Broken [1 2 >>","[1 2","<< /Length 10 >>\nstream\nshort")){
            Path file=Files.write(temp.resolve("source.pdf"),pdf(object));
            assertThrows(Exception.class,()->PdfInspector.inspect(file),"Referenced xref object must fail: "+object);
        }
    }
    @Test void explicitIndirectNullIsValidAndReferencedUndefinedNullRemainsDistinct()throws Exception {
        assertEquals(1,PdfInspector.inspect(Files.write(temp.resolve("null.pdf"),pdf("null"))));
        assertEquals(1,PdfInspector.inspect(Files.write(temp.resolve("undefined-null.pdf"),pdf("<< /Optional 999999 0 R >>"))));
        // Undefined indirect references are PDF null by specification; do not reject a legitimate COSNull.
        try(var document=new PDDocument();var out=new ByteArrayOutputStream()) {
            document.addPage(new PDPage());document.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("Optional"),COSNull.NULL);document.save(out);
            assertEquals(1,PdfInspector.inspect(Files.write(temp.resolve("direct-null.pdf"),out.toByteArray())));
        }
    }
    @Test void malformedCompressedObjectStreamCannotBeSwallowed()throws Exception {
        byte[] valid=Fixtures.pdf(1);String source=new String(valid,StandardCharsets.ISO_8859_1);
        assertTrue(source.contains("/ObjStm"),"Pinned PDFBox emits a real compressed-object fixture");
        // A corrupt compressed stream/filter must fail through strict lazy dereference.
        byte[] broken=valid.clone();int at=source.indexOf("/FlateDecode");assertTrue(at>0);broken[at+1]='X';
        assertThrows(Exception.class,()->PdfInspector.inspect(Files.write(temp.resolve("compressed.pdf"),broken)));
    }
    private static byte[] pdf(String extra)throws IOException {
        var out=new ByteArrayOutputStream();out.write("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII));var offsets=new ArrayList<Integer>();
        String[] objects={"<< /Type /Catalog /Pages 2 0 R >>","<< /Type /Pages /Kids [3 0 R] /Count 1 >>","<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /Resources <<>> >>",extra};
        for(int i=0;i<objects.length;i++){offsets.add(out.size());out.write(((i+1)+" 0 obj\n"+objects[i]+"\nendobj\n").getBytes(StandardCharsets.US_ASCII));}
        int xref=out.size();out.write("xref\n0 5\n0000000000 65535 f \n".getBytes(StandardCharsets.US_ASCII));for(int offset:offsets)out.write(String.format(Locale.ROOT,"%010d 00000 n \n",offset).getBytes(StandardCharsets.US_ASCII));
        out.write(("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n"+xref+"\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));return out.toByteArray();
    }
}
