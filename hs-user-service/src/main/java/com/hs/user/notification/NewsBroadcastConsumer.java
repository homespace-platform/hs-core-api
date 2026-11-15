package com.hs.user.notification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class NewsBroadcastConsumer {
    private final BroadcastNotificationService broadcasts;
    private final KafkaTemplate<String, String> kafka;
    private final String deadLetterTopic;

    public NewsBroadcastConsumer(BroadcastNotificationService broadcasts, KafkaTemplate<String, String> kafka,
            @Value("${spring.kafka.topic.news-events-dlt:homespace.news.events.dlt.v1}") String deadLetterTopic) {
        this.broadcasts = broadcasts;
        this.kafka = kafka;
        this.deadLetterTopic = deadLetterTopic;
    }

    @KafkaListener(topics = "${spring.kafka.topic.news-events}", groupId = "notification-broadcast-core-v1",
            containerFactory = "newsBroadcastKafkaListenerContainerFactory", properties = "enable.auto.commit=false")
    public void consume(ConsumerRecord<String, String> record) {
        try {
            broadcasts.enqueueNews(record.value());
        } catch (BroadcastNotificationService.InvalidNewsEvent | BroadcastNotificationService.IdConflict invalid) {
            // No producer content is copied to the DLT; returning acknowledges only an already durable rejection.
            String hash = sha256(record.value());
            var rejection = BroadcastNotificationService.JSON.valueToTree(Map.of(
                    "sourceTopic", record.topic(), "partition", record.partition(), "offset", record.offset(),
                    "sha256", hash, "reason", "INVALID_NEWS_EVENT", "failedAt", BroadcastNotificationService.TIMESTAMP.format(Instant.now())));
            try {
                kafka.send(deadLetterTopic, hash, rejection.toString()).get(15, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("News dead letter interrupted", failure);
            } catch (Exception failure) { throw new IllegalStateException("News dead letter failed", failure); }
        }
    }

    private static String sha256(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
