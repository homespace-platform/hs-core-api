package com.hs.api.advice.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hs.common.advice.entity.enums.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

class GlobalExceptionTest {
    @Test
    void methodSecurityDenialIs403PermissionDeniedNotOwnership1003() {
        var response = new GlobalException().handlingAccessDeniedException(
                new org.springframework.security.access.AccessDeniedException("denied"));
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(ErrorCode.PERMISSION_DENIED.getCode(), response.getBody().getCode());
    }

    @Test
    void authenticationExceptionsMapTo401UnauthenticatedEnvelope() {
        for (AuthenticationException failure : new AuthenticationException[]{
                new AuthenticationCredentialsNotFoundException("no auth"),
                new InsufficientAuthenticationException("weak"), new BadCredentialsException("bad")}) {
            var response = new GlobalException().handlingAuthenticationException(failure);
            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
            assertEquals(ErrorCode.UNAUTHENTICATED.getCode(), response.getBody().getCode());
        }
    }
}
