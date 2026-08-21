package com.hs.notification.repository;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import com.hs.notification.model.NotificationDelivery;

import lombok.RequiredArgsConstructor;

/** Persists notification delivery audit documents in MongoDB. */
@Repository
@RequiredArgsConstructor
public class NotificationDeliveryRepository {
    private final MongoTemplate mongoTemplate;

    public NotificationDelivery save(NotificationDelivery delivery) {
        return mongoTemplate.save(delivery);
    }
}
