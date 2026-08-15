package com.hs.user.utils;

import org.springframework.stereotype.Component;

import com.hs.user.advice.base.AppException;
import com.hs.user.constant.base.ErrorCode;
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
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
    }
}

