package org.documentknowledge.browserbff.application;

import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Predicate;

/** Closed mirror of the approved KB schemas. No caller supplies an upstream or actor. */
public final class KnowledgeWire {
    private final KnowledgeUriSyntax uris;
    public KnowledgeWire(KnowledgeUriSyntax uris) { this.uris=Objects.requireNonNull(uris); }
    public record Route(String request, int status, String response, boolean idempotent) { }
    public static Route route(String method, String path) {
        for (String segment : path.split("/")) if(segment.length()==36) try { if(!UUID.fromString(segment).toString().equals(segment)) throw new IllegalArgumentException(); } catch(IllegalArgumentException bad){throw new BffFailure(404,"NOT_FOUND");}
        if (method.equals("GET") && path.matches("/api/v1/knowledge/collections")) return new Route("",200,"CollectionPage",false);
        if (method.equals("POST") && path.matches("/api/v1/knowledge/collections")) return new Route("CreateCollection",201,"CollectionReceipt",true);
        if (method.equals("GET") && path.matches("/api/v1/knowledge/collections/[0-9a-f-]{36}")) return new Route("",200,"Collection",false);
        if (method.equals("PATCH") && path.matches("/api/v1/knowledge/collections/[0-9a-f-]{36}")) return new Route("UpdateCollection",200,"CollectionReceipt",true);
        if (method.equals("PATCH") && path.matches("/api/v1/knowledge/collections/[0-9a-f-]{36}/status")) return new Route("UpdateStatus",200,"CollectionReceipt",true);
        if (method.equals("GET") && path.matches("/api/v1/knowledge/collections/[0-9a-f-]{36}/acl")) return new Route("",200,"Acl",false);
        if (method.equals("PATCH") && path.matches("/api/v1/knowledge/collections/[0-9a-f-]{36}/acl")) return new Route("UpdateAcl",200,"CollectionReceipt",true);
        if (method.equals("GET") && path.matches("/api/v1/knowledge/collections/[0-9a-f-]{36}/audience")) return new Route("",200,"AudiencePolicy",false);
        if (method.equals("PUT") && path.matches("/api/v1/knowledge/collections/[0-9a-f-]{36}/audience")) return new Route("UpdateAudience",200,"CollectionReceipt",true);
        if (method.equals("POST") && path.matches("/api/v1/knowledge/collections/[0-9a-f-]{36}/audience-evaluations")) return new Route("EvaluateAudience",200,"AudienceEvaluation",false);
        if (method.equals("GET") && path.matches("/api/v1/knowledge/collections/[0-9a-f-]{36}/memberships")) return new Route("",200,"MembershipPage",false);
        if (method.equals("POST") && path.matches("/api/v1/knowledge/collections/[0-9a-f-]{36}/memberships")) return new Route("CreateMembership",201,"MembershipReceipt",true);
        if (method.equals("PATCH") && path.matches("/api/v1/knowledge/collections/[0-9a-f-]{36}/memberships/[0-9a-f-]{36}")) return new Route("UpdateMembership",200,"MembershipReceipt",true);
        if (method.equals("POST") && path.matches("/api/v1/knowledge/documents/[0-9a-f-]{36}/policy-snapshots")) return new Route("CreatePolicySnapshot",201,"PolicySnapshot",false);
        if (method.equals("POST") && path.matches("/api/v1/knowledge/documents/[0-9a-f-]{36}/policy-validations")) return new Route("ValidatePolicy",200,"PolicyValidation",false);
        throw new BffFailure(404,"NOT_FOUND");
    }
    public Route request(String method,String path,Map<String,String> query,Object body,String key) {
        Route route=route(method,path);
        boolean page=method.equals("GET") && (path.equals("/api/v1/knowledge/collections")||path.endsWith("/memberships"));
        if(!page && !query.isEmpty() || page && !Set.of("limit","cursor").containsAll(query.keySet())) throw new BffFailure(400,"BAD_REQUEST");
        if(query.containsKey("limit") && !query.get("limit").matches("[1-9]|[1-4][0-9]|50") || query.containsKey("cursor") && !text(query.get("cursor"),1,2048)) throw new BffFailure(400,"BAD_REQUEST");
        if(route.idempotent && (key==null||!key.matches("[!-~]{1,128}"))) throw new BffFailure(400,"BAD_REQUEST");
        if(route.request.isEmpty()?body!=null:!validate(route.request,body)) throw new BffFailure(400,"BAD_REQUEST");
        return route;
    }
    public boolean validate(String schema,Object value) {
        if(value==null)return false;
        return switch(schema) {
            case "Actor" -> validActor(value);
            case "KnowledgeAction" -> validKnowledgeAction(value);
            case "Error" -> validError(value);
            case "CollectionReceipt" -> validCollectionReceipt(value);
            case "MembershipReceipt" -> validMembershipReceipt(value);
            case "CollectionMetadata" -> validCollectionMetadata(value);
            case "CreateCollection" -> validCreateCollection(value);
            case "UpdateCollection" -> validUpdateCollection(value);
            case "CollectionStatus" -> validCollectionStatus(value);
            case "UpdateStatus" -> validUpdateStatus(value);
            case "Collection" -> validCollection(value);
            case "CollectionPage" -> validCollectionPage(value);
            case "AudienceSelector" -> validAudienceSelector(value);
            case "AudienceEvaluation" -> validAudienceEvaluation(value);
            case "EvaluateAudience" -> validEvaluateAudience(value);
            case "AudienceRule" -> validAudienceRule(value);
            case "AudiencePolicy" -> validAudiencePolicy(value);
            case "UpdateAudience" -> validUpdateAudience(value);
            case "AclRule" -> validAclRule(value);
            case "Acl" -> validAcl(value);
            case "AclChangeEntry" -> validAclChangeEntry(value);
            case "UpdateAcl" -> validUpdateAcl(value);
            case "CreateMembership" -> validCreateMembership(value);
            case "UpdateMembership" -> validUpdateMembership(value);
            case "Membership" -> validMembership(value);
            case "MembershipPage" -> validMembershipPage(value);
            case "CreatePolicySnapshot" -> validCreatePolicySnapshot(value);
            case "PolicyDecision" -> validPolicyDecision(value);
            case "PolicySnapshot" -> validPolicySnapshot(value);
            case "ValidatePolicy" -> validValidatePolicy(value);
            case "PolicyValidation" -> validPolicyValidation(value);
            default -> false;
        };
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object v){return (Map<String,Object>)v;}
    private static boolean object(Object v,Set<String> fields,Set<String> required){return v instanceof Map<?,?> m && fields.containsAll(m.keySet())&&m.keySet().containsAll(required);}
    private static boolean text(Object v,int min,int max){return v instanceof String s && s.codePointCount(0,s.length())>=min && s.codePointCount(0,s.length())<=max;}
    private static boolean number(Object v,long min,long max){if(!(v instanceof Number n))return false;try {long l=new BigDecimal(n.toString()).longValueExact();return l>=min&&l<=max;}catch(NumberFormatException|ArithmeticException invalid){return false;}}
    private boolean uri(Object v){return uris.isAbsolute((String)v);}
    private static boolean instant(Object v){try{OffsetDateTime.parse((String)v);return true;}catch(java.time.format.DateTimeParseException invalid){return false;}}
    private static boolean array(Object v,int min,int max,boolean unique,Predicate<Object> predicate){return v instanceof List<?> a && a.size()>=min&&a.size()<=max&&(!unique||new HashSet<>(a).size()==a.size())&&a.stream().allMatch(predicate);}
    private boolean validActor(Object value) { return (object(value, Set.of("issuer","subject"), Set.of("issuer","subject")) && (text(map(value).get("issuer"), 1, 2048) && uri(map(value).get("issuer"))) && (text(map(value).get("subject"), 1, 256))); }
    private boolean validKnowledgeAction(Object value) { return (value != null && Set.of("knowledge.collection.create","knowledge.collection.inspect","knowledge.collection.update","knowledge.collection.archive","knowledge.acl.restrict","knowledge.acl.delegate","knowledge.audience.manage","knowledge.membership.manage","knowledge.policy.evaluate").contains(value)); }
    private boolean validError(Object value) { return (object(value, Set.of("code"), Set.of("code")) && (map(value).get("code") != null && Set.of("BAD_REQUEST","UNAUTHENTICATED","FORBIDDEN","NOT_FOUND","STALE_REVISION","IDEMPOTENCY_CONFLICT","STALE_POLICY","INVALID_POLICY","DUPLICATE_ACL_CHANGE","ACL_RULE_LIMIT","RESTRICT_SCOPE","TARGET_NOT_GRANTABLE","DELEGATION_CEILING","LIMIT_EXCEEDED","UNAVAILABLE","TOO_MANY_REQUESTS","PAYLOAD_TOO_LARGE","UNSUPPORTED_MEDIA_TYPE","NOT_ACCEPTABLE","DUPLICATE_DOCUMENT","STALE_AUTHORITY").contains(map(value).get("code")))); }
    private boolean validCollectionReceipt(Object value) { return (object(value, Set.of("collectionId","revision"), Set.of("collectionId","revision")) && (text(map(value).get("collectionId"), 0, 100000) && ((String)map(value).get("collectionId")).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) && (number(map(value).get("revision"), 0L, 9007199254740991L))); }
    private boolean validMembershipReceipt(Object value) { return (object(value, Set.of("collectionId","membershipId","revision"), Set.of("collectionId","membershipId","revision")) && (text(map(value).get("collectionId"), 0, 100000) && ((String)map(value).get("collectionId")).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) && (text(map(value).get("membershipId"), 0, 100000) && ((String)map(value).get("membershipId")).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) && (number(map(value).get("revision"), 0L, 9007199254740991L))); }
    private boolean validCollectionMetadata(Object value) { return (object(value, Set.of("title","description"), Set.of("title","description")) && (text(map(value).get("title"), 1, 256)) && (text(map(value).get("description"), 0, 4096))); }
    private boolean validCreateCollection(Object value) { return validCollectionMetadata(value); }
    private boolean validUpdateCollection(Object value) { return (object(value, Set.of("expectedRevision","metadata"), Set.of("expectedRevision","metadata")) && (number(map(value).get("expectedRevision"), 0L, 9007199254740991L)) && validCollectionMetadata(map(value).get("metadata"))); }
    private boolean validCollectionStatus(Object value) { return (value != null && Set.of("ACTIVE","ARCHIVED").contains(value)); }
    private boolean validUpdateStatus(Object value) { return (object(value, Set.of("expectedRevision","status","reason"), Set.of("expectedRevision","status","reason")) && (number(map(value).get("expectedRevision"), 0L, 9007199254740991L)) && validCollectionStatus(map(value).get("status")) && (text(map(value).get("reason"), 1, 1024))); }
    private boolean validCollection(Object value) { return (object(value, Set.of("collectionId","revision","metadata","status","membershipRevision","audiencePolicyRevision","classificationCeiling","classificationPolicyRevision"), Set.of("collectionId","revision","metadata","status","membershipRevision","audiencePolicyRevision","classificationCeiling","classificationPolicyRevision")) && (text(map(value).get("collectionId"), 0, 100000) && ((String)map(value).get("collectionId")).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) && (number(map(value).get("revision"), 0L, 9007199254740991L)) && validCollectionMetadata(map(value).get("metadata")) && validCollectionStatus(map(value).get("status")) && (number(map(value).get("membershipRevision"), 0L, 9007199254740991L)) && (number(map(value).get("audiencePolicyRevision"), 1L, 9007199254740991L)) && Objects.equals(map(value).get("classificationCeiling"), "development-restricted") && number(map(value).get("classificationPolicyRevision"), 1, 1)); }
    private boolean validCollectionPage(Object value) { return (object(value, Set.of("items","nextCursor"), Set.of("items","nextCursor")) && (array(map(value).get("items"), 0, 50, false, item -> validCollection(item))) && (map(value).get("nextCursor") == null || (text(map(value).get("nextCursor"), 1, 2048)))); }
    private boolean validAudienceSelector(Object value) { return (((object(value, Set.of("kind","principal"), Set.of("kind","principal")) && Objects.equals(map(value).get("kind"), "PRINCIPAL") && validActor(map(value).get("principal"))) ? 1 : 0) + ((object(value, Set.of("kind","tenant"), Set.of("kind","tenant")) && Objects.equals(map(value).get("kind"), "TENANT") && (text(map(value).get("tenant"), 1, 256))) ? 1 : 0) + ((object(value, Set.of("kind","issuer","firmId"), Set.of("kind","issuer","firmId")) && Objects.equals(map(value).get("kind"), "FIRM") && (text(map(value).get("issuer"), 1, 2048) && uri(map(value).get("issuer"))) && (text(map(value).get("firmId"), 1, 256))) ? 1 : 0) + ((object(value, Set.of("kind","issuer","resourceScopeId"), Set.of("kind","issuer","resourceScopeId")) && Objects.equals(map(value).get("kind"), "RESOURCE_SCOPE") && (text(map(value).get("issuer"), 1, 2048) && uri(map(value).get("issuer"))) && (text(map(value).get("resourceScopeId"), 1, 256))) ? 1 : 0)) == 1; }
    private boolean validAudienceEvaluation(Object value) { return (object(value, Set.of("collectionId","audiencePolicyRevision","decision"), Set.of("collectionId","audiencePolicyRevision","decision")) && (text(map(value).get("collectionId"), 0, 100000) && ((String)map(value).get("collectionId")).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) && (number(map(value).get("audiencePolicyRevision"), 1L, 9007199254740991L)) && (map(value).get("decision") != null && Set.of("ALLOW","DENY").contains(map(value).get("decision")))); }
    private boolean validEvaluateAudience(Object value) { return (object(value, Set.of(), Set.of())); }
    private boolean validAudienceRule(Object value) { return (object(value, Set.of("effect","selector"), Set.of("effect","selector")) && (map(value).get("effect") != null && Set.of("ALLOW","DENY").contains(map(value).get("effect"))) && validAudienceSelector(map(value).get("selector"))); }
    private boolean validAudiencePolicy(Object value) { return (object(value, Set.of("collectionId","revision","classificationCeiling","classificationPolicyRevision","rules","effectiveAt"), Set.of("collectionId","revision","classificationCeiling","classificationPolicyRevision","rules","effectiveAt")) && (text(map(value).get("collectionId"), 0, 100000) && ((String)map(value).get("collectionId")).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) && (number(map(value).get("revision"), 1L, 9007199254740991L)) && Objects.equals(map(value).get("classificationCeiling"), "development-restricted") && number(map(value).get("classificationPolicyRevision"), 1, 1) && (array(map(value).get("rules"), 0, 100, true, item -> validAudienceRule(item))) && (text(map(value).get("effectiveAt"), 0, 100000) && instant(map(value).get("effectiveAt")))); }
    private boolean validUpdateAudience(Object value) { return (object(value, Set.of("expectedRevision","rules","reason"), Set.of("expectedRevision","rules","reason")) && (number(map(value).get("expectedRevision"), 0L, 9007199254740991L)) && (array(map(value).get("rules"), 0, 100, true, item -> validAudienceRule(item))) && (text(map(value).get("reason"), 1, 1024))); }
    private boolean validAclRule(Object value) { return (object(value, Set.of("principal","action","effect"), Set.of("principal","action","effect")) && validActor(map(value).get("principal")) && validKnowledgeAction(map(value).get("action")) && (map(value).get("effect") != null && Set.of("ALLOW","DENY").contains(map(value).get("effect")))); }
    private boolean validAcl(Object value) { return (object(value, Set.of("collectionId","revision","rules"), Set.of("collectionId","revision","rules")) && (text(map(value).get("collectionId"), 0, 100000) && ((String)map(value).get("collectionId")).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) && (number(map(value).get("revision"), 0L, 9007199254740991L)) && (array(map(value).get("rules"), 0, 100, true, item -> validAclRule(item)))); }
    private boolean validAclChangeEntry(Object value) { return (object(value, Set.of("operation","principal","action"), Set.of("operation","principal","action")) && (map(value).get("operation") != null && Set.of("ADD_ALLOW","REMOVE_ALLOW","ADD_DENY","REMOVE_DENY").contains(map(value).get("operation"))) && validActor(map(value).get("principal")) && validKnowledgeAction(map(value).get("action"))); }
    private boolean validUpdateAcl(Object value) { return (object(value, Set.of("expectedRevision","mode","changes","reason"), Set.of("expectedRevision","mode","changes","reason")) && (number(map(value).get("expectedRevision"), 0L, 9007199254740991L)) && (map(value).get("mode") != null && Set.of("RESTRICT","DELEGATE").contains(map(value).get("mode"))) && (array(map(value).get("changes"), 1, 100, false, item -> validAclChangeEntry(item))) && (text(map(value).get("reason"), 1, 1024))); }
    private boolean validCreateMembership(Object value) { return (object(value, Set.of("expectedRevision","documentId","expectedDocumentRevision","sharingScope","reason"), Set.of("expectedRevision","documentId","expectedDocumentRevision","sharingScope","reason")) && (number(map(value).get("expectedRevision"), 0L, 9007199254740991L)) && (text(map(value).get("documentId"), 0, 100000) && ((String)map(value).get("documentId")).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) && (number(map(value).get("expectedDocumentRevision"), 0L, 9007199254740991L)) && Objects.equals(map(value).get("sharingScope"), "SELECTED_TENANT") && (text(map(value).get("reason"), 1, 1024))); }
    private boolean validUpdateMembership(Object value) { return (object(value, Set.of("expectedRevision","expectedMembershipRevision","expectedDocumentRevision","state","reason"), Set.of("expectedRevision","expectedMembershipRevision","expectedDocumentRevision","state","reason")) && (number(map(value).get("expectedRevision"), 0L, 9007199254740991L)) && (number(map(value).get("expectedMembershipRevision"), 0L, 9007199254740991L)) && (number(map(value).get("expectedDocumentRevision"), 0L, 9007199254740991L)) && (map(value).get("state") != null && Set.of("ACTIVE","INACTIVE").contains(map(value).get("state"))) && (text(map(value).get("reason"), 1, 1024))); }
    private boolean validMembership(Object value) { return (object(value, Set.of("membershipId","collectionId","documentId","revision","state","sharingScope","creator","createdAt","reason"), Set.of("membershipId","collectionId","documentId","revision","state","sharingScope","creator","createdAt","reason")) && (text(map(value).get("membershipId"), 0, 100000) && ((String)map(value).get("membershipId")).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) && (text(map(value).get("collectionId"), 0, 100000) && ((String)map(value).get("collectionId")).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) && (text(map(value).get("documentId"), 0, 100000) && ((String)map(value).get("documentId")).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) && (number(map(value).get("revision"), 0L, 9007199254740991L)) && (map(value).get("state") != null && Set.of("ACTIVE","INACTIVE").contains(map(value).get("state"))) && Objects.equals(map(value).get("sharingScope"), "SELECTED_TENANT") && validActor(map(value).get("creator")) && (text(map(value).get("createdAt"), 0, 100000) && instant(map(value).get("createdAt"))) && (text(map(value).get("reason"), 1, 1024))); }
    private boolean validMembershipPage(Object value) { return (object(value, Set.of("items","nextCursor"), Set.of("items","nextCursor")) && (array(map(value).get("items"), 0, 50, false, item -> validMembership(item))) && (map(value).get("nextCursor") == null || (text(map(value).get("nextCursor"), 1, 2048)))); }
    private boolean validCreatePolicySnapshot(Object value) { return (object(value, Set.of("expectedDocumentRevision"), Set.of("expectedDocumentRevision")) && (number(map(value).get("expectedDocumentRevision"), 0L, 9007199254740991L))); }
    private boolean validPolicyDecision(Object value) { return (value != null && Set.of("COMPATIBLE","INCOMPATIBLE").contains(value)); }
    private boolean validPolicySnapshot(Object value) { return (object(value, Set.of("snapshotToken","documentId","documentRevision","classificationPolicyRevision","decision"), Set.of("snapshotToken","documentId","documentRevision","classificationPolicyRevision","decision")) && (text(map(value).get("snapshotToken"), 1, 128) && ((String)map(value).get("snapshotToken")).matches("^[A-Za-z0-9_-]{43}$")) && (text(map(value).get("documentId"), 0, 100000) && ((String)map(value).get("documentId")).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) && (number(map(value).get("documentRevision"), 0L, 9007199254740991L)) && number(map(value).get("classificationPolicyRevision"), 1, 1) && validPolicyDecision(map(value).get("decision"))); }
    private boolean validValidatePolicy(Object value) { return (object(value, Set.of("snapshotToken","expectedDocumentRevision"), Set.of("snapshotToken","expectedDocumentRevision")) && (text(map(value).get("snapshotToken"), 1, 128) && ((String)map(value).get("snapshotToken")).matches("^[A-Za-z0-9_-]{43}$")) && (number(map(value).get("expectedDocumentRevision"), 0L, 9007199254740991L))); }
    private boolean validPolicyValidation(Object value) { return (object(value, Set.of("documentId","decision"), Set.of("documentId","decision")) && (text(map(value).get("documentId"), 0, 100000) && ((String)map(value).get("documentId")).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) && validPolicyDecision(map(value).get("decision"))); }
}
