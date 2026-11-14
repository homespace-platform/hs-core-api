package com.hs.user.notification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.user.model.User;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountNotificationWriterTest {
    final AccountNotificationOutboxRepository rows = mock(AccountNotificationOutboxRepository.class);
    final AccountNotificationWriter writer = new AccountNotificationWriter(rows, "account.test.topic");
    final ObjectMapper json = new ObjectMapper();
    User recipient() {
        User u = new User();u.setId("persisted-target");u.setEmail("private@secret.test");u.setPhone("0900000000");return u;
    }
    @Test void allTransitionsHaveOnePersistedRecipientAndEmptyDataAndExportRealJavaFixtures() throws Exception {
        for (var type : AccountNotificationWriter.Event.values()) writer.enqueue(recipient(), "verified-admin", type);
        var captured = ArgumentCaptor.forClass(AccountNotificationOutbox.class);
        verify(rows,times(3)).save(captured.capture());
        var ids = new HashSet<String>();var fixtures = new ArrayList<Map<String,Object>>();
        for (var row : captured.getAllValues()) {
            Map<String,Object> e = json.readValue(row.getPayload(),new TypeReference<>(){});
            assertEquals(Set.of("eventId","eventType","version","occurredAt","recipients","data"),e.keySet());
            assertEquals(row.getEventId(),e.get("eventId"));assertTrue(ids.add(row.getEventId()));
            assertEquals(Map.of(),e.get("data"));assertEquals(List.of("persisted-target"),e.get("recipients"));
            assertEquals(1,e.get("version"));assertEquals("persisted-target",row.getUserId());
            String timestamp=(String)e.get("occurredAt");assertTrue(timestamp.matches(".*\\.\\d{3}Z"));assertDoesNotThrow(()->Instant.parse(timestamp));
            assertEquals("account.test.topic",row.getTopic());assertNull(row.getSentAt());
            assertFalse(row.getPayload().contains("private"));assertFalse(row.getPayload().contains("verified-admin"));
            fixtures.add(e);
        }
        Path path=Path.of("target","account-event-contracts.json");Files.createDirectories(path.getParent());
        Files.writeString(path,json.writeValueAsString(fixtures));
    }
    @Test void sameActorIsSuppressedAndDistinctTransitionsKeepDifferentIdentities() {
        writer.enqueue(recipient(),"persisted-target",AccountNotificationWriter.Event.ACCOUNT_ENABLED);verifyNoInteractions(rows);
        writer.enqueue(recipient(),"admin",AccountNotificationWriter.Event.ACCOUNT_DISABLED);
        writer.enqueue(recipient(),"admin",AccountNotificationWriter.Event.ACCOUNT_DISABLED);
        var captured=ArgumentCaptor.forClass(AccountNotificationOutbox.class);verify(rows,times(2)).save(captured.capture());
        assertNotEquals(captured.getAllValues().getFirst().getEventId(),captured.getAllValues().getLast().getEventId());
    }
    @Test void missingRecipientCannotCreateAnEvent() {
        for (String id : Arrays.asList(null,""," ")) {
            User u=recipient();u.setId(id);
            assertThrows(IllegalStateException.class,()->writer.enqueue(u,"admin",AccountNotificationWriter.Event.ACCOUNT_DISABLED));
        }
        verifyNoInteractions(rows);
    }
}
