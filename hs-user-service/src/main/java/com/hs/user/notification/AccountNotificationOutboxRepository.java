package com.hs.user.notification;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountNotificationOutboxRepository extends JpaRepository<AccountNotificationOutbox, Long> {
    boolean existsByEventId(String eventId);
}
