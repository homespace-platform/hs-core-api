package com.hs.api.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;

class UserContextFilterTest {

    private static final String SECRET = "test-only-internal-gateway-secret-32";
    private final UserContextFilter filter = new UserContextFilter(SECRET);

    /** Result seen by the downstream chain. */
    private record Seen(Authentication auth, UserContext user, int status, boolean chainCalled) {
        Set<String> authorities() {
            return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        }
    }

    private Seen run(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        var auth = new AtomicReference<Authentication>();
        var user = new AtomicReference<UserContext>();
        var called = new AtomicReference<>(false);
        filter.doFilter(request, response, (req, res) -> {
            called.set(true);
            auth.set(SecurityContextHolder.getContext().getAuthentication());
            user.set(UserContextHolder.get());
        });
        return new Seen(auth.get(), user.get(), response.getStatus(), called.get());
    }

    private MockHttpServletRequest request(String uri, String secret, String id, String role, String permissions) {
        var r = new MockHttpServletRequest("GET", uri);
        r.setRequestURI(uri);
        if (secret != null) r.addHeader("X-Internal-Secret", secret);
        if (id != null) r.addHeader("X-User-Id", id);
        if (role != null) r.addHeader("X-User-Role", role);
        if (permissions != null) r.addHeader("X-User-Authorities", permissions);
        r.addHeader("X-User-Email", "u@example.test");
        return r;
    }

    @Test
    void missingSecretIgnoresIdentityHeaders() throws Exception {
        var seen = run(request("/admin/users", null, "u-1", "ADMIN", "USER_VIEW"));
        assertTrue(seen.chainCalled());
        assertNull(seen.auth());
        assertNull(seen.user());
    }

    @Test
    void wrongSecretIgnoresIdentityHeaders() throws Exception {
        var seen = run(request("/admin/users", SECRET + "x", "u-1", "ADMIN", "USER_VIEW"));
        assertTrue(seen.chainCalled());
        assertNull(seen.auth());
        assertNull(seen.user());
    }

    @Test
    void correctSecretWithIdAndAdminRoleAuthenticatesAsAdmin() throws Exception {
        var seen = run(request("/admin/users", SECRET, "u-1", "ROLE_admin", " USER_VIEW , ,KYC_VIEW"));
        assertEquals(Set.of("ADMIN", "USER_VIEW", "KYC_VIEW"), seen.authorities());
        assertEquals("u-1", seen.user().userId());
        assertEquals("u@example.test", seen.user().email());
        // Context never leaks past the request.
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(UserContextHolder.get());
    }

    @Test
    void permissionTokenNamedAsRoleIsDropped() throws Exception {
        var seen = run(request("/me", SECRET, "u-1", "USER", "ADMIN,ROLE_ADMIN,admin,USER_VIEW"));
        assertEquals(Set.of("USER", "USER_VIEW"), seen.authorities());
        assertFalse(seen.authorities().contains("ADMIN"));
    }

    @Test
    void userScopePermissionsSurviveForUserRole() throws Exception {
        var seen = run(request("/me", SECRET, "u-1", "USER", String.join(",", com.hs.common.security.Permissions.USER_SCOPE)));
        var expected = new java.util.HashSet<>(com.hs.common.security.Permissions.USER_SCOPE);
        expected.add("USER");
        assertEquals(expected, seen.authorities());
    }

    @Test
    void unknownRoleRoleOnlyOrIdOnlyIsAnonymous() throws Exception {
        assertNull(run(request("/me", SECRET, "u-1", "SUPERUSER", "USER_VIEW")).auth());
        assertNull(run(request("/me", SECRET, "u-1", null, "USER_VIEW")).auth());
        var roleOnly = run(request("/me", SECRET, null, "ADMIN", null));
        assertNull(roleOnly.auth());
        assertNull(roleOnly.user());
    }

    @Test
    void internalPathsRequireTheSecret() throws Exception {
        for (String uri : new String[]{"/internal/users/u-1/permissions", "/internal/documents/lease-bundles/c-1", "/api/v1/internal/users/u-1/permissions",
                "/%69nternal/users/u-1/permissions", "/INTERNAL/users/u-1/permissions",
                "/internal;x=1/users/1/permissions", "/api;a=b/internal;x=1", "/internal%3Bx=1/users/1/permissions"}) {
            var seen = run(request(uri, null, null, null, null));
            assertFalse(seen.chainCalled(), uri);
            assertEquals(401, seen.status(), uri);
            assertEquals(401, run(request(uri, "wrong", "u-1", "ADMIN", null)).status(), uri);
        }
        var ok = run(request("/internal/users/u-1/permissions", SECRET, null, null, null));
        assertTrue(ok.chainCalled());
        assertEquals(200, ok.status());
    }

    @Test
    void blankSecretConfigurationIsRejected() {
        assertThrows(IllegalStateException.class, () -> new UserContextFilter(" "));
    }
}
