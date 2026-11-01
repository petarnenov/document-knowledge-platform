package org.documentknowledge.documentmanagement.application.upload;
import java.util.UUID;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.documentmanagement.domain.*;
import org.documentknowledge.identity.*;
/** Common current-boundary policy. No positive decisions survive a boundary. */
public final class UploadPolicy {
 private final ResourceAuthorizationPort grants;
 public UploadPolicy(ResourceAuthorizationPort grants){this.grants=java.util.Objects.requireNonNull(grants);}
 public void route(DocumentCaller caller,Action action,UUID document,boolean hidden){route(caller,action,document,hidden,()->{});}
 public void route(DocumentCaller caller,Action action,UUID document,boolean hidden,Runnable check){
  check.run();var membership=caller.membership();check.run();decide(membership,403,"TENANT_DENIED");AuthorityDecision answer;
  try {check.run();answer=grants.authorize(caller.actor(),caller.tenant(),action.wire(),document==null?"":document.toString());check.run();}
  catch(RuntimeException unavailable){throw unavailable();}
  decide(answer,hidden?404:403,hidden?"NOT_FOUND":"OPERATION_DENIED");
 }
 public DocumentAggregate document(DocumentRepository repository,DocumentCaller caller,UUID id,Action action,boolean hidden){return document(repository,caller,id,action,hidden,()->{});}
 public DocumentAggregate document(DocumentRepository repository,DocumentCaller caller,UUID id,Action action,boolean hidden,Runnable check){
  route(caller,action,id,hidden,check);check.run();var found=repository.find(caller.tenant(),id);check.run();var document=found.orElseThrow(UploadPolicy::hidden);
  if(!caller.tenant().equals(document.tenant())||!document.permits(caller.principal(),action))throw hidden();check.run();return document;
 }
 public static void decide(AuthorityDecision answer,int status,String code){if(answer==null||answer==AuthorityDecision.UNAVAILABLE)throw unavailable();if(answer!=AuthorityDecision.ALLOW)throw new DmFailure(status,code);}
 public static DmFailure hidden(){return new DmFailure(404,"NOT_FOUND");}
 public static DmFailure unavailable(){return new DmFailure(503,"UNAVAILABLE");}
}
