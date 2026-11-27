package com.hs.contract.dto.branch;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Every key is always present; nullable values are sent as null. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record BranchOverviewResponse(String branchId, String month, Instant billingNow, Instant realNow,
                                     List<Room> rooms, List<Payment> unplacedPayments) {

    public enum RoomStatus { OCCUPIED, RESERVED, MOVING_IN, EXTERNAL_RENT, REPAIR, ADVERTISED, VACANT, UNLINKED }

    /** Declaration order is the display order of payments; NONE only describes a room without any. */
    public enum PaymentStatus { OVERDUE, PENDING_CONFIRMATION, DUE_SOON, NOT_DUE, UNISSUED, PAID, NONE }

    public enum PaymentKind { MONTHLY, INITIAL, PROJECTED, SETTLEMENT_BALANCE, DEPOSIT_ADJUSTMENT }

    public enum Clock { BILLING, REAL }

    public enum TargetKind {
        CONTRACT, INVOICE, PAYMENT, AMENDMENT, WORKFLOW, RENTAL_REQUEST, APPOINTMENT, LISTING, ROOM, TRAY
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Target(TargetKind kind, String id, String href) {}

    /** Declaration order is the display order. */
    public enum MeterStatus { TODAY, UPCOMING, RECORDED }

    /** Declaration order is the display order. */
    public enum ExpiryStatus { WITHIN_30, WITHIN_60, LONG }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Room(String roomId, RoomStatus status, String tenantName, Target target, Stay nextStay,
                       PaymentLayer payments, List<Meter> meters, List<Expiry> contracts, String listingStatus) {

        public Room(String roomId, RoomStatus status, String tenantName, Target target, Stay nextStay,
                    PaymentLayer payments) {
            this(roomId, status, tenantName, target, nextStay, payments, List.of(), List.of(), null);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Meter(String contractId, int periodIndex, MeterStatus status, LocalDate meterDate,
                        Instant deadlineAt, Target target) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Expiry(String contractId, ExpiryStatus status, LocalDate endDateExclusive, int daysRemaining,
                         Target target) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Stay(String tenantName, LocalDate moveInDate, Target target) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PaymentLayer(PaymentStatus status, int count, List<Payment> items) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Payment(String id, PaymentKind kind, String contractId, PaymentStatus status, Instant dueAt,
                          Instant confirmationDueAt, Clock clock, Target target) {}
}
