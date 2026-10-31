package org.documentknowledge.authorization;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.documentknowledge.identity.Actor;
import org.documentknowledge.identity.AuthorityDecision;
import org.documentknowledge.identity.ResourceAuthorizationPort;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;

/** One bounded authoritative read per decision. There is deliberately no cached successful snapshot. */
public final class GrantCatalog implements ResourceAuthorizationPort {
    public static final Set<String> ACTIONS = Set.of("document.create", "document.inspect-draft", "document.read",
        "document.update-metadata", "document.acl.restrict", "document.acl.delegate", "document.version.append",
        "document.legal-hold.place", "document.legal-hold.inspect", "document.content.review");
    private final Path path;
    private final TrustedAuthorityFiles files;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public GrantCatalog(Path path) { this(path, TrustedAuthorityFiles.system()); }
    /** Java-only test seam; production wiring always uses the system-platform constructor. */
    public GrantCatalog(Path path, TrustedAuthorityFiles files) { this.path = path; this.files = java.util.Objects.requireNonNull(files); }
    @Override public AuthorityDecision authorize(Actor actor, String tenant, String action, String resource) {
        return grant(actor, tenant, action, false);
    }
    public AuthorityDecision delegationCeiling(Actor actor, String tenant, String action) {
        return grant(actor, tenant, action, true);
    }
    private AuthorityDecision grant(Actor actor, String tenant, String action, boolean delegation) {
        try {
            Map<Key, Entry> catalog = read();
            Entry entry = catalog.get(new Key(actor.issuer(), actor.subject(), tenant));
            return entry != null && (delegation ? entry.ceiling() : entry.actions()).contains(action)
                ? AuthorityDecision.ALLOW : AuthorityDecision.DENY;
        } catch (Exception unavailable) { return AuthorityDecision.UNAVAILABLE; }
    }
    private Map<Key, Entry> read() throws java.io.IOException {
        byte[] bytes = files.readBounded(path, 65536, false);
        Object decoded = json.readValue(bytes, Object.class);
        Map<?, ?> root = map(decoded, Set.of("schemaVersion", "entries"), Set.of("schemaVersion", "entries"));
        if (!(root.get("schemaVersion") instanceof Number n) || n.intValue() != 1 || n.doubleValue() != 1.0) invalid();
        List<?> entries = list(root.get("entries"), 100);
        Map<Key, Entry> result = new HashMap<>();
        for (Object value : entries) {
            Map<?, ?> item = map(value, Set.of("issuer", "subject", "tenant", "actions", "delegationCeiling"), Set.of("issuer", "subject", "tenant", "actions"));
            String issuer = text(item.get("issuer"), 2048);
            java.net.URI uri = java.net.URI.create(issuer);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null) invalid();
            Key key = new Key(issuer, text(item.get("subject"), 256), text(item.get("tenant"), 256));
            Entry entry = new Entry(actions(item.get("actions")), item.containsKey("delegationCeiling") ? actions(item.get("delegationCeiling")) : Set.of());
            if (result.putIfAbsent(key, entry) != null) invalid();
        }
        return result;
    }
    private static Set<String> actions(Object value) {
        Set<String> result = new HashSet<>();
        for (Object action : list(value, 32)) {
            String name = text(action, 64);
            if (!ACTIONS.contains(name) || !result.add(name)) invalid();
        }
        return Set.copyOf(result);
    }
    private static Map<?, ?> map(Object value, Set<String> allowed, Set<String> required) {
        if (!(value instanceof Map<?, ?> map) || !allowed.containsAll(map.keySet()) || !map.keySet().containsAll(required)) invalid();
        return (Map<?, ?>) value;
    }
    private static List<?> list(Object value, int maximum) {
        if (!(value instanceof List<?> list) || list.size() > maximum) invalid();
        return (List<?>) value;
    }
    private static String text(Object value, int maximum) {
        if (!(value instanceof String s) || s.isBlank() || s.length() > maximum || !s.equals(s.strip())
            || s.contains("*") || s.chars().anyMatch(Character::isISOControl)) invalid();
        return (String) value;
    }
    private static void invalid() { throw new IllegalArgumentException("Unavailable grant catalog"); }
    private record Key(String issuer, String subject, String tenant) { }
    private record Entry(Set<String> actions, Set<String> ceiling) { }
}
