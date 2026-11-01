package org.documentknowledge.identity;

import java.util.*;

/** One immutable whole-source read for a bounded request. A fingerprint is not an authority lease. */
@FunctionalInterface
public interface RequestScopedResourceAuthorityPort {
    record Snapshot(Actor actor,String tenant,String action,List<String> resources,AuthorityDecision route,Map<String,AuthorityDecision> decisions,String fingerprint) {
        public Snapshot {
            Objects.requireNonNull(actor); Objects.requireNonNull(tenant); Objects.requireNonNull(action);
            resources=List.copyOf(resources); decisions=Map.copyOf(decisions); Objects.requireNonNull(route); Objects.requireNonNull(fingerprint);
            if(resources.isEmpty()||resources.size()>100||new HashSet<>(resources).size()!=resources.size()||!decisions.keySet().equals(new HashSet<>(resources))) throw new IllegalArgumentException("Invalid bounded authority binding");
        }
    }
    Snapshot snapshot(Actor actor,String tenant,String action,List<String> resources);
    static RequestScopedResourceAuthorityPort unavailable() {
        return (actor,tenant,action,resources)->{
            Map<String,AuthorityDecision> decisions=new HashMap<>(); resources.forEach(resource->decisions.put(resource,AuthorityDecision.UNAVAILABLE));
            return new Snapshot(actor,tenant,action,resources,AuthorityDecision.UNAVAILABLE,decisions,"");
        };
    }
}
