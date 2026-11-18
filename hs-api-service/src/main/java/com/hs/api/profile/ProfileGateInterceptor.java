package com.hs.api.profile;

import com.hs.common.context.UserContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class ProfileGateInterceptor implements HandlerInterceptor {

    private static final Pattern AUTHORITY = Pattern.compile("hasAuthority\\('([^']+)'\\)");

    private final ProfileRequirementService requirements;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || !method.hasMethodAnnotation(RequiresCompleteProfile.class)) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // Anonymous callers fall through to method security (401).
        if (auth == null || UserContextHolder.get() == null || UserContextHolder.get().userId() == null) {
            return true;
        }
        // Interceptors run before method security: let a missing permission (1009) win over the profile check.
        PreAuthorize pre = method.getMethodAnnotation(PreAuthorize.class);
        if (pre != null) {
            var m = AUTHORITY.matcher(pre.value());
            if (m.find() && auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals(m.group(1)))) {
                return true;
            }
        }
        var missing = requirements.evaluate().missing();
        if (!missing.isEmpty()) {
            throw new ProfileIncompleteException(missing);
        }
        return true;
    }
}
