package com.hs.contract.repository;

import com.hs.contract.model.Contract;
import com.hs.contract.model.ContractAmendment;
import com.hs.contract.model.ContractClosure;
import com.hs.listing.model.constant.AppointmentStatus;
import com.hs.listing.model.constant.ListingEnums.MediaType;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.model.constant.PaymentType;
import com.hs.storage.model.constant.StorageVisibility;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;

/** Select-only batch reads of the tenant dashboard. Every query is scoped by the verified user id, never locked. */
public interface TenantDashboardReadRepository extends org.springframework.data.repository.Repository<Contract, String> {
    String R = "com.hs.contract.repository.TenantDashboardReadRepository$";

    record LeaseRow(String contractId, String contractNumber, String listingId, String landlordId,
                    LocalDate agreedEarlyEnd, Integer agreedEarlyMonths, Integer effectiveMonths, LocalDate moveInDate,
                    Integer leaseMonths, BigDecimal monthlyRent) {}

    record SignRow(String id, String number, String rentalRequestId, String parentId, String agreementKind,
                   Instant dueAt) {}

    record ExitRow(String id, String contractId, String proposedBy, Instant respondBy) {}

    record SigRow(String contractId, Instant expiresAt) {}

    record HoldRow(String id, String listingId, Instant holdExpiresAt) {}

    record VisitRow(String id, String listingId, AppointmentStatus status, Boolean rescheduleRequested,
                    LocalDate date, LocalTime startTime, LocalTime endTime, LocalDate proposedDate,
                    LocalTime proposedStartTime) {}

    record RequestRow(String id, String listingId, RentalRequestStatus status, Instant createdAt, Instant respondBy,
                      Instant holdExpiresAt) {}

    record SpendRow(Instant issuedAt, PaymentType type, BigDecimal amount) {}

    record ComplaintRow(String id, String contractId, String rentalRequestId, String category, String status,
                        Instant respondBy, String responseDueFor) {}

    record ListingInfo(String id, String title, String address) {}

    record Media(String listingId, boolean cover, Integer sortOrder, String bucket, String key) {}

    record Name(String id, String firstName, String lastName) {}

    record Ref(String id, String value) {}

    record InvoicePeriod(String id, int periodIndex) {}
    record TerminationRow(String contractId, String contractNumber, String invoiceId, String history) {}

    @Query("select new " + R + "TerminationRow(c.id, c.contractNumber, i.id, c.unilateralTerminationSnapshot) "
            + "from Contract c join MonthlyInvoice i on i.id = c.terminationProposalInvoiceId "
            + "join PaymentRequest p on p.id = i.paymentRequestId where c.tenantId = :u and c.active = true "
            + "and c.status = com.hs.contract.model.constant.ContractStatus.ACTIVE and c.terminationProposedAt is not null "
            + "and c.terminationProposedAt <= :now and c.terminationAcceptedAt is null and c.terminationDeclinedAt is null "
            + "and c.terminationCancelledAt is null and c.terminationCompletedAt is null and c.mutualTerminationWorkflowId is null "
            + "and p.payerId = :u and p.active = true and p.status in :statuses "
            + "and i.status in (com.hs.contract.model.MonthlyInvoiceStatus.UNPAID, com.hs.contract.model.MonthlyInvoiceStatus.OVERDUE) "
            + "order by c.id")
    List<TerminationRow> findTerminationProposals(@Param("u") String userId, @Param("now") Instant now,
            @Param("statuses") Collection<PaymentStatus> statuses, Pageable limit);

    record DepositRow(String contractId, String contractNumber, BigDecimal amount, String status,
                      String closureStatus, BigDecimal refundAmount, Instant refundDueAt) {}
    record WaitRow(String id, String type, String contractId, String contractNumber, Instant sentAt, Instant respondBy) {}
    interface SpendBucket {
        String getMonth();
        String getType();
        BigDecimal getAmount();
    }
    interface MoneyTotals {
        long getOverdueCount();
        BigDecimal getOverdueAmount();
        BigDecimal getDueSoonAmount();
        long getAwaitingCount();
        BigDecimal getAwaitingAmount();
    }
    String OPEN_MONEY = """
            with money as (
              select p.*, case when p.type in (:billingTypes) or p.type = 'OTHER_CHARGE' and p.contract_id is not null
                     then cast(:now as timestamptz) else cast(:real as timestamptz) end as clock_now
              from payment_requests p where p.payer_id = :u and p.direction = 'TENANT_TO_LANDLORD'
                and p.active = true and p.total_amount > 0
                and p.type in ('INITIAL','RESERVATION_DEPOSIT','MONTHLY_RENT','UTILITY','OTHER_CHARGE','DEPOSIT_ADJUSTMENT')
                and p.status in ('AWAITING_TRANSFER','REJECTED','OVERDUE','TRANSFER_REPORTED')
            )
            """;

