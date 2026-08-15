package com.hs.user.service;

import com.hs.user.dto.request.UpdateKeycloakUserRequest;
import com.hs.user.dto.request.UpdatePasswordRequest;

public interface KeycloakUserService {

    void updateUserIfChanged(String userId, UpdateKeycloakUserRequest request);

    void updatePassword(String userId, String username, UpdatePasswordRequest request);
}

