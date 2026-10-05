package com.hs.payment.repository;

import com.hs.payment.model.DepositRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface DepositRecordRepository extends JpaRepository<DepositRecord, String> {

    Optional<DepositRecord> findByRentalRequestId(String rentalRequestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DepositRecord d where d.rentalRequestId = :rentalRequestId")
    Optional<DepositRecord> findByRentalRequestIdForUpdate(@Param("rentalRequestId") String rentalRequestId);

    Optional<DepositRecord> findByInitialPaymentRequestId(String initialPaymentRequestId);

    Optional<DepositRecord> findByContractId(String contractId);
}
