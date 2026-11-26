package com.hs.user.event;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class ChatProfileChanges {
    private final KafkaTemplate<String, String> kafka;
    private final String topic;

    public ChatProfileChanges(
            KafkaTemplate<String, String> kafka,
            @Value("${spring.kafka.topic.chat-profile}") String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    public void afterCommit(String userId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // Kafka metadata lookup can block. Profile saves can complete independently.
                Thread.startVirtualThread(() -> publish(userId));
            }
        });
    }

    private void publish(String userId) {
        try {
            kafka.send(topic, userId, userId).exceptionally(error -> {
                log.warn("Could not publish chat profile change");
                return null;
            });
        } catch (RuntimeException error) {
            log.warn("Could not publish chat profile change");
        }
    }
}
