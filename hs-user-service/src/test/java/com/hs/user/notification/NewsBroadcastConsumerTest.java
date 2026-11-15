package com.hs.user.notification;

import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.core.KafkaTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NewsBroadcastConsumerTest {
    final BroadcastNotificationService service = mock(BroadcastNotificationService.class);
    @SuppressWarnings("unchecked") final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    final NewsBroadcastConsumer consumer = new NewsBroadcastConsumer(service, kafka, "news.dlt.test");
    final ConsumerRecord<String, String> record = new ConsumerRecord<>("news.test", 2, 42, "ignored", "private producer content");

    @Test void invalidEnvelopeIsRedactedAndRequiresAcknowledgedDeadLetter() {
        doThrow(new BroadcastNotificationService.InvalidNewsEvent()).when(service).enqueueNews(record.value());
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        consumer.consume(record);
        var payload = ArgumentCaptor.forClass(String.class); verify(kafka).send(eq("news.dlt.test"), matches("[a-f0-9]{64}"), payload.capture());
        assertFalse(payload.getValue().contains("private"));
        var rejection = BroadcastNotificationService.read(payload.getValue());
        assertEquals("news.test", rejection.get("sourceTopic").asText()); assertEquals(42, rejection.get("offset").asLong());
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        assertThrows(IllegalStateException.class, () -> consumer.consume(record));
    }

    @Test void databaseFailureEscapesForRetryWithoutDeadLetterOrSourceRecovery() {
        doThrow(new CannotGetJdbcConnectionException("offline")).when(service).enqueueNews(record.value());
        assertThrows(CannotGetJdbcConnectionException.class, () -> consumer.consume(record)); verifyNoInteractions(kafka);
    }

    @Test void successfulDurableAcceptanceNeedsNoKafkaCallAndIdCollisionUsesDeadLetter() {
        consumer.consume(record); verify(service).enqueueNews(record.value()); verifyNoInteractions(kafka);
        doThrow(new BroadcastNotificationService.IdConflict()).when(service).enqueueNews(record.value());
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        consumer.consume(record); verify(kafka).send(eq("news.dlt.test"), anyString(), anyString());
    }
}
