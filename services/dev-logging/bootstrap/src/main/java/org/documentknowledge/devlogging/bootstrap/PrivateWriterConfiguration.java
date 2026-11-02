package org.documentknowledge.devlogging.bootstrap;

import java.nio.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.documentknowledge.devlogging.adapters.outbound.persistence.*;
import org.documentknowledge.devlogging.application.LogProducerRegistry.*;
import org.documentknowledge.devlogging.domain.LogScope;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Trusted immutable operator configuration, never an HTTP grant parser. */
final class PrivateWriterConfiguration {
    private final String password,volumeIdentity;
    private final List<ConfiguredLogRegistrars.RegistrarGrant> registrars;
    private final List<ConfiguredLogRegistrars.DelegatorGrant> delegators;
    private final ConfiguredLogObserver.Grant observer;
    private final List<byte[]> nonproducerHashes;

    PrivateWriterConfiguration(String passwordFile,String registrationFile,String observerFile,String volume,
                               PrivateLogConfiguration readers,Clock clock) {
        try {
            password=new String(PrivateLogConfiguration.read(passwordFile,512),StandardCharsets.US_ASCII);
            if(!password.matches("[A-Za-z0-9_-]{32,512}") || volume==null || !volume.matches("[0-9a-f]{64}"))fail();
            volumeIdentity=volume;
            var root=parse(PrivateLogConfiguration.read(registrationFile,1048576),8);
            keys(root,Set.of("schemaVersion","registrars","delegators"));version(root);
            var rs=new ArrayList<ConfiguredLogRegistrars.RegistrarGrant>();
            for(Object row:rows(root,"registrars")) {
                var item=object(row);keys(item,Set.of("id","expiresAt","tokenSha256"));
                rs.add(new ConfiguredLogRegistrars.RegistrarGrant(new Registrar(id(text(item,"id")),expiry(item,clock)),hash(item)));
            }
            var ds=new ArrayList<ConfiguredLogRegistrars.DelegatorGrant>();
            for(Object row:rows(root,"delegators")) {
                var item=object(row);keys(item,Set.of("id","expiresAt","tokenSha256","parents","bridgeManifests","permissions"));
                var authority=new Delegator(id(text(item,"id")),expiry(item,clock),
                    unique(rows(item,"parents"),value->id(string(value))),
                    unique(rows(item,"bridgeManifests"),value->digest(string(value))),
                    unique(rows(item,"permissions"),PrivateWriterConfiguration::permission));
                ds.add(new ConfiguredLogRegistrars.DelegatorGrant(authority,hash(item)));
            }
            registrars=List.copyOf(rs);delegators=List.copyOf(ds);
            new ConfiguredLogRegistrars(registrars,delegators,clock);
            var ob=parse(PrivateLogConfiguration.read(observerFile,4096),4);
            keys(ob,Set.of("schemaVersion","id","expiresAt","tokenSha256"));version(ob);
            observer=new ConfiguredLogObserver.Grant(id(text(ob,"id")),expiry(ob,clock),hash(ob));
            var ids=new HashSet<UUID>();var hashes=new HashSet<String>();var all=new ArrayList<byte[]>();
            for(var grant:readers.grants())add(ids,hashes,all,grant.reader().id(),grant.tokenSha256());
            for(var grant:registrars)add(ids,hashes,all,grant.authority().id(),grant.tokenSha256());
            for(var grant:delegators)add(ids,hashes,all,grant.authority().id(),grant.tokenSha256());
            add(ids,hashes,all,observer.id(),observer.tokenSha256());nonproducerHashes=List.copyOf(all);
        } catch(Exception invalid){throw new IllegalStateException("LOG_CONFIGURATION_UNAVAILABLE");}
    }
    String password(){return password;}
    String volumeIdentity(){return volumeIdentity;}
    List<ConfiguredLogRegistrars.RegistrarGrant> registrars(){return registrars;}
    List<ConfiguredLogRegistrars.DelegatorGrant> delegators(){return delegators;}
    ConfiguredLogObserver.Grant observer(){return observer;}
    List<byte[]> nonproducerHashes(){return nonproducerHashes.stream().map(byte[]::clone).toList();}

    private static Map<?,?> parse(byte[] bytes,int depth) throws Exception {
        String value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        var factory=JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(depth).maxDocumentLength(1048576)
                .maxStringLength(512).maxNumberLength(16).build()).build();
        return JsonMapper.builder(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build().readValue(value,Map.class);
    }
    private static ScopePermission permission(Object row) {
        var item=object(row);var kind=LogScope.Kind.valueOf(text(item,"kind"));
        return switch(kind) {
            case TENANT -> {keys(item,Set.of("kind","issuer","tenantId"));yield new ScopePermission(kind,text(item,"issuer"),text(item,"tenantId"));}
            case AUTHENTICATED_UNSELECTED -> {keys(item,Set.of("kind","issuer"));yield new ScopePermission(kind,text(item,"issuer"),null);}
            case UNATTRIBUTED,OPERATOR_ONLY -> {keys(item,Set.of("kind"));yield new ScopePermission(kind,null,null);}
        };
    }
    private static void add(Set<UUID> ids,Set<String> hashes,List<byte[]> all,UUID id,byte[] hash) {
        if(!ids.add(id) || !hashes.add(HexFormat.of().formatHex(hash)))fail();all.add(hash.clone());
    }
    private static <T> Set<T> unique(List<?> values,Function<Object,T> convert) {
        var result=new HashSet<T>();for(Object value:values)if(!result.add(convert.apply(value)))fail();return Set.copyOf(result);
    }
    private static List<?> rows(Map<?,?> item,String key) {
        if(!(item.get(key) instanceof List<?> list) || list.size()>4096) {fail();return List.of();}
        return (List<?>)item.get(key);
    }
    private static Instant expiry(Map<?,?> item,Clock clock) {
        String value=text(item,"expiresAt");
        if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z")
                || value.substring(11,13).compareTo("23")>0 || value.substring(14,16).compareTo("59")>0
                || value.substring(17,19).compareTo("59")>0)fail();
        Instant result=Instant.parse(value);if(!clock.instant().isBefore(result))fail();return result;
    }
    private static UUID id(String value) {
        if(!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") || value.equals("00000000-0000-0000-0000-000000000000"))fail();
        return UUID.fromString(value);
    }
    private static String digest(String value){if(!value.matches("[0-9a-f]{64}"))fail();return value;}
    private static byte[] hash(Map<?,?> item){return HexFormat.of().parseHex(digest(text(item,"tokenSha256")));}
    private static void version(Map<?,?> item){if(!(item.get("schemaVersion") instanceof Integer v) || v!=1)fail();}
    private static void keys(Map<?,?> item,Set<String> keys){if(!item.keySet().equals(keys))fail();}
    private static Map<?,?> object(Object value){if(!(value instanceof Map<?,?>)){fail();}return (Map<?,?>)value;}
    private static String string(Object value){if(!(value instanceof String)){fail();}return (String)value;}
    private static String text(Map<?,?> item,String key){return string(item.get(key));}
    private static void fail(){throw new IllegalArgumentException("Invalid private configuration");}
}
