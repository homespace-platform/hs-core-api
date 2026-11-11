package com.hs.payment.repository;

import com.hs.payment.model.EthWalletChallenge;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EthWalletChallengeRepository extends JpaRepository<EthWalletChallenge, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from EthWalletChallenge c where c.id = :id")
    Optional<EthWalletChallenge> findByIdForUpdate(@Param("id") String id);
}