    @Query(value = OPEN_MONEY + """
            select count(*) filter (where status <> 'TRANSFER_REPORTED' and due_at < clock_now
                     and (opens_at is null or opens_at <= clock_now)) as "overdueCount",
                   coalesce(sum(total_amount) filter (where status <> 'TRANSFER_REPORTED' and due_at < clock_now
                     and (opens_at is null or opens_at <= clock_now)), 0) as "overdueAmount",
                   coalesce(sum(total_amount) filter (where status <> 'TRANSFER_REPORTED' and due_at >= clock_now
                     and due_at <= clock_now + interval '7 days' and (opens_at is null or opens_at <= clock_now)), 0) as "dueSoonAmount",
                   count(*) filter (where status = 'TRANSFER_REPORTED') as "awaitingCount",
                   coalesce(sum(total_amount) filter (where status = 'TRANSFER_REPORTED'), 0) as "awaitingAmount"
            from money
            """, nativeQuery = true)
    MoneyTotals moneyTotals(@Param("u") String userId, @Param("now") Instant now, @Param("real") Instant real,
                            @Param("billingTypes") Collection<String> billingTypes);

    @Query(value = OPEN_MONEY + """
            select * from money where status <> 'TRANSFER_REPORTED' and (opens_at is null or opens_at <= clock_now)
            order by case when status <> 'TRANSFER_REPORTED' and due_at < clock_now then 0 else 1 end,
                     due_at nulls last, id
            """, nativeQuery = true)
    List<PaymentRequest> findDashboardPayments(@Param("u") String userId, @Param("now") Instant now,
            @Param("real") Instant real, @Param("billingTypes") Collection<String> billingTypes, Pageable limit);

    @Query(value = OPEN_MONEY + """
            select * from (select distinct on (contract_id) * from money where status <> 'TRANSFER_REPORTED'
              and due_at >= clock_now and (opens_at is null or opens_at <= clock_now)
            order by contract_id, due_at, id) next
            where contract_id in (:contractIds) or id = (select id from money where status <> 'TRANSFER_REPORTED'
              and due_at >= clock_now and (opens_at is null or opens_at <= clock_now) order by due_at, id limit 1)
            order by due_at, id
            """, nativeQuery = true)
    List<PaymentRequest> findNextPayments(@Param("u") String userId, @Param("now") Instant now,
            @Param("real") Instant real, @Param("billingTypes") Collection<String> billingTypes,
            @Param("contractIds") Collection<String> contractIds, Pageable limit);

    @Query("select p from PaymentRequest p where p.payerId = :u and p.direction = "
            + "com.hs.payment.model.constant.PaymentDirection.TENANT_TO_LANDLORD and p.active = true "
            + "and p.status = com.hs.payment.model.constant.PaymentStatus.TRANSFER_REPORTED and p.totalAmount > 0 "
            + "order by p.confirmationDueAt, p.id")
    List<PaymentRequest> findAwaitingPayments(@Param("u") String userId, Pageable limit);

    @Query("select p from PaymentRequest p where p.payerId = :u and p.rentalRequestId in :ids and p.active = true "
            + "and p.direction = com.hs.payment.model.constant.PaymentDirection.TENANT_TO_LANDLORD "
            + "and p.type in (com.hs.payment.model.constant.PaymentType.INITIAL, "
            + "com.hs.payment.model.constant.PaymentType.RESERVATION_DEPOSIT) and p.status in :statuses")
    List<PaymentRequest> findHoldPayments(@Param("u") String userId, @Param("ids") Collection<String> ids,
            @Param("statuses") Collection<PaymentStatus> statuses);

