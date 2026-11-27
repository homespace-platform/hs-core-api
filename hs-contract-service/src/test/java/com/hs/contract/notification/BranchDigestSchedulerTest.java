package com.hs.contract.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.contract.dto.branch.BranchAttentionResponse.BranchCount;
import com.hs.contract.dto.branch.BranchAttentionResponse.Summary;
import com.hs.contract.service.BranchAttentionService;
import com.hs.listing.repository.PropertyBranchRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BranchDigestSchedulerTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 27);

    @Test
    void readsOwnersInBatchesSkipsEmptyAndContinuesAfterAnOwnersFailures() {
        PropertyBranchRepository branches = mock();
        BranchAttentionService attention = mock();
        BranchDigestOutboxWriter writer = mock();
        var first = IntStream.range(0, 100).mapToObj(i -> "owner-%03d".formatted(i)).toList();
        var page = PageRequest.of(0, 100);
        when(branches.findActiveOwnerIds("", page)).thenReturn(first);
        when(branches.findActiveOwnerIds("owner-099", page)).thenReturn(List.of("owner-next"));
        when(attention.summary(anyString())).thenReturn(summary());
        when(attention.summary("owner-000")).thenThrow(new IllegalStateException("read failed"));
        when(attention.summary("owner-001")).thenReturn(summary(new BranchCount("private-branch", 4)));
        when(attention.summary("owner-002")).thenReturn(summary(new BranchCount("branch-a", 2), new BranchCount("branch-b", 3)));
        when(attention.summary("owner-next")).thenReturn(summary(new BranchCount("branch-c", 1)));
        when(writer.write("owner-001", DAY, 4)).thenThrow(new IllegalStateException("writes failed"));
        when(writer.write("owner-002", DAY, 5)).thenThrow(new IllegalStateException("retry")).thenReturn(true);

        new BranchDigestScheduler(branches, attention, writer).send(DAY);

        var writes = inOrder(writer);
        writes.verify(writer, times(2)).write("owner-001", DAY, 4);
        writes.verify(writer, times(2)).write("owner-002", DAY, 5);
        writes.verify(writer).write("owner-next", DAY, 1);
        verifyNoMoreInteractions(writer);
    }

    @Test
    void runsAtEightInVietnamAndWritesOnlyCountToItsOwnAggregate() throws Exception {
        var schedule = BranchDigestScheduler.class.getMethod("sendDaily").getAnnotation(Scheduled.class);
        assertEquals("0 0 8 * * *", schedule.cron());
        assertEquals("Asia/Ho_Chi_Minh", schedule.zone());

        JdbcTemplate jdbc = mock();
        var writer = new BranchDigestOutboxWriter(jdbc, "events");
        var eventId = UUID.nameUUIDFromBytes(("BRANCH_DAILY_DIGEST:owner:" + DAY)
                .getBytes(StandardCharsets.UTF_8)).toString();
        when(jdbc.update(anyString(), eq(eventId), eq(eventId), eq("events"), anyString(), any()))
                .thenAnswer(call -> {
                    assertTrue(call.getArgument(0, String.class).contains("ON CONFLICT (event_id) DO NOTHING"));
                    var json = new ObjectMapper();
                    var payload = json.readTree(call.getArgument(4, String.class));
                    var timestamp = payload.get("occurredAt").asText();
                    assertTrue(timestamp.matches(".*\\.\\d{3}Z"));
                    Instant.parse(timestamp);
                    assertEquals(json.readTree("""
                            {"eventId":"%s","eventType":"BRANCH_DAILY_DIGEST","version":1,"occurredAt":"%s",
                             "recipients":["owner"],"data":{"attentionCount":"5"}}
                            """.formatted(eventId, timestamp)), payload);
                    return 1;
                }).thenReturn(0);

        assertTrue(writer.write("owner", DAY, 5));
        assertFalse(writer.write("owner", DAY, 5));
    }

    private static Summary summary(BranchCount... branches) {
        return new Summary(Instant.EPOCH, Instant.EPOCH, List.of(branches));
    }
}
