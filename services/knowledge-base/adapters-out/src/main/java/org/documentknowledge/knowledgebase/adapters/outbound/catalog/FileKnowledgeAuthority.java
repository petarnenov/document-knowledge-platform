package org.documentknowledge.knowledgebase.adapters.outbound.catalog;

import java.nio.file.Path;
import java.net.URI;
import java.util.*;
import org.documentknowledge.authorization.TrustedAuthorityFiles;
import org.documentknowledge.knowledgebase.application.KnowledgeAuthorityPort;
import org.documentknowledge.knowledgebase.application.AudienceRelationPort;
import org.documentknowledge.knowledgebase.domain.KbModels.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Bounded local authoritative read on EVERY decision, including target grants and relations. */
public final class FileKnowledgeAuthority implements KnowledgeAuthorityPort, AudienceRelationPort {
    private final Path path;
    private final TrustedAuthorityFiles files;
    private final JsonMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public FileKnowledgeAuthority(Path path) { this(path,TrustedAuthorityFiles.system()); }
    /** Java-only filesystem test seam. Production must use the system constructor. */
    public FileKnowledgeAuthority(Path path,TrustedAuthorityFiles files) { this.path=path; this.files=Objects.requireNonNull(files); }
    /** Operational file validity only; never an authority grant. */
    public boolean ready() { try { read(); return true; } catch(Exception unavailable) { return false; } }
    @Override public Result authorize(Context context,Action action) { return grant(context,action,false); }
    @Override public Result delegationCeiling(Context context,Action action) { return grant(context,action,true); }
    private Result grant(Context context,Action action,boolean ceiling) {
        try {
            Entry entry=read().get(context);
            return entry!=null&&(ceiling?entry.ceiling():entry.actions()).contains(action.wire())?Result.ALLOW:Result.DENY;
        } catch(Exception unavailable) { return Result.UNAVAILABLE; }
    }
    @Override public Result matches(Context context,Selector selector) {
        try {
            Entry entry=read().get(context);
            boolean match=switch(selector.kind()) {
                case PRINCIPAL -> selector.issuer().equals(context.actor().issuer())&&selector.value().equals(context.actor().subject());
                case TENANT -> selector.value().equals(context.tenant());
                case FIRM -> entry!=null&&selector.issuer().equals(context.actor().issuer())&&entry.firms().contains(selector.value());
                case RESOURCE_SCOPE -> entry!=null&&selector.issuer().equals(context.actor().issuer())&&entry.scopes().contains(selector.value());
            };
            return match?Result.ALLOW:Result.DENY;
        } catch(Exception unavailable) { return Result.UNAVAILABLE; }
    }
    private Map<Context,Entry> read() throws java.io.IOException {
        Map<?,?> root=map(json.readValue(files.readBounded(path,65536,false),Object.class),Set.of("schemaVersion","entries"),Set.of("schemaVersion","entries"));
        if(!(root.get("schemaVersion") instanceof Number version)||version.intValue()!=1||version.doubleValue()!=1.0) invalid();
        Map<Context,Entry> result=new HashMap<>();
        for(Object value:list(root.get("entries"),100)) {
            Map<?,?> item=map(value,Set.of("issuer","subject","tenant","actions","delegationCeiling","firms","resourceScopes"),Set.of("issuer","subject","tenant","actions"));
            String issuer=text(item.get("issuer"),2048); URI uri=URI.create(issuer);
            if(!uri.isAbsolute()||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null) invalid();
            Context key=new Context(new Principal(issuer,text(item.get("subject"),256)),text(item.get("tenant"),256));
            Entry entry=new Entry(strings(item.get("actions"),true),optional(item,"delegationCeiling",true),optional(item,"firms",false),optional(item,"resourceScopes",false));
            if(result.putIfAbsent(key,entry)!=null) invalid();
        }
        return Map.copyOf(result);
    }
    private static Set<String> optional(Map<?,?> item,String name,boolean actions) { return item.containsKey(name)?strings(item.get(name),actions):Set.of(); }
    private static Set<String> strings(Object value,boolean actions) {
        Set<String> result=new HashSet<>();
        for(Object item:list(value,32)) {
            String name=text(item,actions?64:256);
            if(actions&&Arrays.stream(Action.values()).noneMatch(a->a.wire().equals(name))) invalid();
            if(!result.add(name)) invalid();
        }
        return Set.copyOf(result);
    }
    private static Map<?,?> map(Object value,Set<String> allowed,Set<String> required) {
        if(!(value instanceof Map<?,?> map)||!allowed.containsAll(map.keySet())||!map.keySet().containsAll(required)) invalid();
        return (Map<?,?>)value;
    }
    private static List<?> list(Object value,int maximum) {
        if(!(value instanceof List<?> list)||list.size()>maximum) invalid(); return (List<?>)value;
    }
    private static String text(Object value,int maximum) {
        if(!(value instanceof String text)||text.isBlank()||text.length()>maximum||!text.equals(text.strip())||text.contains("*")||text.chars().anyMatch(Character::isISOControl)) invalid();
        return (String)value;
    }
    private static void invalid() { throw new IllegalArgumentException("Unavailable KB catalog"); }
    private record Entry(Set<String> actions,Set<String> ceiling,Set<String> firms,Set<String> scopes) { }
}
