package org.documentknowledge.browserbff.application;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit closed wire boundary shared by fixed routing and response validation. */
public final class DocumentWire {
    public static final Set<String> ACTIONS = Set.of("document.create", "document.inspect-draft", "document.read", "document.update-metadata", "document.acl.restrict", "document.acl.delegate", "document.version.append", "document.legal-hold.place", "document.legal-hold.inspect");
    private DocumentWire() { }
    public static boolean uuid(Object value) { return value instanceof String s && s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"); }
    public static boolean route(String method, String path) {
        if (path.equals("/api/v1/documents")) return Set.of("GET", "POST").contains(method);
        String[] parts = path.split("/", -1);
        if (parts.length < 5 || !path.startsWith("/api/v1/documents/") || !uuid(parts[4])) return false;
        if (parts.length == 5) return Set.of("GET", "PATCH").contains(method);
        if (parts.length != 6) return false;
        return switch (parts[5]) { case "acl" -> Set.of("GET", "PATCH").contains(method); case "legal-holds" -> Set.of("GET", "POST").contains(method); case "versions" -> method.equals("POST"); default -> false; };
    }
    public static void request(String method, String path, Map<String, String> query, Map<String, Object> body, String key) {
        if (!route(method, path)) fail(404, "NOT_FOUND");
        if (method.equals("GET")) {
            if (!query.isEmpty() && !path.equals("/api/v1/documents")) fail(400, "INVALID_REQUEST");
            if (!Set.of("limit", "cursor").containsAll(query.keySet())) fail(400, "INVALID_REQUEST");
            if (query.containsKey("limit") && !query.get("limit").matches("[1-9]|[1-4][0-9]|50")) fail(400, "INVALID_REQUEST");
            if (query.containsKey("cursor") && !text(query.get("cursor"), 1, 2048)) fail(400, "INVALID_REQUEST");
            return;
        }
        if (!query.isEmpty() || key == null || !key.matches("[!-~]{1,128}")) fail(400, "INVALID_REQUEST");
        if (body == null) fail(400, "INVALID_REQUEST");
        boolean valid;
        if (path.equals("/api/v1/documents")) valid = keys(body, "metadata", "classification", "classificationPolicyRevision") && metadata(body.get("metadata")) && "development-restricted".equals(body.get("classification")) && one(body.get("classificationPolicyRevision"));
        else if (path.endsWith("/acl")) valid = aclChange(body);
        else if (path.endsWith("/legal-holds")) valid = keys(body, "expectedRevision", "reason") && revision(body.get("expectedRevision")) && text(body.get("reason"), 1, 1024);
        else if (path.endsWith("/versions")) valid = keys(body, "expectedRevision", "receiptId") && revision(body.get("expectedRevision")) && text(body.get("receiptId"), 1, 256);
        else valid = keys(body, "expectedRevision", "metadata") && revision(body.get("expectedRevision")) && metadata(body.get("metadata"));
        if (!valid) fail(400, "INVALID_REQUEST");
    }
    private static boolean aclChange(Map<?, ?> body) {
        if (!keys(body, "expectedRevision", "mode", "changes") || !revision(body.get("expectedRevision")) || !member(Set.of("RESTRICT", "DELEGATE"), body.get("mode"))) return false;
        return body.get("changes") instanceof List<?> changes && !changes.isEmpty() && changes.size() <= 100 && changes.stream().allMatch(v -> v instanceof Map<?, ?> m && keys(m, "operation", "principal", "action") && actor(m.get("principal")) && member(ACTIONS, m.get("action")) && member(Set.of("ADD_ALLOW", "REMOVE_ALLOW", "ADD_DENY", "REMOVE_DENY"), m.get("operation")));
    }
    public static boolean response(String method, String path, Map<?, ?> body, String tenant, int limit) {
        if (!method.equals("GET")) return keys(body, "documentId", "revision") && uuid(body.get("documentId")) && revision(body.get("revision")) && (path.equals("/api/v1/documents") || path.split("/")[4].equalsIgnoreCase((String) body.get("documentId")));
        if (path.equals("/api/v1/documents")) return keys(body, "items", "nextCursor") && (body.get("nextCursor") == null || text(body.get("nextCursor"), 1, 2048)) && body.get("items") instanceof List<?> items && items.size() <= limit && items.stream().allMatch(v -> v instanceof Map<?, ?> m && document(m, tenant));
        if (path.endsWith("/acl")) return keys(body, "documentId", "revision", "rules") && path.split("/")[4].equalsIgnoreCase(String.valueOf(body.get("documentId"))) && revision(body.get("revision")) && body.get("rules") instanceof List<?> rules && rules.size() <= 100 && rules.stream().allMatch(v -> v instanceof Map<?, ?> r && keys(r, "principal", "action", "effect") && actor(r.get("principal")) && member(ACTIONS, r.get("action")) && member(Set.of("ALLOW", "DENY"), r.get("effect")));
        if (path.endsWith("/legal-holds")) return keys(body, "items") && body.get("items") instanceof List<?> items && items.size() <= 100 && items.stream().allMatch(v -> v instanceof Map<?, ?> h && keys(h, "holdId", "documentId", "revision", "active", "reason", "placedBy", "placedAt") && uuid(h.get("holdId")) && path.split("/")[4].equalsIgnoreCase(String.valueOf(h.get("documentId"))) && revision(h.get("revision")) && Boolean.TRUE.equals(h.get("active")) && text(h.get("reason"), 1, 1024) && actor(h.get("placedBy")) && instant(h.get("placedAt")));
        return document(body, tenant) && path.split("/")[4].equalsIgnoreCase(String.valueOf(body.get("documentId")));
    }
    private static boolean document(Map<?, ?> m, String tenant) {
        return keys(m, "documentId", "tenant", "creator", "createdAt", "revision", "lifecycle", "metadata", "classification", "classificationPolicyRevision", "activeVersionId", "retention") && uuid(m.get("documentId")) && tenant.equals(m.get("tenant")) && actor(m.get("creator")) && instant(m.get("createdAt")) && revision(m.get("revision")) && "DRAFT".equals(m.get("lifecycle")) && metadata(m.get("metadata")) && "development-restricted".equals(m.get("classification")) && one(m.get("classificationPolicyRevision")) && m.get("activeVersionId") == null && retention(m.get("retention"));
    }
    private static boolean retention(Object v) { return v instanceof Map<?, ?> m && keys(m, "policyRef", "policyRevision", "resolution", "retainUntil", "destructiveEligible") && "development-unresolved".equals(m.get("policyRef")) && one(m.get("policyRevision")) && "UNRESOLVED".equals(m.get("resolution")) && m.get("retainUntil") == null && Boolean.FALSE.equals(m.get("destructiveEligible")); }
    private static boolean actor(Object v) { return v instanceof Map<?, ?> m && keys(m, "issuer", "subject") && text(m.get("issuer"), 1, 2048) && text(m.get("subject"), 1, 256); }
    private static boolean metadata(Object v) { return v instanceof Map<?, ?> m && keys(m, "title", "description") && text(m.get("title"), 1, 256) && text(m.get("description"), 0, 4096); }
    private static boolean instant(Object v) { try { return v instanceof String s && java.time.Instant.parse(s) != null; } catch (java.time.format.DateTimeParseException invalid) { return false; } }
    private static boolean one(Object v) { return v instanceof Number n && n.doubleValue() == 1; }
    private static boolean revision(Object v) { return v instanceof Number n && n.doubleValue() >= 0 && n.doubleValue() <= 9007199254740991L && n.doubleValue() == n.longValue(); }
    private static boolean text(Object v, int min, int max) { return v instanceof String s && s.length() >= min && s.length() <= max && s.chars().noneMatch(Character::isISOControl); }
    private static boolean keys(Map<?, ?> m, String... expected) { return m != null && m.keySet().equals(Set.of(expected)); }
    private static boolean member(Set<String> set, Object value) { return value instanceof String s && set.contains(s); }
    private static void fail(int status, String code) { throw new BffFailure(status, code); }
}
