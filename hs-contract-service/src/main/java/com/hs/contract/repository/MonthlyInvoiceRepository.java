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
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from MonthlyInvoice i where i.id = :id")
    Optional<MonthlyInvoice> findByIdForUpdate(@Param("id") String id);
    List<MonthlyInvoice> findByStatusAndDueAtBefore(MonthlyInvoiceStatus status, Instant now);
}
