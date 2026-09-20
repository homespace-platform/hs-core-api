package com.hs.payment.repository;

import com.hs.payment.model.BankAccount;
import com.hs.payment.model.constant.BankAccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BankAccountRepository extends JpaRepository<BankAccount, String> {

    List<BankAccount> findByUserIdAndActiveTrue(String userId);

    Optional<BankAccount> findByIdAndUserIdAndActiveTrue(String id, String userId);

    Optional<BankAccount> findByUserIdAndDefaultForIncomingPaymentsTrueAndStatusAndActiveTrue(
            String userId, BankAccountStatus status);

    Optional<BankAccount> findByUserIdAndDefaultForRefundsTrueAndStatusAndActiveTrue(
            String userId, BankAccountStatus status);

    boolean existsByUserIdAndStatusAndActiveTrue(String userId, BankAccountStatus status);

    boolean existsByUserIdAndActiveTrue(String userId);
}
