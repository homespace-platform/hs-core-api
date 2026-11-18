package com.hs.api.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.security.Permissions;

@Component
public class UserContextFilter implements Filter {

    static final String INTERNAL_SECRET_HEADER = "X-Internal-Secret";
    private static final Set<String> ROLES = Set.of(Permissions.ROLE_ADMIN, Permissions.ROLE_USER);

    private final byte[] internalSecret;

    public UserContextFilter(@Value("${homespace.gateway.internal-secret}") String internalSecret) {
        if (internalSecret == null || internalSecret.isBlank()) {
            throw new IllegalStateException("GATEWAY_INTERNAL_SECRET must not be blank");
        }
        this.internalSecret = internalSecret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Reads identity headers added by the API Gateway and exposes them through
     * Spring Security and the application user context. The headers are honoured only when the
     * request carries the shared Gateway secret; otherwise the request is anonymous.
     */
    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) servletRequest;
        boolean trusted = hasValidSecret(httpRequest.getHeader(INTERNAL_SECRET_HEADER));

        if (!trusted && isInternalPath(httpRequest)) {
            ((HttpServletResponse) servletResponse).sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        String userId = trusted ? httpRequest.getHeader("X-User-Id") : null;
        String email = trusted ? httpRequest.getHeader("X-User-Email") : null;
        String role = trusted ? normalizeRole(httpRequest.getHeader("X-User-Role")) : null;

        // Authenticated only with a verified user id AND a known role; anything else is anonymous.
        if (hasText(userId) && role != null) {
            var auth = new UsernamePasswordAuthenticationToken(
                    hasText(email) ? email : userId, null,
                    authorities(httpRequest.getHeader("X-User-Authorities"), role));
            SecurityContextHolder.getContext().setAuthentication(auth);
            // Store the current identity so business code does not parse HTTP headers.
            UserContextHolder.set(new UserContext(userId, email));
        } else {
            SecurityContextHolder.clearContext();
            UserContextHolder.clear();
        }

        try {
            filterChain.doFilter(servletRequest, servletResponse);
        } finally {
            UserContextHolder.clear();
            SecurityContextHolder.clearContext();
        }

    }

    /** Constant-time comparison; the secret is never logged. */
    private boolean hasValidSecret(String provided) {
        return provided != null
                && MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), internalSecret);
    }

    /** True for /internal/** after URL decoding, so encoded or prefixed variants cannot slip past. */
    private boolean isInternalPath(HttpServletRequest request) {
        String path;
        try {
            path = UriUtils.decode(request.getRequestURI(), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return true;
        }
        // Drop ;matrix=params from every segment so /internal;x=1/... cannot dodge the match.
        return path.replaceAll(";[^/]*", "").toLowerCase(Locale.ROOT).matches(".*/internal(/.*)?");
    }

    /** Role authority plus trimmed permission tokens; a permission token can never name a role. */
    private List<SimpleGrantedAuthority> authorities(String permissionsRaw, String role) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        if (hasText(permissionsRaw)) {
            for (String token : permissionsRaw.split(",")) {
                String permission = token.trim();
                if (!permission.isEmpty() && normalizeRole(permission) == null) {
                    authorities.add(new SimpleGrantedAuthority(permission));
                }
            }
        }
        authorities.add(new SimpleGrantedAuthority(role));
        return authorities.stream().distinct().toList();
    }

    /** Returns whether a header contains a usable value. */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** Strips an optional ROLE_ prefix and returns ADMIN/USER, or null for anything else. */
    private String normalizeRole(String role) {
        if (role == null) return null;
        String normalized = role.trim().replaceFirst("(?i)^ROLE_", "").toUpperCase(Locale.ROOT);
        return ROLES.contains(normalized) ? normalized : null;
    }
}
