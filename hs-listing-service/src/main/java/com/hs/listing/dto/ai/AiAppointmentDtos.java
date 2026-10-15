package com.hs.listing.dto.ai;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * Read-only projections of viewing appointments for the AI assistant. Deliberately narrower than
 * {@code AppointmentResponse}: no phone, email, user ids or thumbnails. Every value that is missing in
 * the database is {@code null} (never a made-up default), and the stored status is kept apart from the
 * time-derived {@code temporalPhase}.
 */
public final class AiAppointmentDtos {
    private AiAppointmentDtos() {}

    /** Reference time. Viewing appointments use the real Asia/Ho_Chi_Minh clock, not the contract billing clock. */
    public record Meta(Instant readAt, Instant serverNow, String timezone, LocalDate today, LocalTime nowLocalTime,
                       String clock, String weekRule, String phaseRule) {}

    public record DateRange(LocalDate from, LocalDate to, String preset) {}

    public record Filters(String role, List<String> statuses, DateRange dateRange, LocalTime timeFrom, LocalTime timeTo,
                          String listingId, Boolean rescheduleRequested, List<String> timePhases, String q, String sort) {}

    public record PageInfo(int page, int size, long totalElements, int totalPages, boolean hasNext) {}

    public record Schedule(LocalDate date, LocalTime startTime, LocalTime endTime, String slotType) {}

    public record ListingRef(String listingId, String title, String address) {}

    /** One row. renterName is only filled when the caller is the owner of the appointment. */
    public record Item(String appointmentId, String requesterRole, ListingRef listing, Schedule currentSchedule,
                       Schedule proposedSchedule, String status, String temporalPhase, boolean statusSyncPending,
                       boolean rescheduleRequested, Integer visitorCount, String renterName, Instant createdAt) {}

    public record SearchResponse(Meta meta, Filters filters, PageInfo page, List<Item> items) {}

    public record SummaryResponse(Meta meta, Filters filters, long total, Map<String, Long> byStatus,
                                  Map<String, Long> byRole, Map<String, Long> byTemporalPhase,
                                  long pendingRescheduleRequests, long expectedVisitorsTotal,
                                  long appointmentsCountedForVisitors, Item nextUpcoming, List<String> notes) {}

    public record Notes(String renterNote, String ownerNote) {}

    public record Actions(List<String> available, Map<String, List<String>> blocked) {}

    public record Detail(Meta meta, String appointmentId, String requesterRole, ListingRef listing,
                         Schedule currentSchedule, Schedule proposedSchedule, String rescheduleReason, String status,
                         String temporalPhase, boolean statusSyncPending, boolean rescheduleRequested,
                         Integer visitorCount, String renterName, Notes notes, String rejectReason,
                         String cancelledBy, String cancelReason, Instant createdAt, Instant updatedAt,
                         Instant completedAt, String completionNote, Actions actions, Map<String, String> unavailable) {}

    public record AvailabilitySlot(LocalTime startTime, LocalTime endTime, String slotType, String status,
                                   int bookingCount) {}

    public record Availability(Meta meta, ListingRef listing, LocalDate date, String dayOfWeek, boolean dayAccepted,
                               List<String> allowedViewingDays, List<String> allowedViewingSlots,
                               List<AvailabilitySlot> slots, boolean callerIsOwner, String callerActiveBookingId,
                               List<String> notes) {}
}
