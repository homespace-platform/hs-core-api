package com.hs.listing.notification;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RentalRequestNotificationOutboxRepository extends JpaRepository<RentalRequestNotificationOutbox, Long> {
}
