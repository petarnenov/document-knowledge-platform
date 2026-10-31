package org.documentknowledge.documentmanagement.adapters.outbound;
import org.documentknowledge.authorization.GrantCatalog;
import org.documentknowledge.documentmanagement.application.DelegationPolicyPort;
import org.documentknowledge.documentmanagement.domain.Action;
import org.documentknowledge.identity.Actor;
import org.documentknowledge.identity.AuthorityDecision;
public final class CatalogDelegationAdapter implements DelegationPolicyPort {
 private final GrantCatalog catalog;
 public CatalogDelegationAdapter(GrantCatalog catalog){this.catalog=java.util.Objects.requireNonNull(catalog);}
 @Override public AuthorityDecision targetGrant(Actor target,String tenant,Action action){return catalog.authorize(target,tenant,action.wire(),"");}
 @Override public AuthorityDecision ceiling(Actor caller,String tenant,Action action){return catalog.delegationCeiling(caller,tenant,action.wire());}
}
