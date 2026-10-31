package org.documentknowledge.documentmanagement.adapters.inbound;

import java.util.*;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.documentmanagement.domain.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Closed, independently validated direct API wire boundary. */
final class DocumentWire {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private DocumentWire() { }
    static DmFailure invalid(){return new DmFailure(400,"INVALID_REQUEST");}
    static Map<?,?> decode(byte[] bytes,String contentType) {
        if(contentType==null || !contentType.split(";",2)[0].strip().equalsIgnoreCase("application/json"))throw new DmFailure(415,"UNSUPPORTED_MEDIA_TYPE");
        if(bytes==null || bytes.length==0)throw invalid();
        if(bytes.length>16384)throw new DmFailure(413,"REQUEST_TOO_LARGE");
        try {Object value=JSON.readValue(bytes,Object.class);if(!(value instanceof Map<?,?> map))throw invalid();return map;}
        catch(DmFailure failure){throw failure;}catch(RuntimeException failure){throw invalid();}
    }
    static void keys(Map<?,?> map,String...keys){if(!map.keySet().equals(Set.of(keys)))throw invalid();}
    static Map<?,?> map(Object value){if(!(value instanceof Map<?,?> map))throw invalid();return map;}
    static String text(Object value,int minimum,int maximum){if(!(value instanceof String text) || text.length()<minimum || text.length()>maximum || (minimum>0 && text.isBlank()) || text.chars().anyMatch(Character::isISOControl))throw invalid();return text;}
    static long revision(Object value){if(!(value instanceof Integer || value instanceof Long) || ((Number)value).longValue()<0 || ((Number)value).longValue()>9007199254740991L)throw invalid();return ((Number)value).longValue();}
    static UUID id(String value){if(value==null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value);}
    static Metadata metadata(Object value){Map<?,?> map=map(value);keys(map,"title","description");try{return new Metadata(text(map.get("title"),1,256),text(map.get("description"),0,4096));}catch(IllegalArgumentException failure){throw invalid();}}
    static Metadata draft(Map<?,?> map){keys(map,"metadata","classification","classificationPolicyRevision");if(!"development-restricted".equals(map.get("classification")) || revision(map.get("classificationPolicyRevision"))!=1)throw invalid();return metadata(map.get("metadata"));}
    static AclChange acl(Map<?,?> map){
        keys(map,"expectedRevision","mode","changes");revision(map.get("expectedRevision"));
        if(!(map.get("changes") instanceof List<?> entries) || entries.isEmpty() || entries.size()>100)throw invalid();
        try {
            var result=new ArrayList<AclChange.Entry>();
            for(Object value:entries){var entry=map(value);keys(entry,"operation","principal","action");var principal=map(entry.get("principal"));keys(principal,"issuer","subject");String issuer=text(principal.get("issuer"),1,2048);var uri=java.net.URI.create(issuer);if(!uri.isAbsolute())throw invalid();result.add(new AclChange.Entry(AclChange.Operation.valueOf(text(entry.get("operation"),1,32)),new Principal(issuer,text(principal.get("subject"),1,256)),Action.parse(text(entry.get("action"),1,64))));}
            return new AclChange(AclChange.Mode.valueOf(text(map.get("mode"),1,16)),result);
        }catch(IllegalArgumentException failure){throw invalid();}
    }
    static Map<String,Object> receipt(CommandReceipt receipt){return Map.of("documentId",receipt.documentId().toString(),"revision",receipt.revision());}
    static Map<String,Object> principal(Principal principal){return Map.of("issuer",principal.issuer(),"subject",principal.subject());}
    static Map<String,Object> document(DocumentAggregate document){
        Map<String,Object> result=new LinkedHashMap<>();result.put("documentId",document.documentId().toString());result.put("tenant",document.tenant());result.put("creator",principal(document.creator()));result.put("createdAt",document.createdAt().toString());result.put("revision",document.revision());result.put("lifecycle",document.lifecycle());result.put("metadata",Map.of("title",document.metadata().title(),"description",document.metadata().description()));result.put("classification",document.classification());result.put("classificationPolicyRevision",document.classificationPolicyRevision());result.put("activeVersionId",document.activeVersionId()==null?null:document.activeVersionId().toString());
        Map<String,Object> retention=new LinkedHashMap<>();retention.put("policyRef",document.retentionPolicyRef());retention.put("policyRevision",document.retentionPolicyRevision());retention.put("resolution",document.retentionResolution());retention.put("retainUntil",null);retention.put("destructiveEligible",document.destructiveEligible());result.put("retention",retention);return result;
    }
    static Map<String,Object> acl(DocumentAggregate document){return Map.of("documentId",document.documentId().toString(),"revision",document.revision(),"rules",document.rules().stream().sorted(Comparator.comparing((AclRule r)->r.principal().issuer()).thenComparing(r->r.principal().subject()).thenComparing(r->r.action().wire()).thenComparing(r->r.effect().name())).map(r->Map.of("principal",principal(r.principal()),"action",r.action().wire(),"effect",r.effect().name())).toList());}
    static Map<String,Object> holds(DocumentAggregate document){return Map.of("items",document.holds().stream().map(h->Map.of("holdId",h.holdId().toString(),"documentId",document.documentId().toString(),"revision",document.revision(),"active",true,"reason",h.reason(),"placedBy",principal(h.placedBy()),"placedAt",h.placedAt().toString())).toList());}
}
