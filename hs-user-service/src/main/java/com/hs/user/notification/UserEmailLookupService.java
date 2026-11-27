package com.hs.user.notification;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.hs.common.advice.entity.AppException;
import com.hs.user.repository.UserRepository;
import org.springframework.http.HttpStatus;

/** Service-to-service lookup used by notification-service to email broadcast recipients. */
@Service
public class UserEmailLookupService {
    static final int MAX_IDS = 100;
    private final UserRepository users;

    public UserEmailLookupService(UserRepository users) { this.users = users; }

    public record Request(List<String> userIds) {}
    public record UserEmail(String userId, String email) {}

    /** Active users with an address only; unknown or disabled ids are simply absent. */
    @Transactional(readOnly = true)
    public List<UserEmail> findActive(List<String> userIds) {
        if (userIds == null || userIds.isEmpty() || userIds.size() > MAX_IDS)
            throw new AppException(2020, "userIds must contain 1 to " + MAX_IDS + " ids", HttpStatus.BAD_REQUEST);
        return users.findAllById(userIds).stream()
                .filter(u -> Boolean.TRUE.equals(u.getActive()) && u.getEmail() != null && !u.getEmail().isBlank())
                .map(u -> new UserEmail(u.getId(), u.getEmail()))
                .toList();
    }
}
