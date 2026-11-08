package com.hs.contract.blockchain.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ContractDocumentProofRepository extends JpaRepository<ContractDocumentProof, String> {
    List<ContractDocumentProof> findByStatusOrderByCreatedAtAsc(ProofStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ContractDocumentProof p where p.documentId = :id")
    Optional<ContractDocumentProof> findByIdForUpdate(@Param("id") String id);

    @Query("""
            select p from ContractDocumentProof p
            where p.status in (com.hs.contract.blockchain.persistence.ProofStatus.PENDING,
                               com.hs.contract.blockchain.persistence.ProofStatus.SUBMITTED)
              and p.nextAttemptAt <= :now
            order by p.createdAt, p.documentId
            """)
    List<ContractDocumentProof> findDue(@Param("now") Instant now, Pageable pageable);
}
