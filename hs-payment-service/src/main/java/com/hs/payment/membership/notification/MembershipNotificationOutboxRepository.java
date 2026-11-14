package com.hs.payment.membership.notification;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipNotificationOutboxRepository extends JpaRepository<MembershipNotificationOutbox, Long> {
}
