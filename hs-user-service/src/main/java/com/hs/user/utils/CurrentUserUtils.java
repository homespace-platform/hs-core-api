package com.hs.user.utils;

import org.springframework.stereotype.Component;

import com.hs.common.advice.entity.AppException;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.common.context.UserContextHolder;
import com.hs.user.model.User;
import com.hs.user.repository.UserRepository;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class CurrentUserUtils {

    UserRepository userRepository;

    public String getCurrentUserId() {
        return UserContextHolder.get().userId();
    }

    public User getCurrentUser() {
        return userRepository.findById(getCurrentUserId())
                .orElseThrow(() -> new AppException(UserErrorCode.USER_NOT_EXISTED));
    }
}

