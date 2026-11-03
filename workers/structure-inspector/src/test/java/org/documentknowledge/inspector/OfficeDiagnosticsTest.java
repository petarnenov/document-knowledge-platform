package org.documentknowledge.inspector;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import javax.xml.stream.XMLStreamException;
import org.documentknowledge.inspector.diagnostics.InspectorDiagnostics.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OfficeDiagnosticsTest {
    @TempDir Path temp;
    @Test void malformedXmlRetainsOriginalParserCauseForSanitization() throws Exception {
        var entries=Fixtures.office(Format.DOCX);entries.put("extra.xml","<broken>".getBytes());var port=new StructureDiagnosticsTest.Recording();
        var failure=assertThrows(InspectionFailure.class,()->new StructureInspector(temp,port).inspect(new ByteArrayInputStream(Fixtures.zip(entries)),Format.DOCX,Context.fresh()));
        assertEquals("INVALID_FILE",failure.code());assertInstanceOf(XMLStreamException.class,failure.getCause());assertSame(failure,port.one(Phase.OPERATION).failure);assertEquals(Outcome.SUCCEEDED,port.one(Phase.CLEANUP).outcome);
    }
    @Test void ordinaryInvalidContentTypeAndRelationshipDocumentsKeepSafeDecisions() throws Exception {
        String types="http://schemas.openxmlformats.org/package/2006/content-types",rels="http://schemas.openxmlformats.org/package/2006/relationships";
        for(String content:List.of("<Types/>","<Types xmlns='"+types+"'><Other/></Types>","<Types xmlns='"+types+"'><Default xmlns='wrong'/></Types>","<Types xmlns='"+types+"'><Override PartName='word/document.xml' ContentType='application/xml'/></Types>","<Types xmlns='"+types+"'><Override PartName='/word/document.xml' ContentType='application/xml'/><Override PartName='/word/document.xml' ContentType='application/xml'/></Types>","<Types xmlns='"+types+"'><Default Extension='xml' ContentType='application/xml'/><Default Extension='xml' ContentType='application/xml'/></Types>","<Types xmlns='"+types+"'><Override PartName='/word/document.xml' ContentType='application/vnd.openxmlformats-vba'/></Types>","<Types xmlns='"+types+"'><Default Extension=' ' ContentType='application/xml'/></Types>")) {
            var entries=Fixtures.office(Format.DOCX);entries.put("[Content_Types].xml",content.getBytes());assertThrows(InspectionFailure.class,()->new StructureInspector(temp).inspect(new ByteArrayInputStream(Fixtures.zip(entries)),Format.DOCX));
        }
        for(String content:List.of("<Relationships/>","<Relationships xmlns='"+rels+"'><Other/></Relationships>","<Relationships xmlns='"+rels+"'><Relationship xmlns='wrong'/></Relationships>")) {
            var entries=Fixtures.office(Format.DOCX);entries.put("_rels/.rels",content.getBytes());assertThrows(InspectionFailure.class,()->new StructureInspector(temp).inspect(new ByteArrayInputStream(Fixtures.zip(entries)),Format.DOCX));
        }
        var internal=Fixtures.office(Format.DOCX);internal.put("_rels/.rels",new String(internal.get("_rels/.rels")).replace(" Id="," TargetMode=\"Internal\" Id=").getBytes());assertNotNull(new StructureInspector(temp).inspect(new ByteArrayInputStream(Fixtures.zip(internal)),Format.DOCX));
    }
}
