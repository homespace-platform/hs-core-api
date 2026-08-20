package com.hs.api.facade;

import org.springframework.stereotype.Service;

import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.service.StorageService;
import com.hs.user.service.UserService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserAvatarFacade {
    private final StorageService storageService;
    private final UserService userService;

    public void updateCurrentUserAvatar(String storageId) {
        String publicUrl = storageService.getOwnedPublicUrl(storageId, StoragePurpose.USER_AVATAR);
        userService.updateUserAvatar(publicUrl);
    }
}
