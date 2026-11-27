package com.hs.contract.repository;

import com.hs.contract.model.MonthlyInvoice;
import com.hs.contract.model.MonthlyInvoiceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MonthlyInvoiceRepository extends JpaRepository<MonthlyInvoice, String> {
    List<MonthlyInvoice> findByContractIdOrderByPeriodIndexDesc(String contractId);
    Optional<MonthlyInvoice> findByContractIdAndPeriodIndex(String contractId, int periodIndex);
    /** Light read-only rows of every period, cancelled ones included (they block projecting the period again). */
    @Query("select new com.hs.contract.dto.branch.BranchInvoiceRow(i.id, i.contractId, i.periodIndex, "
            + "i.periodEndExclusive, i.status, i.dueAt, i.resolutionMode, i.paymentRequestId, i.periodStart, "
            + "i.costTermsRevisionId, i.finalCostSupplement, "
            + "case when i.draftExtraChargesSnapshot is not null then true else false end) "
            + "from MonthlyInvoice i where i.contractId in :contractIds")
    List<com.hs.contract.dto.branch.BranchInvoiceRow> findBranchRows(
            @Param("contractIds") java.util.Collection<String> contractIds);
    // Scalar lookup avoids caching a stale invoice before waiting for the contract lock.
    @Query("select i.contractId from MonthlyInvoice i where i.id = :id")
    Optional<String> findContractIdByInvoiceId(@Param("id") String id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from MonthlyInvoice i where i.id = :id")
    Optional<MonthlyInvoice> findByIdForUpdate(@Param("id") String id);
}
