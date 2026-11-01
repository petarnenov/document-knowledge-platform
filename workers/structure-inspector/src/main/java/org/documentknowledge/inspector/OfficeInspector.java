package org.documentknowledge.inspector;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.stream.*;

/** Bounded ZIP/package inspection. Neither XML resolution nor Office execution is available. */
final class OfficeInspector {
    private static final long MAX_EXPANDED=512_000_000, MAX_XML=32_000_000;
    private static final String TYPES="http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String RELS="http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String OFFICE_REL="http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
    private static final Set<String> BAD_EXT=Set.of("bin","exe","dll","com","js","vbs","ps1","zip","jar","7z","rar","pdf","doc","xls","ppt","docm","pptm","xlsm","html","htm");
    private OfficeInspector() { }
    static void inspect(Path path,Format format) throws IOException {
        Set<String> central=StrictZip.validate(path);
        try(ZipFile zip=new ZipFile(path.toFile(),StandardCharsets.UTF_8)) {
            Set<String> names=new HashSet<>();Set<String> xml=new HashSet<>();long total=0;int count=0;
            var entries=zip.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry=entries.nextElement();String name=entry.getName();canonical(name);
                if(!names.add(name) || ++count>10000)fail("STRUCTURE_LIMIT");
                if(entry.isDirectory())continue;
                String lower=name.toLowerCase(Locale.ROOT);String ext=lower.substring(lower.lastIndexOf('.')+1);
                if(BAD_EXT.contains(ext) || lower.contains("vbaproject") || lower.contains("activex") || lower.contains("embeddings/"))fail("UNSUPPORTED_CONTENT");
                boolean isXml=lower.endsWith(".xml") || lower.endsWith(".rels");
                long expanded=0;CRC32 crc=new CRC32();
                try(InputStream in=zip.getInputStream(entry)) {
                    byte[] chunk=new byte[65536];int n;boolean first=true;
                    while((n=in.read(chunk))!=-1) {
                        if(first && n>=4 && chunk[0]=='P' && chunk[1]=='K' && chunk[2]==3 && chunk[3]==4)fail("UNSUPPORTED_CONTENT");first=false;
                        crc.update(chunk,0,n);expanded+=n;total+=n;
                        if(total>MAX_EXPANDED || (isXml && expanded>MAX_XML) || expanded>Math.max(1,entry.getCompressedSize())*100L)fail("STRUCTURE_LIMIT");
                    }
                }
                if(expanded!=entry.getSize() || crc.getValue()!=entry.getCrc())fail("INVALID_FILE");
                if(isXml)xml.add(name);
                else {
                    if(!Set.of("png","jpeg","jpg","gif","tif","tiff").contains(ext))fail("UNSUPPORTED_CONTENT");
                    Path media=Files.createTempFile(path.getParent(),"media-",".tmp");
                    try {
                        try(var in=zip.getInputStream(entry);var out=Files.newOutputStream(media)){in.transferTo(out);}
                        total+=MediaInspector.inspect(media,ext);if(total>MAX_EXPANDED)fail("STRUCTURE_LIMIT");
                    }finally{Files.deleteIfExists(media);}
                }
            }
            if(!names.equals(central) || !xml.contains("[Content_Types].xml") || !xml.contains("_rels/.rels") || !xml.contains(format.mainPart))fail("INVALID_FILE");
            Map<String,String> contentTypes=new HashMap<>();Set<String> defaults=new HashSet<>();boolean[] mainLinked={false};
            for(String name:xml) {
                byte[] bytes;try(InputStream in=zip.getInputStream(zip.getEntry(name))) { bytes=in.readNBytes((int)MAX_XML+1); }
                if(bytes.length>MAX_XML)fail("STRUCTURE_LIMIT");
                parse(bytes,(reader,depth)-> {
                    String local=reader.getLocalName(),ns=reader.getNamespaceURI();
                    if(name.equals("[Content_Types].xml")) {
                        if(depth==1 && (!local.equals("Types") || !TYPES.equals(ns)))fail("INVALID_FILE");
                        if(depth==2) {
                            if(!TYPES.equals(ns) || !Set.of("Override","Default").contains(local))fail("INVALID_FILE");
                            String type=attribute(reader,"ContentType");String lower=type.toLowerCase(Locale.ROOT);
                            if(lower.contains("macro") || lower.contains("oleobject") || lower.contains("activex") || lower.contains("vba") || !(type.startsWith("application/vnd.openxmlformats-") || Set.of("application/xml","application/vnd.openxmlformats-package.relationships+xml","image/png","image/jpeg","image/gif","image/tiff").contains(type)))fail("UNSUPPORTED_CONTENT");
                            if(local.equals("Override")) {String target=attribute(reader,"PartName");if(!target.startsWith("/"))fail("INVALID_FILE");canonical(target.substring(1));if(contentTypes.put(target.substring(1),type)!=null)fail("INVALID_FILE");}
                            else if(!defaults.add(attribute(reader,"Extension")))fail("INVALID_FILE");
                        }
                    } else if(name.endsWith(".rels")) {
                        if(depth==1 && (!local.equals("Relationships") || !RELS.equals(ns)))fail("INVALID_FILE");
                        if(depth==2) {
                            if(!local.equals("Relationship") || !RELS.equals(ns))fail("INVALID_FILE");
                            String mode=reader.getAttributeValue(null,"TargetMode");if(mode!=null && !mode.equals("Internal"))fail("UNSUPPORTED_CONTENT");
                            String type=attribute(reader,"Type"),target=attribute(reader,"Target");
                            if(!type.startsWith(OFFICE_REL) && !type.startsWith("http://schemas.openxmlformats.org/package/2006/relationships/"))fail("UNSUPPORTED_CONTENT");
                            String kind=type.substring(type.lastIndexOf('/')+1).toLowerCase(Locale.ROOT);
                            if(Set.of("oleobject","package","control","vbaproject","attachedtemplate","externalLink".toLowerCase(Locale.ROOT)).contains(kind))fail("UNSUPPORTED_CONTENT");
                            String resolved=resolveRelationship(name,target);if(!names.contains(resolved))fail("INVALID_FILE");
                            if(name.equals("_rels/.rels") && type.equals(OFFICE_REL+"officeDocument")) {if(!resolved.equals(format.mainPart) || mainLinked[0])fail("INVALID_FILE");mainLinked[0]=true;}
                        }
                    } else if(name.equals(format.mainPart) && depth==1 && (!local.equals(format.root) || !format.namespace.equals(ns)))fail("INVALID_FILE");
                    if("http://www.w3.org/2001/XInclude".equals(ns))fail("UNSUPPORTED_CONTENT");
                });
            }
            String mainType=switch(format) {
                case DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
                case PPTX -> "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml";
                case XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";
                default -> throw new InspectionFailure("INVALID_FILE");
            };
            if(!mainLinked[0] || !mainType.equals(contentTypes.get(format.mainPart)))fail("INVALID_FILE");
            for(String part:contentTypes.keySet())if(!names.contains(part))fail("INVALID_FILE");
        }
    }
    private interface ElementCheck { void check(XMLStreamReader reader,int depth); }
    private static void parse(byte[] bytes,ElementCheck check) {
        XMLInputFactory factory=XMLInputFactory.newFactory();factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);factory.setProperty("javax.xml.stream.isSupportingExternalEntities",false);factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES,false);
        factory.setXMLResolver((publicId,systemId,base,namespace)->{throw new XMLStreamException("external resolution denied");});
        try {
            XMLStreamReader reader=factory.createXMLStreamReader(new ByteArrayInputStream(bytes));int depth=0,roots=0;
            try {
                while(reader.hasNext()) {
                    int event=reader.next();
                    if(event==XMLStreamConstants.DTD || event==XMLStreamConstants.ENTITY_REFERENCE)fail("UNSUPPORTED_CONTENT");
                    if(event==XMLStreamConstants.START_ELEMENT) {if(++depth>64 || reader.getAttributeCount()>100)fail("STRUCTURE_LIMIT");if(depth==1 && ++roots>1)fail("INVALID_FILE");check.check(reader,depth);}
                    else if(event==XMLStreamConstants.END_ELEMENT)depth--;
                }
                if(roots!=1 || depth!=0)fail("INVALID_FILE");
            } finally { reader.close(); }
        } catch(XMLStreamException malformed) { fail("INVALID_FILE"); }
    }
    private static String attribute(XMLStreamReader reader,String name) {String value=reader.getAttributeValue(null,name);if(value==null || value.isBlank())throw new InspectionFailure("INVALID_FILE");return value;}
    private static String resolveRelationship(String name,String target) {
        if(target.startsWith("/") || target.contains(":") || target.contains("\\") || target.contains("%") || target.contains("#"))throw new InspectionFailure("UNSUPPORTED_CONTENT");
        String parent=name.equals("_rels/.rels")?"":name.substring(0,name.lastIndexOf("/_rels/")+1);
        Path resolved=Path.of(parent).resolve(target).normalize();String result=resolved.toString().replace('\\','/');canonical(result);return result;
    }
    static void canonical(String name) {
        String trimmed=name.endsWith("/")?name.substring(0,name.length()-1):name;
        if(trimmed.isEmpty() || trimmed.length()>1024 || trimmed.startsWith("/") || trimmed.contains("\\") || trimmed.contains(":") || trimmed.contains("%") || trimmed.chars().anyMatch(c->c<32 || c==127))fail("INVALID_FILE");
        for(String part:trimmed.split("/",-1))if(part.isEmpty() || part.equals(".") || part.equals(".."))fail("INVALID_FILE");
    }
    private static void fail(String code) { throw new InspectionFailure(code); }
}
