package com.hs.user.dto.request;

import java.util.Set;

public record UpdateRoleRequest(
        String description,
        Set<String> permissionIdList
) {
}
