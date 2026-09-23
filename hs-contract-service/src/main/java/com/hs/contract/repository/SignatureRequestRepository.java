package com.hs.contract.repository;

import com.hs.contract.model.SignatureRequest;
import com.hs.contract.model.constant.SignatureRequestStatus;
import com.hs.contract.model.constant.SignerRole;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SignatureRequestRepository extends JpaRepository<SignatureRequest, String> {

    List<SignatureRequest> findByContractIdOrderByCreatedAtDesc(String contractId);

    Optional<SignatureRequest> findFirstByContractIdAndSignerRoleOrderByCreatedAtDesc(
            String contractId, SignerRole signerRole);

    /** Tìm request active (chưa terminal) của một bên ký trong một hợp đồng. */
    Optional<SignatureRequest> findFirstByContractIdAndSignerRoleAndStatusIn(
            String contractId,
            SignerRole signerRole,
            Collection<SignatureRequestStatus> statuses
    );

    Optional<SignatureRequest> findByProviderTranCode(String providerTranCode);

    Optional<SignatureRequest> findByDocId(String docId);

    List<SignatureRequest> findByStatus(SignatureRequestStatus status);

    List<SignatureRequest> findByStatusAndExpiresAtBefore(SignatureRequestStatus status, Instant threshold);

    /** Tìm PROVIDER_SIGNED chưa bị claim quá nhiều lần. */
    @Query("SELECT r FROM SignatureRequest r " +
           "WHERE r.status = com.hs.contract.model.constant.SignatureRequestStatus.PROVIDER_SIGNED " +
           "AND r.processingAttempts < :maxAttempts " +
           "ORDER BY r.createdAt ASC")
    List<SignatureRequest> findPendingEmbeddingRequests(@Param("maxAttempts") int maxAttempts);

    /**
     * Atomic claim: chuyển PROVIDER_SIGNED → EMBEDDING.
     * Trả về số row updated (1 nếu thành công, 0 nếu bị race condition).
     */
    @Modifying
    @Transactional
    @Query("UPDATE SignatureRequest r " +
           "SET r.status = com.hs.contract.model.constant.SignatureRequestStatus.EMBEDDING, " +
           "    r.processingStartedAt = :now, " +
           "    r.processingAttempts = r.processingAttempts + 1 " +
           "WHERE r.id = :id " +
           "AND r.status = com.hs.contract.model.constant.SignatureRequestStatus.PROVIDER_SIGNED")
    int claimForEmbedding(@Param("id") String id, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("UPDATE SignatureRequest r SET r.status = com.hs.contract.model.constant.SignatureRequestStatus.PROVIDER_SIGNED " +
           "WHERE r.status = com.hs.contract.model.constant.SignatureRequestStatus.EMBEDDING " +
           "AND r.processingStartedAt < :before AND r.processingAttempts < :maxAttempts")
    int recoverStaleEmbedding(@Param("before") Instant before, @Param("maxAttempts") int maxAttempts);

    @Modifying
    @Transactional
    @Query("UPDATE SignatureRequest r SET r.status = com.hs.contract.model.constant.SignatureRequestStatus.FAILED, " +
           "r.failureCode = 'EMBEDDING_STUCK', r.failureMessage = 'Cần đối soát chữ ký với VNPT trước khi xử lý tiếp' " +
           "WHERE r.status = com.hs.contract.model.constant.SignatureRequestStatus.EMBEDDING " +
           "AND r.processingStartedAt < :before AND r.processingAttempts >= :maxAttempts")
    int failStaleEmbedding(@Param("before") Instant before, @Param("maxAttempts") int maxAttempts);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM SignatureRequest r WHERE r.id = :id")
    Optional<SignatureRequest> findByIdForUpdate(@Param("id") String id);
}
