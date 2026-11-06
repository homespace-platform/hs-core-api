package com.hs.listing.repository;

import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.RentalRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RentalRequestRepository extends JpaRepository<RentalRequest, String>, JpaSpecificationExecutor<RentalRequest> {

    // Read only the FK before locking the parent; do not cache a stale Listing entity.
    @org.springframework.data.jpa.repository.Query("SELECT r.listing.id FROM RentalRequest r WHERE r.id = :id AND r.active = true")
    Optional<String> findListingIdByRequestId(@org.springframework.data.repository.query.Param("id") String id);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            UPDATE RentalRequest r
            SET r.status = :rejected, r.rejectReason = :reason, r.updatedAt = :at, r.updatedBy = :actorId
            WHERE r.listing.id = :listingId AND r.id <> :acceptedId
              AND r.status = :pending AND r.active = true
            """)
    int rejectCompetingPendingRequests(
            @org.springframework.data.repository.query.Param("listingId") String listingId,
            @org.springframework.data.repository.query.Param("acceptedId") String acceptedId,
            @org.springframework.data.repository.query.Param("pending") RentalRequestStatus pending,
            @org.springframework.data.repository.query.Param("rejected") RentalRequestStatus rejected,
            @org.springframework.data.repository.query.Param("reason") String reason,
            @org.springframework.data.repository.query.Param("at") Instant at,
            @org.springframework.data.repository.query.Param("actorId") String actorId);

    List<RentalRequest> findByListingIdAndStatus(String listingId, RentalRequestStatus status);

    org.springframework.data.domain.Page<RentalRequest> findByStatus(RentalRequestStatus status, org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query("SELECT r FROM RentalRequest r WHERE r.status = :status AND r.active = true AND r.id > :after ORDER BY r.id")
    List<RentalRequest> findNextOnboardingBatch(@org.springframework.data.repository.query.Param("status") RentalRequestStatus status,
            @org.springframework.data.repository.query.Param("after") String after,org.springframework.data.domain.Pageable pageable);

    List<RentalRequest> findByStatusAndRespondByLessThanEqual(RentalRequestStatus status, Instant now);

    List<RentalRequest> findByListingIdAndStatusIn(String listingId, Collection<RentalRequestStatus> statuses);

    Optional<RentalRequest> findFirstByListingIdAndRenterIdAndStatusIn(
            String listingId, String renterId, Collection<RentalRequestStatus> statuses);

    List<RentalRequest> findAllByStatusAndHoldExpiresAtLessThanEqual(RentalRequestStatus status, Instant now);

    List<RentalRequest> findAllByStatusAndHoldExpiresAtIsNotNull(RentalRequestStatus status);

    long countByListingIdAndStatusIn(String listingId, Collection<RentalRequestStatus> statuses);

    boolean existsByListingIdAndRenterId(String listingId, String renterId);

    @org.springframework.data.jpa.repository.Query("SELECT r FROM RentalRequest r JOIN FETCH r.listing WHERE r.ownerId = :ownerId AND r.status = :status AND r.active = true ORDER BY r.createdAt ASC")
    List<RentalRequest> findActiveByOwnerAndStatus(@org.springframework.data.repository.query.Param("ownerId") String ownerId,
            @org.springframework.data.repository.query.Param("status") RentalRequestStatus status);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT r FROM RentalRequest r WHERE r.id = :id")
    Optional<RentalRequest> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") String id);
}
