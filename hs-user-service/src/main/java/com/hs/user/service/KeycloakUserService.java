package com.hs.user.service;

import com.hs.user.dto.request.AdminCreateUserRequest;
import com.hs.user.dto.request.UpdateKeycloakUserRequest;
import com.hs.user.dto.request.UpdatePasswordRequest;
import com.hs.user.dto.request.SetInitialPasswordRequest;
import com.hs.user.dto.response.AdminCreateUserResponse;

public interface KeycloakUserService {

    AdminCreateUserResponse createUser(AdminCreateUserRequest request);

    void updateUserIfChanged(String userId, UpdateKeycloakUserRequest request);

    void updatePassword(String userId, String username, UpdatePasswordRequest request);

    void setInitialPassword(String userId, SetInitialPasswordRequest request);

    boolean hasPassword(String userId);
}
