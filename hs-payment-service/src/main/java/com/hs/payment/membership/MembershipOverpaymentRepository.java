package com.hs.payment.membership;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface MembershipOverpaymentRepository extends JpaRepository<MembershipOverpayment, String> {
    boolean existsByProviderAndTransactionNo(MembershipOrder.Provider provider, String transactionNo);
    Page<MembershipOverpayment> findByStatus(MembershipOverpayment.Status status, Pageable page);
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM MembershipOverpayment p WHERE p.id = :id")
    Optional<MembershipOverpayment> lockById(@Param("id") String id);
}
