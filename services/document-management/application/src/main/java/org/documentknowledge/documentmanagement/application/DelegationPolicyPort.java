package org.documentknowledge.documentmanagement.application;
import org.documentknowledge.identity.Actor;
import org.documentknowledge.identity.AuthorityDecision;
import org.documentknowledge.documentmanagement.domain.Action;
public interface DelegationPolicyPort {
 AuthorityDecision targetGrant(Actor target,String tenant,Action action);
 AuthorityDecision ceiling(Actor caller,String tenant,Action action);
}
