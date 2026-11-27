package com.hs.user.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.hs.common.advice.entity.AppException;
import com.hs.user.model.User;
import com.hs.user.repository.UserRepository;

class UserEmailLookupServiceTest {
    final UserRepository users = mock(UserRepository.class);
    final UserEmailLookupService service = new UserEmailLookupService(users);

    static User user(String id, String email, boolean active) {
        var u = new User();
        u.setId(id); u.setEmail(email); u.setActive(active);
        return u;
    }

    @Test void returnsOnlyActiveUsersWithAddress() {
        when(users.findAllById(List.of("a", "b", "c"))).thenReturn(List.of(user("a", "a@x.io", true), user("b", "b@x.io", false), user("c", " ", true)));
        assertEquals(List.of(new UserEmailLookupService.UserEmail("a", "a@x.io")), service.findActive(List.of("a", "b", "c")));
    }

    @Test void rejectsEmptyOrOversizedRequests() {
        assertThrows(AppException.class, () -> service.findActive(List.of()));
        assertThrows(AppException.class, () -> service.findActive(java.util.stream.IntStream.range(0, 101).mapToObj(String::valueOf).toList()));
        verifyNoInteractions(users);
    }
}
