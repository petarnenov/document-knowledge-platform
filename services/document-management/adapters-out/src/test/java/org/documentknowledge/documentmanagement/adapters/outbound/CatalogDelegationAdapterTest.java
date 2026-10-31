package org.documentknowledge.documentmanagement.adapters.outbound;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import org.documentknowledge.authorization.GrantCatalog;
import org.documentknowledge.documentmanagement.domain.Action;
import org.documentknowledge.identity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class CatalogDelegationAdapterTest {
 @TempDir Path directory;
 @Test void bridgePreservesTargetVersusCeilingAndUnavailable()throws Exception{
  Path parent=directory.toRealPath();Files.setPosixFilePermissions(parent,PosixFilePermissions.fromString("rwx------"));Path file=parent.resolve("grants.json");
  Files.writeString(file,"{\"schemaVersion\":1,\"entries\":[{\"issuer\":\"https://issuer.example\",\"subject\":\"staff\",\"tenant\":\"tenant\",\"actions\":[\"document.create\"],\"delegationCeiling\":[\"document.inspect-draft\"]}]}");Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
  var adapter=new CatalogDelegationAdapter(new GrantCatalog(file));var actor=new Actor("https://issuer.example","staff");
  assertEquals(AuthorityDecision.ALLOW,adapter.targetGrant(actor,"tenant",Action.CREATE));assertEquals(AuthorityDecision.DENY,adapter.targetGrant(actor,"tenant",Action.INSPECT_DRAFT));
  assertEquals(AuthorityDecision.ALLOW,adapter.ceiling(actor,"tenant",Action.INSPECT_DRAFT));assertEquals(AuthorityDecision.DENY,adapter.ceiling(actor,"tenant",Action.CREATE));
  Files.delete(file);assertEquals(AuthorityDecision.UNAVAILABLE,adapter.targetGrant(actor,"tenant",Action.CREATE));assertEquals(AuthorityDecision.UNAVAILABLE,adapter.ceiling(actor,"tenant",Action.INSPECT_DRAFT));assertThrows(NullPointerException.class,()->new CatalogDelegationAdapter(null));
 }
}
