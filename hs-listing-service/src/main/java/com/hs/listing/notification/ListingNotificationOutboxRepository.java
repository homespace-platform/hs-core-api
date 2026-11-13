package com.hs.listing.notification;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ListingNotificationOutboxRepository extends JpaRepository<ListingNotificationOutbox, Long> {
}
