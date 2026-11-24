package com.hs.contract.notification;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractNotificationOutboxRepository extends JpaRepository<ContractNotificationOutbox, Long> {
    boolean existsByEventId(String eventId);
}
