package com.hs.user.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hs.user.model.Permission;
import org.junit.jupiter.api.Test;

class PermissionMapperTest {
    private static String scopeOf(String name) {
        var p = new Permission();
        p.setName(name);
        return PermissionMapper.mapToPermissionResponse(p).scope();
    }

    @Test void scopeIsComputedFromTheCatalog() {
        assertEquals("USER", scopeOf("KYC_SUBMIT"));
        assertEquals("ADMIN", scopeOf("USER_VIEW"));
        assertEquals("ADMIN", scopeOf("LEGACY_UNKNOWN"));
    }
}
