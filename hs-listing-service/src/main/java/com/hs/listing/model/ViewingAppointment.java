package com.hs.listing.model;

import com.hs.common.persistence.BaseEntity;
import com.hs.listing.model.constant.AppointmentCancelledBy;
import com.hs.listing.model.constant.AppointmentStatus;
import com.hs.listing.model.constant.ListingEnums.ViewingSlot;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "viewing_appointments", indexes = {
        @Index(name = "idx_appointment_listing_date", columnList = "listing_id, appointment_date"),
        @Index(name = "idx_appointment_owner_status", columnList = "owner_id, status"),
        @Index(name = "idx_appointment_renter_status", columnList = "renter_id, status"),
        @Index(name = "idx_appointment_date_status", columnList = "appointment_date, status")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ViewingAppointment extends BaseEntity {

    @Id
    @Column(length = 36, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "listing_id", nullable = false)
    private Listing listing;

    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;

    @Column(name = "renter_id", nullable = false, length = 36)
    private String renterId;

    @Column(name = "renter_name", nullable = false, length = 100)
    private String renterName;

    @Column(name = "renter_phone", nullable = false, length = 20)
    private String renterPhone;

    @Column(name = "renter_email", length = 150)
    private String renterEmail;

    @Builder.Default
    @Column(name = "visitor_count", nullable = false)
    private Integer visitorCount = 1;

    @Column(name = "renter_note", columnDefinition = "TEXT")
    private String renterNote;

    @Column(name = "appointment_date", nullable = false)
    private LocalDate appointmentDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "slot_type", length = 20)
    private ViewingSlot slotType;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private AppointmentStatus status = AppointmentStatus.PENDING;

    @Column(name = "owner_note", columnDefinition = "TEXT")
    private String ownerNote;

    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancelled_by", length = 20)
    private AppointmentCancelledBy cancelledBy;

    @Column(name = "cancel_reason", columnDefinition = "TEXT")
    private String cancelReason;

    // Các trường phục vụ yêu cầu Đổi lịch hẹn (Reschedule)
    @Builder.Default
    @Column(name = "is_reschedule_requested", nullable = false)
    private Boolean rescheduleRequested = false;

    @Column(name = "proposed_date")
    private LocalDate proposedDate;

    @Column(name = "proposed_start_time")
    private LocalTime proposedStartTime;

    @Column(name = "proposed_end_time")
    private LocalTime proposedEndTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "proposed_slot_type", length = 20)
    private ViewingSlot proposedSlotType;

    @Column(name = "reschedule_reason", columnDefinition = "TEXT")
    private String rescheduleReason;

    @Column(name = "completed_at")
    private Instant completedAt;
}
