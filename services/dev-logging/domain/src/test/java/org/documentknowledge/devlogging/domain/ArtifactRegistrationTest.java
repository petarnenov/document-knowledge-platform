package org.documentknowledge.devlogging.domain;

import java.util.UUID;
import org.documentknowledge.observability.BuildIdentity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactRegistrationTest {
    final ProducerBinding binding = new ProducerBinding(new BuildIdentity(UUID.randomUUID(),"runtime-tools","a".repeat(40),"b".repeat(64)),UUID.randomUUID());
    @Test void artifactRequiresBothExactLowercaseDigestsAndBinding() {
        var value = new ArtifactRegistration(binding,"c".repeat(64),"d".repeat(64));
        assertEquals(binding,value.binding()); assertEquals("c".repeat(64),value.manifestSha256());
        for(String invalid:new String[]{null,"A".repeat(64),"bad"}) {
            assertThrows(LogFailure.class,()->new ArtifactRegistration(binding,invalid,"d".repeat(64)));
            assertThrows(LogFailure.class,()->new ArtifactRegistration(binding,"c".repeat(64),invalid));
        }
        assertThrows(NullPointerException.class,()->new ArtifactRegistration(null,"c".repeat(64),"d".repeat(64)));
    }
    @Test void descriptionUsesTheSameScopeInvariantsAndServerAssignedId() {
        var description = new LogScope.Description(LogScope.Kind.TENANT,"issuer","actor","tenant");
        var id = UUID.randomUUID(); assertEquals(new LogScope(id,description.kind(),"issuer","actor","tenant"),description.identified(id));
        assertThrows(LogFailure.class,()->description.identified(new UUID(0,0)));
        assertThrows(LogFailure.class,()->new LogScope.Description(LogScope.Kind.TENANT,"issuer","actor",null));
        assertThrows(LogFailure.class,()->new LogScope.Description(LogScope.Kind.AUTHENTICATED_UNSELECTED,"issuer","actor","tenant"));
        for(var kind:new LogScope.Kind[]{LogScope.Kind.OPERATOR_ONLY,LogScope.Kind.UNATTRIBUTED}) {
            assertEquals(kind,new LogScope.Description(kind,null,null,null).identified(UUID.randomUUID()).kind());
            assertThrows(LogFailure.class,()->new LogScope.Description(kind,"issuer",null,null));
        }
    }
}
