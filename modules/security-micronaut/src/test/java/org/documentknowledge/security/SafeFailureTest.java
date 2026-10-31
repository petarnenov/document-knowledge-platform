package org.documentknowledge.security;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.authentication.AuthorizationException;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class SafeFailureTest {
    @Test void rejectedIdentityHasOnlyPublicCodeAndNoCache() {
        var handler=new SafeAuthorizationExceptionHandler();
        var anonymous=handler.handle(HttpRequest.GET("/api/v1/security/context"),new AuthorizationException(null));
        assertEquals(401,anonymous.code());assertEquals(Map.of("code","UNAUTHENTICATED"),anonymous.body());
        assertEquals("no-store",anonymous.header("Cache-Control"));assertEquals("Bearer",anonymous.header("WWW-Authenticate"));
        var authenticated=handler.handle(HttpRequest.GET("/api/v1/security/context"),new AuthorizationException(Authentication.build("user")));
        assertEquals(403,authenticated.code());assertEquals(Map.of("code","ACCESS_DENIED"),authenticated.body());
    }
}
