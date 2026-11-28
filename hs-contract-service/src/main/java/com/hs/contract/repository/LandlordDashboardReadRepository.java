package com.hs.contract.repository;

import com.hs.contract.model.Contract;
import com.hs.listing.model.constant.AppointmentStatus;
import com.hs.payment.model.constant.PaymentType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;

/** Grouped, owner-scoped reads of the landlord dashboard. Select only, never locked; every list takes a limit. */
public interface LandlordDashboardReadRepository extends org.springframework.data.repository.Repository<Contract, String> {

    record ExpiringListing(String id, String title, Instant expiresAt, Long viewCount) {}

    record Visit(String id, LocalDate date, LocalTime startTime, AppointmentStatus status, Boolean rescheduleRequested,
                 LocalDate proposedDate, LocalTime proposedStartTime, String renterName, String listingTitle) {}

    String T2L = "com.hs.payment.model.constant.PaymentDirection.TENANT_TO_LANDLORD";
    String L2T = "com.hs.payment.model.constant.PaymentDirection.LANDLORD_TO_TENANT";
    String PS = "com.hs.payment.model.constant.PaymentStatus.";
    String RECEIPTS = "p.id = r.paymentRequestId and p.status = " + PS + "CONFIRMED and r.landlordId = :ownerId "
            + "and (p.direction = " + T2L + " and p.payeeId = :ownerId or p.direction = " + L2T + " and p.payerId = :ownerId) "
            + "and r.issuedAt >= :from and r.issuedAt < :to ";
    String LINES = "and li.paymentRequestId = p.id and li.type in :depositLines ";

    /** (type, direction, count, sum) of the confirmed receipts issued in [from, to) for the landlord. */
    @Query("select p.type, p.direction, count(p), sum(p.totalAmount) from PaymentReceipt r, PaymentRequest p where "
            + RECEIPTS + "group by p.type, p.direction")
    List<Object[]> receiptTotals(@Param("ownerId") String ownerId, @Param("from") Instant from,
            @Param("to") Instant to);

    /** (type, direction, sum) of the deposit allocation lines inside those receipts. */
    @Query("select p.type, p.direction, sum(li.amount) from PaymentLineItem li, PaymentReceipt r, PaymentRequest p "
            + "where " + RECEIPTS + LINES + "group by p.type, p.direction")
    List<Object[]> receiptDepositLines(@Param("ownerId") String ownerId, @Param("from") Instant from,
            @Param("to") Instant to, @Param("depositLines") Collection<String> depositLines);

    String EXPECTED = "p.payeeId = :ownerId and p.direction = " + T2L + " and p.active = true and p.type in :types "
            + "and p.dueAt >= :from and p.dueAt < :to and p.status not in (" + PS + "CANCELLED, " + PS + "EXPIRED) ";

    /** (type, sum) of what falls due in [from, to), already confirmed ones included. */
    @Query("select p.type, sum(p.totalAmount) from PaymentRequest p where " + EXPECTED + "group by p.type")
    List<Object[]> expectedTotals(@Param("ownerId") String ownerId, @Param("types") Collection<PaymentType> types,
            @Param("from") Instant from, @Param("to") Instant to);

    @Query("select p.type, sum(li.amount) from PaymentRequest p, PaymentLineItem li where " + EXPECTED + LINES
            + "group by p.type")
    List<Object[]> expectedDepositLines(@Param("ownerId") String ownerId, @Param("types") Collection<PaymentType> types,
            @Param("from") Instant from, @Param("to") Instant to, @Param("depositLines") Collection<String> depositLines);

    /**
     * (direction, status, count, sum, earliest confirmation_due_at, earliest due_at) of what waits for the landlord:
     * reported transfers to confirm and rejected ones to be reported again (as payee), refunds owed (as payer).
     */
    @Query("select p.direction, p.status, count(p), sum(p.totalAmount), min(p.confirmationDueAt), min(p.dueAt) "
            + "from PaymentRequest p where p.active = true and (p.payeeId = :ownerId and p.direction = " + T2L
            + " and p.status in (" + PS + "TRANSFER_REPORTED, " + PS + "REJECTED) or p.payerId = :ownerId "
            + "and p.direction = " + L2T + " and p.status in (" + PS + "AWAITING_TRANSFER, " + PS + "REJECTED, "
            + PS + "OVERDUE)) group by p.direction, p.status")
    List<Object[]> waitingOnOwner(@Param("ownerId") String ownerId);

    /** (status, review status, count, oldest submitted_at) of the active listings. */
    @Query("select l.status, l.reviewOperationalStatus, count(l), min(l.submittedAt) from Listing l "
            + "where l.ownerId = :ownerId and l.active = true group by l.status, l.reviewOperationalStatus")
    List<Object[]> listingCounts(@Param("ownerId") String ownerId);

