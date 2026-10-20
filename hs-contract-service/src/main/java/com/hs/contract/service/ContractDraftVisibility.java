package com.hs.contract.service;

import com.hs.contract.model.Contract;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.model.constant.PaymentType;
import jakarta.persistence.criteria.*;
import java.util.Objects;

/** A deposit-backed onboarding draft is reviewable, not signed or effective. */
public final class ContractDraftVisibility {
    private ContractDraftVisibility() {}

    public static boolean eligibleRental(Contract c, RentalRequest r) {
        return c.getStatus()==ContractStatus.DRAFT && c.getParentContractId()==null && r!=null
            && Objects.equals(c.getRentalRequestId(),r.getId()) && r.getStatus()==RentalRequestStatus.ACCEPTED
            && Boolean.TRUE.equals(r.getActive()) && Objects.equals(c.getTenantId(),r.getRenterId())
            && Objects.equals(c.getLandlordId(),r.getOwnerId());
    }

    public static boolean confirmedForParties(Contract c, PaymentRequest p) {
        return p.getStatus()==PaymentStatus.CONFIRMED && Objects.equals(p.getRentalRequestId(),c.getRentalRequestId())
            && (p.getType()==PaymentType.RESERVATION_DEPOSIT || p.getType()==PaymentType.INITIAL)
            && Objects.equals(p.getPayerId(),c.getTenantId()) && Objects.equals(p.getPayeeId(),c.getLandlordId());
    }

    /**
     * Single-record version of {@link #tenantReadable}: used by detail, documents and storage access so every path
     * applies the same rule. Previewing is read-only; it never grants edit, render, date-change or signing rights.
     */
    public static boolean tenantMayReview(Contract contract, com.hs.listing.repository.RentalRequestRepository rentals,
            com.hs.payment.repository.PaymentRequestRepository payments) {
        if (contract == null || contract.getRentalRequestId() == null || rentals == null || payments == null) return false;
        var rental = rentals.findById(contract.getRentalRequestId()).orElse(null);
        if (!eligibleRental(contract, rental)) return false;
        return java.util.stream.Stream.of(PaymentType.RESERVATION_DEPOSIT, PaymentType.INITIAL)
            .map(type -> payments.findByRentalRequestIdAndType(contract.getRentalRequestId(), type))
            .flatMap(java.util.Optional::stream).anyMatch(p -> confirmedForParties(contract, p));
    }

    /** SQL filtering keeps pagination/counts correct; do not filter a fetched page in Java. */
    public static Predicate tenantReadable(Root<Contract> c, CommonAbstractCriteria query, CriteriaBuilder cb) {
        var accepted=query.subquery(Integer.class);
        var r=accepted.from(RentalRequest.class);
        accepted.select(cb.literal(1)).where(cb.equal(r.get("id"),c.get("rentalRequestId")),
            cb.equal(r.get("status"),RentalRequestStatus.ACCEPTED),cb.isTrue(r.get("active")),
            cb.equal(r.get("renterId"),c.get("tenantId")),cb.equal(r.get("ownerId"),c.get("landlordId")));
        var confirmed=query.subquery(Integer.class);
        var p=confirmed.from(PaymentRequest.class);
        confirmed.select(cb.literal(1)).where(cb.equal(p.get("rentalRequestId"),c.get("rentalRequestId")),
            cb.equal(p.get("status"),PaymentStatus.CONFIRMED),
            p.get("type").in(PaymentType.RESERVATION_DEPOSIT,PaymentType.INITIAL),
            cb.equal(p.get("payerId"),c.get("tenantId")),cb.equal(p.get("payeeId"),c.get("landlordId")));
        return cb.or(cb.notEqual(c.get("status"),ContractStatus.DRAFT),
            cb.and(cb.isNull(c.get("parentContractId")),cb.exists(accepted),cb.exists(confirmed)));
    }
}
