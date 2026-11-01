package org.documentknowledge.knowledgebase.adapters.inbound;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.documentknowledge.knowledgebase.application.KnowledgeService;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.documentknowledge.knowledgebase.domain.KbModels.*;
import org.documentknowledge.knowledgebase.domain.KbModels.Collection;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Independently closed wire boundary. Domain records are never serialized implicitly. */
final class KnowledgeWire {
    static final int REQUEST_LIMIT=16384,RESPONSE_LIMIT=2097152;
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private KnowledgeWire() { }
    static KbFailure invalid(){return new KbFailure(400,"BAD_REQUEST");}
    static Map<?,?> decode(byte[] bytes,String type){
        if(type==null||!type.split(";",2)[0].strip().equalsIgnoreCase("application/json"))throw new KbFailure(415,"UNSUPPORTED_MEDIA_TYPE");
        if(bytes==null||bytes.length==0)throw invalid();if(bytes.length>REQUEST_LIMIT)throw new KbFailure(413,"PAYLOAD_TOO_LARGE");
        try{return map(JSON.readValue(StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString(),Object.class));}catch(KbFailure failure){throw failure;}catch(java.nio.charset.CharacterCodingException|RuntimeException malformed){throw invalid();}
    }
    static byte[] encode(Object value){try{byte[] bytes=JSON.writeValueAsBytes(value);if(bytes.length>RESPONSE_LIMIT)throw KbFailure.unavailable();return bytes;}catch(KbFailure failure){throw failure;}catch(RuntimeException malformed){throw KbFailure.unavailable();}}
    static Map<?,?> map(Object value){if(!(value instanceof Map<?,?> map))throw invalid();return map;}
    static void keys(Map<?,?> value,String...names){if(!value.keySet().equals(Set.of(names)))throw invalid();}
    static String text(Object value,int min,int max){if(!(value instanceof String text)||text.length()<min||text.length()>max)throw invalid();return text;}
    static long revision(Object value){if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<0||((Number)value).longValue()>9007199254740991L)throw invalid();return ((Number)value).longValue();}
    static UUID id(Object value){String text=text(value,36,36);if(!text.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid();return UUID.fromString(text);}
    static String token(Object value){String text=text(value,43,43);if(!text.matches("[A-Za-z0-9_-]{43}"))throw invalid();return text;}
    static Metadata metadata(Object value){var map=map(value);keys(map,"title","description");return new Metadata(text(map.get("title"),1,256),text(map.get("description"),0,4096));}
    static Principal principal(Object value){var map=map(value);keys(map,"issuer","subject");String issuer=text(map.get("issuer"),1,2048);try{if(!URI.create(issuer).isAbsolute())throw invalid();}catch(IllegalArgumentException malformed){throw invalid();}return new Principal(issuer,text(map.get("subject"),1,256));}
    static Action action(Object value){String wire=text(value,1,64);return Arrays.stream(Action.values()).filter(a->a.wire().equals(wire)).findFirst().orElseThrow(KnowledgeWire::invalid);}
    static <T extends Enum<T>>T enumeration(Class<T> type,Object value){try{return Enum.valueOf(type,text(value,1,32));}catch(IllegalArgumentException malformed){throw invalid();}}
    static KnowledgeService.EditMetadata update(Map<?,?> map){keys(map,"expectedRevision","metadata");return new KnowledgeService.EditMetadata(revision(map.get("expectedRevision")),metadata(map.get("metadata")));}
    static KnowledgeService.EditStatus status(Map<?,?> map){keys(map,"expectedRevision","status","reason");return new KnowledgeService.EditStatus(revision(map.get("expectedRevision")),enumeration(Status.class,map.get("status")),text(map.get("reason"),1,1024));}
    static KnowledgeService.EditAcl aclChange(Map<?,?> map){
        keys(map,"expectedRevision","mode","changes","reason");if(!(map.get("changes") instanceof List<?> entries)||entries.isEmpty()||entries.size()>100)throw invalid();
        var result=new ArrayList<AclChange>();for(Object entry:entries){var value=map(entry);keys(value,"operation","principal","action");result.add(new AclChange(enumeration(Delta.class,value.get("operation")),principal(value.get("principal")),action(value.get("action"))));}
        return new KnowledgeService.EditAcl(revision(map.get("expectedRevision")),enumeration(Mode.class,map.get("mode")),result,text(map.get("reason"),1,1024));
    }
    static KnowledgeService.EditAudience audienceChange(Map<?,?> map){
        keys(map,"expectedRevision","rules","reason");if(!(map.get("rules") instanceof List<?> entries)||entries.size()>100)throw invalid();var rules=new HashSet<AudienceRule>();
        for(Object entry:entries){var value=map(entry);keys(value,"effect","selector");var rule=new AudienceRule(enumeration(Effect.class,value.get("effect")),selector(value.get("selector")));if(!rules.add(rule))throw new KbFailure(422,"INVALID_POLICY");}
        return new KnowledgeService.EditAudience(revision(map.get("expectedRevision")),rules,text(map.get("reason"),1,1024));
    }
    private static Selector selector(Object raw){
        var value=map(raw);var kind=enumeration(SelectorKind.class,value.get("kind"));
        if(kind==SelectorKind.PRINCIPAL){keys(value,"kind","principal");var actor=principal(value.get("principal"));return new Selector(kind,actor.issuer(),actor.subject());}
        if(kind==SelectorKind.TENANT){keys(value,"kind","tenant");return new Selector(kind,null,text(value.get("tenant"),1,256));}
        String field=kind==SelectorKind.FIRM?"firmId":"resourceScopeId";keys(value,"kind","issuer",field);String issuer=text(value.get("issuer"),1,2048);try{if(!URI.create(issuer).isAbsolute())throw invalid();}catch(IllegalArgumentException malformed){throw invalid();}return new Selector(kind,issuer,text(value.get(field),1,256));
    }
    static KnowledgeService.AddMembership membership(Map<?,?> map){keys(map,"expectedRevision","documentId","expectedDocumentRevision","sharingScope","reason");if(!"SELECTED_TENANT".equals(map.get("sharingScope")))throw invalid();return new KnowledgeService.AddMembership(revision(map.get("expectedRevision")),id(map.get("documentId")),revision(map.get("expectedDocumentRevision")),text(map.get("reason"),1,1024));}
    static KnowledgeService.EditMembership membership(UUID id,Map<?,?> map){keys(map,"expectedRevision","expectedMembershipRevision","expectedDocumentRevision","state","reason");return new KnowledgeService.EditMembership(revision(map.get("expectedRevision")),id,revision(map.get("expectedMembershipRevision")),revision(map.get("expectedDocumentRevision")),enumeration(State.class,map.get("state")),text(map.get("reason"),1,1024));}
    static String hash(Map<?,?> map){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(canonical(map))));}catch(java.security.NoSuchAlgorithmException|RuntimeException failure){throw KbFailure.unavailable();}}
    private static Object canonical(Object value){if(value instanceof Map<?,?> map){var result=new TreeMap<String,Object>();map.forEach((key,item)->result.put((String)key,canonical(item)));return result;}if(value instanceof List<?> list)return list.stream().map(KnowledgeWire::canonical).toList();return value;}
    static Map<String,Object> receipt(Receipt receipt){var result=new LinkedHashMap<String,Object>();result.put("collectionId",receipt.collectionId().toString());if(receipt.membershipId()!=null)result.put("membershipId",receipt.membershipId().toString());result.put("revision",receipt.revision());return result;}
    static Map<String,Object> collection(Collection c){return Map.of("collectionId",c.collectionId().toString(),"revision",c.revision(),"metadata",Map.of("title",c.metadata().title(),"description",c.metadata().description()),"status",c.status().name(),"membershipRevision",c.membershipRevision(),"audiencePolicyRevision",c.audience().revision(),"classificationCeiling","development-restricted","classificationPolicyRevision",1);}
    static Map<String,Object> principal(Principal p){return Map.of("issuer",p.issuer(),"subject",p.subject());}
    static Map<String,Object> acl(Collection c){return Map.of("collectionId",c.collectionId().toString(),"revision",c.revision(),"rules",c.rules().stream().sorted(Comparator.comparing((AclRule r)->r.principal().issuer()).thenComparing(r->r.principal().subject()).thenComparing(r->r.action().wire()).thenComparing(r->r.effect().name())).map(r->Map.of("principal",principal(r.principal()),"action",r.action().wire(),"effect",r.effect().name())).toList());}
    private static Map<String,Object> selector(Selector s){return switch(s.kind()){case PRINCIPAL->Map.of("kind","PRINCIPAL","principal",Map.of("issuer",s.issuer(),"subject",s.value()));case TENANT->Map.of("kind","TENANT","tenant",s.value());case FIRM->Map.of("kind","FIRM","issuer",s.issuer(),"firmId",s.value());case RESOURCE_SCOPE->Map.of("kind","RESOURCE_SCOPE","issuer",s.issuer(),"resourceScopeId",s.value());};}
    static Map<String,Object> audience(AudiencePolicy p){return Map.of("collectionId",p.collectionId().toString(),"revision",p.revision(),"classificationCeiling","development-restricted","classificationPolicyRevision",1,"effectiveAt",p.effectiveAt().toString(),"rules",p.rules().stream().map(r->Map.of("effect",r.effect().name(),"selector",selector(r.selector()))).sorted(Comparator.comparing(m->new String(encode(m),StandardCharsets.UTF_8))).toList());}
    static Map<String,Object> membership(Membership m){return Map.of("membershipId",m.membershipId().toString(),"collectionId",m.collectionId().toString(),"documentId",m.documentId().toString(),"revision",m.revision(),"state",m.state().name(),"sharingScope","SELECTED_TENANT","creator",principal(m.creator()),"createdAt",m.createdAt().toString(),"reason",m.reason());}
    static Map<String,Object> page(List<?> items,String cursor){var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("nextCursor",cursor);return result;}
}
