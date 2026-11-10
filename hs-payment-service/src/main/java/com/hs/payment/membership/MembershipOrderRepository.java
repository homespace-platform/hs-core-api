package com.hs.payment.membership;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MembershipOrderRepository extends JpaRepository<MembershipOrder, String> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT o FROM MembershipOrder o WHERE o.id = :id")
    Optional<MembershipOrder> lockById(@org.springframework.data.repository.query.Param("id") String id);

    boolean existsByOwnerIdAndStatusAndExpiresAtAfter(String ownerId, MembershipOrder.Status status, Instant now);
    List<MembershipOrder> findByOwnerIdAndStatus(String ownerId, MembershipOrder.Status status);
}
