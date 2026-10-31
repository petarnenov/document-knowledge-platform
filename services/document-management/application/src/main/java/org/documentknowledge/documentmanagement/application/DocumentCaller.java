package org.documentknowledge.documentmanagement.application;
import java.util.Objects;
import java.util.function.Supplier;
import org.documentknowledge.identity.Actor;
import org.documentknowledge.identity.AuthorityDecision;
import org.documentknowledge.documentmanagement.domain.Principal;
public record DocumentCaller(Actor actor,String tenant,Supplier<AuthorityDecision> membershipCheck) {
 public DocumentCaller {Objects.requireNonNull(actor); org.documentknowledge.documentmanagement.domain.Validation.text(tenant,256,false);Objects.requireNonNull(membershipCheck);}
 public Principal principal(){return new Principal(actor.issuer(),actor.subject());}
 public AuthorityDecision membership(){try {var decision=membershipCheck.get();return decision==null?AuthorityDecision.UNAVAILABLE:decision;}catch(RuntimeException failure){return AuthorityDecision.UNAVAILABLE;}}
}