    /** (status, review status, count) of the active listings whose expires_at is not after {@code realNow}. */
    @Query("select l.status, l.reviewOperationalStatus, count(l) from Listing l where l.ownerId = :ownerId "
            + "and l.active = true and l.expiresAt <= :realNow group by l.status, l.reviewOperationalStatus")
    List<Object[]> pastExpiryListingCounts(@Param("ownerId") String ownerId, @Param("realNow") Instant realNow);

    @Query("select new com.hs.contract.repository.LandlordDashboardReadRepository$ExpiringListing(l.id, l.title, "
            + "l.expiresAt, l.viewCount) from Listing l where l.ownerId = :ownerId and l.active = true "
            + "and (l.status = com.hs.listing.model.constant.ListingStatus.PUBLISHED or "
            + "l.status = com.hs.listing.model.constant.ListingStatus.PENDING_REVIEW and "
            + "l.reviewOperationalStatus = com.hs.listing.model.constant.ListingStatus.PUBLISHED) and l.expiresAt > :realNow "
            + "and l.expiresAt <= :until order by l.expiresAt, l.id")
    List<ExpiringListing> expiringListings(@Param("ownerId") String ownerId, @Param("realNow") Instant realNow,
            @Param("until") Instant until, Pageable page);

    /** Pending requests that wait for the landlord on listings outside any branch (the attention list skips them). */
    @Query("select count(r) from RentalRequest r join r.listing l where r.ownerId = :ownerId and r.active = true "
            + "and r.status = com.hs.listing.model.constant.RentalRequestStatus.PENDING "
            + "and (r.respondBy is null or r.respondBy > :now) and l.branchId is null and l.ownerId = :ownerId and l.active = true")
    long unbranchedRentalRequests(@Param("ownerId") String ownerId, @Param("now") Instant now);

    /** Same open condition as {@code findOpenAppointments} plus the lapse rule of the attention list. */
    @Query("select count(a) from ViewingAppointment a join a.listing l where a.ownerId = :ownerId and a.active = true "
            + "and l.branchId is null and l.ownerId = :ownerId and l.active = true and (a.status = com.hs.listing.model.constant.AppointmentStatus.PENDING "
            + "and (a.appointmentDate > :today or a.appointmentDate = :today and a.endTime >= :time) "
            + "or a.status = com.hs.listing.model.constant.AppointmentStatus.CONFIRMED "
            + "and a.rescheduleRequested = true)")
    long unbranchedAppointments(@Param("ownerId") String ownerId, @Param("today") LocalDate today,
            @Param("time") LocalTime time);

    String VISIT_DATE = "case when a.rescheduleRequested = true and a.proposedDate is not null and a.proposedStartTime is not null then a.proposedDate else a.appointmentDate end";
    String VISIT_TIME = "case when a.rescheduleRequested = true and a.proposedDate is not null and a.proposedStartTime is not null then a.proposedStartTime else a.startTime end";
    @Query("select new com.hs.contract.repository.LandlordDashboardReadRepository$Visit(a.id, a.appointmentDate, "
            + "a.startTime, a.status, a.rescheduleRequested, a.proposedDate, a.proposedStartTime, a.renterName, "
            + "l.title) from ViewingAppointment a join a.listing l where a.ownerId = :ownerId and a.active = true "
            + "and l.ownerId = :ownerId and l.active = true and a.status in (com.hs.listing.model.constant.AppointmentStatus.PENDING, "
            + "com.hs.listing.model.constant.AppointmentStatus.CONFIRMED) and (" + VISIT_DATE + " > :today or "
            + VISIT_DATE + " = :today and " + VISIT_TIME + " >= :time) order by " + VISIT_DATE + ", " + VISIT_TIME + ", a.id")
    List<Visit> upcomingVisits(@Param("ownerId") String ownerId, @Param("today") LocalDate today,
            @Param("time") LocalTime time, Pageable page);

    /** (status, count) of the landlord's own leases; deposit documents and annexes are not leases. */
    @Query("select c.status, count(c) from Contract c where c.landlordId = :ownerId and c.active = true "
            + "and c.parentContractId is null and (c.agreementKind is null or c.agreementKind <> 'DEPOSIT') "
            + "group by c.status")
    List<Object[]> contractCounts(@Param("ownerId") String ownerId);

    @Query("select count(b) from BankAccount b where b.userId = :ownerId and b.active = true "
            + "and b.status = com.hs.payment.model.constant.BankAccountStatus.ACTIVE "
            + "and b.defaultForIncomingPayments = true")
    long receivingAccounts(@Param("ownerId") String ownerId);
}