    @Query("select count(p), coalesce(sum(p.totalAmount), 0) from PaymentRequest p where p.payeeId = :u and p.direction = "
            + "com.hs.payment.model.constant.PaymentDirection.LANDLORD_TO_TENANT and p.active = true "
            + "and p.type in :types and p.status in :statuses and p.totalAmount > 0")
    List<Object[]> refundTotals(@Param("u") String userId, @Param("types") Collection<PaymentType> types,
            @Param("statuses") Collection<PaymentStatus> statuses);

    @Query("select u.onBoarded from User u where u.id = :u")
    Boolean isOnboarded(@Param("u") String userId);

    @Query("select new " + R + "DepositRow(c.id, c.contractNumber, d.heldAmount, cast(d.status as string), "
            + "s.status, s.refundAmount, s.refundDueAt) from DepositRecord d join Contract c on "
            + "(d.contractId = c.id or d.contractId is null and d.rentalRequestId = c.rentalRequestId) "
            + "left join ContractClosure s on s.contractId = c.id where d.tenantId = :u and c.tenantId = :u "
            + "and d.active = true and c.active = true and c.parentContractId is null "
            + "and (c.agreementKind is null or c.agreementKind <> 'DEPOSIT') order by c.id")
    List<DepositRow> findDeposits(@Param("u") String userId, Pageable limit);

    @Query("select coalesce(sum(d.heldAmount), 0) from DepositRecord d join Contract c on "
            + "(d.contractId = c.id or d.contractId is null and d.rentalRequestId = c.rentalRequestId) "
            + "where d.tenantId = :u and c.tenantId = :u and d.active = true and c.active = true "
            + "and c.parentContractId is null and (c.agreementKind is null or c.agreementKind <> 'DEPOSIT')")
    BigDecimal totalHeldDeposits(@Param("u") String userId);

    /** Old payments can prove a deposit principal, but cannot prove its current held balance without a ledger. */
    @Query("select new " + R + "DepositRow(c.id, c.contractNumber, sum(l.amount), 'CONFIRMED_PAYMENT', "
            + "s.status, s.refundAmount, s.refundDueAt) from Contract c join PaymentRequest p on "
            + "p.rentalRequestId = c.rentalRequestId join PaymentLineItem l on l.paymentRequestId = p.id "
            + "left join ContractClosure s on s.contractId = c.id where c.tenantId = :u and p.payerId = :u "
            + "and c.active = true and c.parentContractId is null and (c.agreementKind is null or c.agreementKind <> 'DEPOSIT') "
            + "and p.status = com.hs.payment.model.constant.PaymentStatus.CONFIRMED and p.direction = "
            + "com.hs.payment.model.constant.PaymentDirection.TENANT_TO_LANDLORD and l.type = 'DEPOSIT' "
            + "and not exists (select d.id from DepositRecord d where d.rentalRequestId = c.rentalRequestId) "
            + "group by c.id, c.contractNumber, s.status, s.refundAmount, s.refundDueAt order by c.id")
    List<DepositRow> findLegacyDeposits(@Param("u") String userId, Pageable limit);

    @Query("select s from ContractClosure s join Contract c on c.id = s.contractId where c.tenantId = :u "
            + "and c.active = true and c.status = com.hs.contract.model.constant.ContractStatus.EXPIRED "
            + "and s.status in ('PROPOSED', 'DISPUTED') order by s.respondBy, s.contractId")
    List<ContractClosure> findClosures(@Param("u") String userId, Pageable limit);

    @Query("select new " + R + "WaitRow(r.id, 'RENEWAL_REQUESTED', c.id, c.contractNumber, r.requestedAt, r.respondBy) "
            + "from ContractRenewal r join Contract c on c.id = r.contractId where c.tenantId = :u "
            + "and c.active = true and c.status = com.hs.contract.model.constant.ContractStatus.ACTIVE "
            + "and r.status = 'REQUESTED' and r.respondBy > :now order by r.respondBy, r.id")
    List<WaitRow> findWaitingRenewals(@Param("u") String userId, @Param("now") Instant now, Pageable limit);

