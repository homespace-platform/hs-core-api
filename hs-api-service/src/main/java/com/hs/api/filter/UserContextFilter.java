package com.hs.api.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;

@Component
public class UserContextFilter implements Filter {

    /**
     * Reads identity headers added by the API Gateway and exposes them through
     * Spring Security and the application user context.
     */
    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) servletRequest;

        String email = httpRequest.getHeader("X-User-Email");
        String userId = httpRequest.getHeader("X-User-Id");
        String role = httpRequest.getHeader("X-User-Role");
        String authoritiesRaw = httpRequest.getHeader("X-User-Authorities");

        // Convert the permission header into authorities used by @PreAuthorize.
        List<SimpleGrantedAuthority> authorities = Collections.emptyList();

        if (authoritiesRaw != null && !authoritiesRaw.isBlank()) {
            authorities = Arrays.stream(authoritiesRaw.split(","))
                    .filter(auth -> !auth.isBlank())
                    .map(SimpleGrantedAuthority::new)
                    .toList();
        }

        if (hasText(role)) {
            List<SimpleGrantedAuthority> roleAuthorities = List.of(
                    new SimpleGrantedAuthority(normalizeRole(role))
            );
            authorities = authorities.isEmpty()
                    ? roleAuthorities
                    : java.util.stream.Stream
                    .concat(authorities.stream(), roleAuthorities.stream())
                    .distinct()
                    .toList();
        }

        if (hasText(email) || hasText(userId) || !authorities.isEmpty()) {
            var auth = new UsernamePasswordAuthenticationToken(email, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        } else SecurityContextHolder.clearContext();


        // Store the current identity so business code does not parse HTTP headers.
        if (hasText(email) || hasText(userId)) {
            UserContextHolder.set(new UserContext(userId, email));
        } else UserContextHolder.clear();


        try {
            filterChain.doFilter(servletRequest, servletResponse);
        } finally {
            UserContextHolder.clear();
            SecurityContextHolder.clearContext();
        }

    }

    /** Returns whether a header contains a usable value. */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** Removes an optional ROLE_ prefix and normalizes the role to uppercase. */
    private String normalizeRole(String role) {
        return role.replaceFirst("^ROLE_", "").toUpperCase();
    }
}


