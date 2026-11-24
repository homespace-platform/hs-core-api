package com.hs.payment.notification;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentNotificationOutboxRepository extends JpaRepository<PaymentNotificationOutbox, Long> {
    boolean existsByEventId(String eventId);
    java.util.Optional<PaymentNotificationOutbox> findByEventId(String eventId);
}