    @Query("select new " + R + "WaitRow(w.id, 'EARLY_EXIT_PROPOSED', c.id, c.contractNumber, null, w.respondBy) "
            + "from RentalWorkflowRecord w join Contract c on c.id = w.contractId where c.tenantId = :u "
            + "and c.active = true and c.status = com.hs.contract.model.constant.ContractStatus.ACTIVE "
            + "and w.proposedBy = :u and w.kind = 'EARLY_EXIT' and w.status = 'PROPOSED' and w.respondBy > :now "
            + "order by w.respondBy, w.id")
    List<WaitRow> findWaitingExits(@Param("u") String userId, @Param("now") Instant now, Pageable limit);

    @Query("select new " + R + "LeaseRow(c.id, c.contractNumber, c.listingId, c.landlordId, c.agreedEarlyEndExclusive, "
            + "c.agreedEarlyBillingMonths, c.effectiveLeaseMonths, r.moveInDate, r.leaseMonths, "
            + "coalesce(r.effectiveMonthlyRent, r.monthlyRentPrice)) from Contract c, RentalRequest r "
            + "where r.id = c.rentalRequestId and c.tenantId = :u and c.active = true and c.parentContractId is null "
            + "and (c.agreementKind is null or c.agreementKind <> 'DEPOSIT') "
            + "and c.status = com.hs.contract.model.constant.ContractStatus.ACTIVE order by c.id")
    List<LeaseRow> findLeases(@Param("u") String userId, Pageable limit);

    @Query("select count(c) from Contract c join RentalRequest r on r.id = c.rentalRequestId "
            + "where c.tenantId = :u and c.active = true and c.parentContractId is null "
            + "and (c.agreementKind is null or c.agreementKind <> 'DEPOSIT') "
            + "and c.status = com.hs.contract.model.constant.ContractStatus.ACTIVE")
    long countLeases(@Param("u") String userId);

    /** Refunds the landlord owes this user, still open. */
    @Query("select p from PaymentRequest p where p.payeeId = :u and p.direction = "
            + "com.hs.payment.model.constant.PaymentDirection.LANDLORD_TO_TENANT and p.active = true "
            + "and p.type in :types and p.status in :statuses and p.totalAmount > 0 "
            + "order by case when p.status = com.hs.payment.model.constant.PaymentStatus.TRANSFER_REPORTED then 0 else 1 end, "
            + "p.confirmationDueAt, p.dueAt, p.id")
    List<PaymentRequest> findIncomingRefunds(@Param("u") String userId, @Param("types") Collection<PaymentType> types,
            @Param("statuses") Collection<PaymentStatus> statuses, Pageable limit);

    @Query("select new " + R + "SignRow(c.id, c.contractNumber, c.rentalRequestId, c.parentContractId, c.agreementKind, "
            + "coalesce(a.signBy, r.signBy, c.tenantSigningDueAt)) from Contract c "
            + "left join ContractAmendment a on a.agreementContractId = c.id "
            + "left join ContractRenewal r on r.agreementContractId = c.id "
            + "where c.tenantId = :u and c.active = true "
            + "and (a.id is null or a.status = 'APPROVED') and (r.id is null or r.status = 'APPROVED') "
            + "and (c.parentContractId is null or exists (select root.id from Contract root "
            + "where root.id = c.parentContractId and root.tenantId = :u and root.active = true "
            + "and root.rentalRequestId = c.rentalRequestId and ((a.id is not null or r.id is not null) "
            + "and root.status = com.hs.contract.model.constant.ContractStatus.ACTIVE or c.agreementKind = 'WORKFLOW' "
            + "and root.status in (com.hs.contract.model.constant.ContractStatus.ACTIVE, "
            + "com.hs.contract.model.constant.ContractStatus.EXPIRED) and exists (select d.id from WorkflowSigningDocument d "
            + "join RentalWorkflowRecord w on w.id = d.workflowId where d.signingContractId = c.id "
            + "and w.contractId = root.id and w.kind = 'EARLY_EXIT' and w.status = 'APPROVED')))) "
            + "and c.status = com.hs.contract.model.constant.ContractStatus.TENANT_SIGNATURE_PENDING order by c.id")
    List<SignRow> findSigningContracts(@Param("u") String userId, Pageable limit);

    @Query("select a from ContractAmendment a where a.contractId in :contractIds and a.status in :statuses")
    List<ContractAmendment> findAmendments(@Param("contractIds") Collection<String> contractIds,
            @Param("statuses") Collection<String> statuses);

