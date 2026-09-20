package com.hs.payment.repository;

import com.hs.payment.model.DepositRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DepositRecordRepository extends JpaRepository<DepositRecord, String> {

    Optional<DepositRecord> findByRentalRequestId(String rentalRequestId);

    Optional<DepositRecord> findByInitialPaymentRequestId(String initialPaymentRequestId);

    Optional<DepositRecord> findByContractId(String contractId);
}
