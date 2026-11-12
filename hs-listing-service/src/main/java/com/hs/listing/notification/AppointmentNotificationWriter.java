package com.hs.listing.notification;

import com.hs.listing.model.ViewingAppointment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AppointmentNotificationWriter {
    public enum Side { OWNER, RENTER }
    public enum Event {
        BOOKING_REQUESTED, BOOKING_CONFIRMED, BOOKING_REJECTED, BOOKING_CANCELLED,
        BOOKING_RESCHEDULE_REQUESTED, BOOKING_RESCHEDULE_APPROVED, BOOKING_RESCHEDULE_REJECTED,
        BOOKING_COMPLETED, BOOKING_EXPIRED
    }
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final JsonMapper json = JsonMapper.builder().build();
    private final AppointmentNotificationOutboxRepository outbox;
    private final String topic;

    public AppointmentNotificationWriter(AppointmentNotificationOutboxRepository outbox,
            @Value("${notification.events-topic:homespace.notification.events.v1}") String topic) {
        this.outbox = outbox;
        this.topic = topic;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(ViewingAppointment appointment, Event type, Side side) {
        String recipient = side == Side.OWNER ? appointment.getOwnerId() : appointment.getRenterId();
        if (recipient == null || recipient.isBlank()) throw new IllegalStateException("Appointment recipient missing");
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Map<String, String> data = new HashMap<>();
        data.put("bookingId", appointment.getId());
        data.put("recipientSide", side.name());
        if (appointment.getListing() != null && appointment.getListing().getTitle() != null) {
            String title = appointment.getListing().getTitle().replaceAll("[<>\\p{Cntrl}]", " ")
                    .replaceAll("\\s+", " ").trim();
            if (!title.isEmpty()) data.put("listingTitle", title.substring(0, Math.min(title.length(), 120)).trim());
        }
        LocalDate date = type == Event.BOOKING_RESCHEDULE_REQUESTED
                ? appointment.getProposedDate() : appointment.getAppointmentDate();
        LocalTime start = type == Event.BOOKING_RESCHEDULE_REQUESTED
                ? appointment.getProposedStartTime() : appointment.getStartTime();
        if (date != null) data.put("appointmentDate", date.toString());
        if (start != null) data.put("startTime", start.format(DateTimeFormatter.ofPattern("HH:mm")));
        String payload = json.writeValueAsString(Map.of(
                "eventId", eventId, "eventType", type.name(), "version", 1,
                "occurredAt", TIMESTAMP.format(now), "recipients", List.of(recipient),
                "data", data));
        outbox.save(new AppointmentNotificationOutbox(eventId, appointment.getId(), topic, payload, now));
    }
}