    @Query("select new " + R + "ExitRow(w.id, w.contractId, w.proposedBy, w.respondBy) from RentalWorkflowRecord w "
            + "where w.contractId in :contractIds and w.kind = 'EARLY_EXIT' and w.status = 'PROPOSED'")
    List<ExitRow> findProposedEarlyExits(@Param("contractIds") Collection<String> contractIds);

    @Query("select new " + R + "SigRow(s.contractId, case when s.status = "
            + "com.hs.contract.model.constant.SignatureRequestStatus.PENDING_USER_CONFIRMATION then s.expiresAt else null end) "
            + "from SignatureRequest s join Contract c on c.id = s.contractId "
            + "where c.tenantId = :u and c.active = true and s.active = true and c.currentRevisionId = s.revisionId "
            + "and c.status = com.hs.contract.model.constant.ContractStatus.TENANT_SIGNATURE_PENDING and s.signerUserId = :u "
            + "and (s.status = com.hs.contract.model.constant.SignatureRequestStatus.PENDING_USER_CONFIRMATION "
            + "and (s.expiresAt is null or s.expiresAt > :now) or s.status in ("
            + "com.hs.contract.model.constant.SignatureRequestStatus.FAILED, "
            + "com.hs.contract.model.constant.SignatureRequestStatus.EXPIRED, "
            + "com.hs.contract.model.constant.SignatureRequestStatus.REJECTED)) "
            + "and not exists (select newer.id from SignatureRequest newer where newer.contractId = s.contractId "
            + "and newer.signerUserId = s.signerUserId and newer.revisionId = s.revisionId and newer.active = true "
            + "and (newer.createdAt > s.createdAt or newer.createdAt = s.createdAt and newer.id > s.id)) "
            + "order by s.expiresAt, s.id")
    List<SigRow> findSignaturesToConfirm(@Param("u") String userId, @Param("now") Instant now, Pageable limit);

    @Query("select new " + R + "HoldRow(r.id, r.listing.id, r.holdExpiresAt) from RentalRequest r "
            + "where r.renterId = :u and r.active = true and r.status = "
            + "com.hs.listing.model.constant.RentalRequestStatus.ACCEPTED and r.holdExpiresAt > :now "
            + "order by r.holdExpiresAt, r.id")
    List<HoldRow> findHolds(@Param("u") String userId, @Param("now") Instant now, Pageable limit);

    @Query("select r.id from RentalRequest r where r.renterId = :u and r.active = true and r.status = "
            + "com.hs.listing.model.constant.RentalRequestStatus.ACCEPTED order by r.createdAt desc, r.id")
    List<String> findAcceptedRequests(@Param("u") String userId, Pageable limit);

    /** The "upcoming" filter my-bookings lacks: later date, or today and not started yet (Vietnam wall clock). */
    String UPCOMING = "from ViewingAppointment a where a.renterId = :u and a.active = true and a.status in :statuses "
            + "and (a.appointmentDate > :today or a.appointmentDate = :today and a.startTime >= :time)";

    @Query("select new " + R + "VisitRow(a.id, a.listing.id, a.status, a.rescheduleRequested, a.appointmentDate, "
            + "a.startTime, a.endTime, a.proposedDate, a.proposedStartTime) " + UPCOMING
            + " order by a.appointmentDate, a.startTime, a.id")
    List<VisitRow> findUpcomingVisits(@Param("u") String userId,
            @Param("statuses") Collection<AppointmentStatus> statuses, @Param("today") LocalDate today,
            @Param("time") LocalTime time, Pageable limit);

    @Query("select count(a) " + UPCOMING)
    long countUpcomingVisits(@Param("u") String userId, @Param("statuses") Collection<AppointmentStatus> statuses,
            @Param("today") LocalDate today, @Param("time") LocalTime time);

    @Query("select r.status, count(r) from RentalRequest r where r.renterId = :u and r.active = true group by r.status")
    List<Object[]> countRequestsByStatus(@Param("u") String userId);

    @Query("select new " + R + "RequestRow(r.id, r.listing.id, r.status, r.createdAt, r.respondBy, r.holdExpiresAt) "
            + "from RentalRequest r where r.renterId = :u and r.active = true and r.status in :statuses "
            + "order by r.createdAt desc, r.id")
    List<RequestRow> findOpenRequests(@Param("u") String userId,
            @Param("statuses") Collection<RentalRequestStatus> statuses, Pageable limit);

