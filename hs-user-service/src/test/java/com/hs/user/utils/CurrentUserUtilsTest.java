package com.hs.user.utils;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContextHolder;
import com.hs.user.repository.UserRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class CurrentUserUtilsTest {
    @Test void missingContextIsUnauthenticatedNotNullPointer() {
        UserContextHolder.clear();
        var ex = assertThrows(AppException.class,
                () -> new CurrentUserUtils(mock(UserRepository.class)).getCurrentUserId());
        assertEquals(ErrorCode.UNAUTHENTICATED.getCode(), ex.getCode());
    }
}
