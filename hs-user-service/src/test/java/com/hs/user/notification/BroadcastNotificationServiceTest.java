package com.hs.user.notification;

import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BroadcastNotificationServiceTest {
    static final String NEWS = """
            {"eventId":"news_123","eventType":"NEWS_PUBLISHED","version":1,"occurredAt":"2026-11-15T00:00:00.000Z","recipients":[],"data":{"newsId":"123","slug":"new-news"}}
            """;
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final BroadcastNotificationService service = new BroadcastNotificationService(jdbc, "notification.test");
    final Map<String, String> stored = new HashMap<>();

    void database() {
        when(jdbc.update(eq(BroadcastNotificationService.INSERT_SQL), any(), any(), any(), any(), any()))
                .thenAnswer(call -> stored.putIfAbsent(call.getArgument(1), call.getArgument(3)) == null ? 1 : 0);
        when(jdbc.queryForObject(anyString(), eq(String.class), anyString())).thenAnswer(call -> stored.get(call.getArgument(2)));
    }

    @Test void sourceNewsRequiresExactTypedEnvelopeAndCanonicalTime() {
        assertEquals("news_123", BroadcastNotificationService.parseNews(NEWS).get("eventId").asText());
        List<java.util.function.Consumer<ObjectNode>> invalid = List.of(
                n -> n.put("extra", true), n -> n.put("version", 2), n -> n.put("version", 1.0),
                n -> n.put("eventId", "other"), n -> n.put("eventType", "PLATFORM_ANNOUNCEMENT"),
                n -> n.putArray("recipients").add("person"), n -> n.put("occurredAt", "2026-02-30T00:00:00.000Z"),
                n -> n.put("occurredAt", "2026-11-15T00:00:00Z"),
                n -> ((ObjectNode)n.get("data")).put("slug", "../unsafe"),
                n -> ((ObjectNode)n.get("data")).put("slug", 123),
                n -> ((ObjectNode)n.get("data")).put("newsId", 123),
                n -> ((ObjectNode)n.get("data")).put("title", "private"));
        for (var change : invalid) {
            var node = (ObjectNode) BroadcastNotificationService.read(NEWS); change.accept(node);
            assertThrows(BroadcastNotificationService.InvalidNewsEvent.class, () -> service.enqueueNews(node.toString()));
        }
        for (String value : Arrays.asList(null, "null", "{}", NEWS + "{}", NEWS.replace("\"version\":1", "\"version\":1,\"version\":1"), " ".repeat(65537)))
            assertThrows(BroadcastNotificationService.InvalidNewsEvent.class, () -> service.enqueueNews(value));
        verifyNoInteractions(jdbc);
    }

    @Test void newsRetryPreservesFrozenTemplateAndConflictingSourceIsRejected() {
        database(); service.enqueueNews(NEWS); service.enqueueNews(NEWS);
        assertEquals(1, stored.size());
        assertEquals("2026-11-15T00:00:00.000Z", BroadcastNotificationService.read(stored.get("news_123")).get("occurredAt").asText());
        assertThrows(BroadcastNotificationService.IdConflict.class, () -> service.enqueueNews(NEWS.replace("new-news", "changed-news")));
        assertThrows(BroadcastNotificationService.IdConflict.class, () -> service.enqueueNews(NEWS.replace("00:00:00.000", "00:00:01.000")));
    }

    @Test void rolePermissionsChangedPayloadHasOnlyRoleNameAndSafeUniqueId() {
        database(); service.enqueueRolePermissionsChanged("USER");
        assertEquals(1, stored.size());
        String id = stored.keySet().iterator().next();
        assertTrue(id.matches("role_perms_USER_\\d{13}"), id);
        var event = BroadcastNotificationService.read(stored.get(id));
        assertEquals("ROLE_PERMISSIONS_CHANGED", event.get("eventType").asText());
        assertEquals(1, event.get("version").asInt()); assertEquals(0, event.get("recipients").size());
        assertEquals(Set.of("roleName"), new HashSet<>(event.get("data").properties().stream().map(Map.Entry::getKey).toList()));
        assertEquals("USER", event.get("data").get("roleName").asText());
        service.enqueueRolePermissionsChanged("we!rd name/" + "x".repeat(300));
        assertEquals(2, stored.size());
        for (String key : stored.keySet()) assertTrue(key.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,199}"), key);
    }

    @Test void adminIdempotencyComparesNormalizedContentAndConflictsAre409() {
        database(); String id = UUID.randomUUID().toString();
        var request = new AdminBroadcastRequest(id, " Title ", "Body\nsecond line", AdminBroadcastRequest.Priority.URGENT, null);
        service.enqueueAdmin(request); String frozen = stored.get("admin_" + id); service.enqueueAdmin(request);
        assertEquals(frozen, stored.get("admin_" + id));
        var data = BroadcastNotificationService.read(frozen).get("data");
        assertEquals(Set.of("title", "body", "priority", "actionPath"), new HashSet<>(data.properties().stream().map(Map.Entry::getKey).toList()));
        assertEquals("Title", data.get("title").asText()); assertEquals("URGENT", data.get("priority").asText());
        assertEquals("/notifications", data.get("actionPath").asText());
        var conflict = assertThrows(BroadcastNotificationService.IdConflict.class, () -> service.enqueueAdmin(
                new AdminBroadcastRequest(id, "changed", request.body(), request.priority(), null)));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
    }
}