    /** Receipts of money this user paid; only CONFIRMED payments have a receipt, the status check is a safeguard. */
    @Query(value = """
            select to_char(r.issued_at at time zone 'Asia/Ho_Chi_Minh', 'YYYY-MM') as month,
                   p.type as type, sum(p.total_amount) as amount
            from payment_receipts r join payment_requests p on p.id = r.payment_request_id
            where r.tenant_id = :u and p.payer_id = :u and p.direction = 'TENANT_TO_LANDLORD'
              and p.status = 'CONFIRMED' and r.issued_at >= :from and r.issued_at < :to
              and p.type in ('INITIAL','RESERVATION_DEPOSIT','DEPOSIT_ADJUSTMENT','MONTHLY_RENT','UTILITY','OTHER_CHARGE')
            group by month, p.type order by month, p.type
            """, nativeQuery = true)
    List<SpendBucket> findSpending(@Param("u") String userId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("select count(r) from PaymentReceipt r where r.tenantId = :u and r.issuedAt >= :since and "
            + "(r.pdfStatus = com.hs.payment.receipt.PaymentReceipt.PdfStatus.FAILED or r.pdfStatus = "
            + "com.hs.payment.receipt.PaymentReceipt.PdfStatus.PENDING and r.issuedAt < :stale)")
    long countReceiptPdfIssues(@Param("u") String userId, @Param("since") Instant since, @Param("stale") Instant stale);

    @Query("select new " + R + "ComplaintRow(d.id, d.contractId, d.rentalRequestId, "
            + "coalesce(d.complaintCategory, d.subjectType), cast(d.status as string), d.respondBy, d.responseDueFor) "
            + "from DisputeCase d where (d.payerId = :u or d.payeeId = :u) and d.active = true and d.status <> "
            + "com.hs.payment.model.constant.DisputeCaseStatus.RESOLVED "
            + "order by case when d.respondBy is null then 1 else 0 end, d.respondBy, d.id")
    List<ComplaintRow> findOpenComplaints(@Param("u") String userId, Pageable limit);

    @Query("select count(d), coalesce(sum(case when d.responseDueFor = :u then 1 else 0 end), 0) from DisputeCase d "
            + "where (d.payerId = :u or d.payeeId = :u) and d.active = true and d.status <> "
            + "com.hs.payment.model.constant.DisputeCaseStatus.RESOLVED")
    List<Object[]> countComplaints(@Param("u") String userId);

    @Query("select l.status, count(f) from ListingFavorite f join f.listing l where f.userId = :u "
            + "and f.active = true and l.active = true group by l.status")
    List<Object[]> countSavedByListingStatus(@Param("u") String userId);

    @Query("select new " + R + "ListingInfo(l.id, l.title, a.display) from Listing l "
            + "left join ListingAddress a on a.listingId = l.id and a.active = true where l.id in :ids")
    List<ListingInfo> findListingInfo(@Param("ids") Collection<String> ids);

    @Query("select new " + R + "Media(m.listing.id, m.cover, m.sortOrder, o.bucketName, o.objectKey) "
            + "from ListingMedia m join m.storageObject o where m.listing.id in :ids and m.active = true "
            + "and m.mediaType = :type and o.visibility = :visibility")
    List<Media> findPublicImages(@Param("ids") Collection<String> ids, @Param("type") MediaType type,
            @Param("visibility") StorageVisibility visibility);

    @Query("select new " + R + "Name(u.id, u.firstName, u.lastName) from User u where u.id in :ids")
    List<Name> findNames(@Param("ids") Collection<String> ids);

    /** Own contracts only: a payment pointing at somebody else's contract shows no number. */
    @Query("select new " + R + "Ref(c.id, c.contractNumber) from Contract c where c.id in :ids and c.tenantId = :u")
    List<Ref> findOwnContractNumbers(@Param("ids") Collection<String> ids, @Param("u") String userId);

    @Query("select new " + R + "InvoicePeriod(i.id, i.periodIndex) from MonthlyInvoice i join Contract c on c.id = i.contractId "
            + "where i.id in :ids and c.tenantId = :u")
    List<InvoicePeriod> findInvoicePeriods(@Param("ids") Collection<String> ids, @Param("u") String userId);
}
