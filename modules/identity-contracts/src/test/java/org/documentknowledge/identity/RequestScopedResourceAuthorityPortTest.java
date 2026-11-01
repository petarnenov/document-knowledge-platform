package org.documentknowledge.identity;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RequestScopedResourceAuthorityPortTest {
 final Actor actor=new Actor("https://issuer.example","user");
 @Test void immutableExactBindingsAndUnavailableIsNotAnAllowFallback(){List<String> ids=new ArrayList<>(List.of("one"));Map<String,AuthorityDecision> decisions=new HashMap<>(Map.of("one",AuthorityDecision.ALLOW));var snapshot=new RequestScopedResourceAuthorityPort.Snapshot(actor,"t","a",ids,AuthorityDecision.ALLOW,decisions,"generation");ids.add("two");decisions.put("one",AuthorityDecision.DENY);assertEquals(List.of("one"),snapshot.resources());assertEquals(AuthorityDecision.ALLOW,snapshot.decisions().get("one"));assertThrows(UnsupportedOperationException.class,()->snapshot.resources().add("three"));assertThrows(UnsupportedOperationException.class,()->snapshot.decisions().clear());var unknown=RequestScopedResourceAuthorityPort.unavailable().snapshot(actor,"t","a",List.of("one","two"));assertEquals(AuthorityDecision.UNAVAILABLE,unknown.route());assertTrue(unknown.decisions().values().stream().allMatch(value->value==AuthorityDecision.UNAVAILABLE));}
 @Test void rejectsEmptyOverBoundDuplicateAndIncompleteSet(){for(List<String> ids:List.of(List.<String>of(),Collections.nCopies(101,"one"),List.of("one","one"),List.of("two")))assertThrows(IllegalArgumentException.class,()->new RequestScopedResourceAuthorityPort.Snapshot(actor,"t","a",ids,AuthorityDecision.ALLOW,Map.of("one",AuthorityDecision.ALLOW),"generation"));}
}
