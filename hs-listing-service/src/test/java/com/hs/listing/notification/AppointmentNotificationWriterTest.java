package com.hs.listing.notification;

import com.hs.listing.model.ViewingAppointment;
import com.hs.listing.model.Listing;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static com.hs.listing.notification.AppointmentNotificationWriter.Event.*;
import static com.hs.listing.notification.AppointmentNotificationWriter.Side.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppointmentNotificationWriterTest {
    @Test
    void serializesStrictContractAndAssignsANewIdentityForEveryTransition() throws Exception {
        var repository = mock(AppointmentNotificationOutboxRepository.class);
        var writer = new AppointmentNotificationWriter(repository, "homespace.notification.events.v1");
        var appointment = ViewingAppointment.builder().id("11111111-1111-4111-8111-111111111111")
                .ownerId("owner").renterId("renter")
                .listing(Listing.builder().title("Phòng <đẹp>\n trung tâm").build())
                .appointmentDate(LocalDate.of(2026, 11, 16)).startTime(LocalTime.of(8, 0)).build();
        writer.enqueue(appointment, BOOKING_REQUESTED, OWNER);
        writer.enqueue(appointment, BOOKING_CONFIRMED, RENTER);
        var captured = ArgumentCaptor.forClass(AppointmentNotificationOutbox.class);
        verify(repository, times(2)).save(captured.capture());
        var rows = captured.getAllValues();
        var json = JsonMapper.builder().build();
        var requested = json.readTree(rows.getFirst().getPayload());
        assertEquals(6, requested.size());
        assertEquals("BOOKING_REQUESTED", requested.get("eventType").asText());
        assertEquals("owner", requested.get("recipients").get(0).asText());
        assertEquals("OWNER", requested.get("data").get("recipientSide").asText());
        assertEquals(5, requested.get("data").size());
        assertEquals("Phòng đẹp trung tâm", requested.get("data").get("listingTitle").asText());
        assertEquals("2026-11-16", requested.get("data").get("appointmentDate").asText());
        assertEquals("08:00", requested.get("data").get("startTime").asText());
        String timestamp = requested.get("occurredAt").asText();
        assertTrue(timestamp.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
        assertNotNull(Instant.parse(timestamp));
        assertNotEquals(rows.getFirst().getEventId(), rows.getLast().getEventId());
        assertEquals("renter", json.readTree(rows.getLast().getPayload()).get("recipients").get(0).asText());
        Path fixture = Path.of("target", "booking-event-contract.json");
        Files.createDirectories(fixture.getParent());
        Files.writeString(fixture, rows.getFirst().getPayload());
    }

    @Test
    void rescheduleRequestShowsProposedTimeWithoutChangingTheCurrentAppointment() {
        var repository = mock(AppointmentNotificationOutboxRepository.class);
        var writer = new AppointmentNotificationWriter(repository, "homespace.notification.events.v1");
        var appointment = ViewingAppointment.builder().id("11111111-1111-4111-8111-111111111111")
                .ownerId("owner").renterId("renter")
                .appointmentDate(LocalDate.of(2026, 11, 16)).startTime(LocalTime.of(8, 0))
                .proposedDate(LocalDate.of(2026, 11, 18)).proposedStartTime(LocalTime.of(10, 0)).build();
        writer.enqueue(appointment, BOOKING_RESCHEDULE_REQUESTED, OWNER);
        var captured = ArgumentCaptor.forClass(AppointmentNotificationOutbox.class);
        verify(repository).save(captured.capture());
        var data = JsonMapper.builder().build().readTree(captured.getValue().getPayload()).get("data");
        assertEquals("2026-11-18", data.get("appointmentDate").asText());
        assertEquals("10:00", data.get("startTime").asText());
        assertNull(data.get("listingTitle"));
    }
}
