package com.hs.listing.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hs.listing.model.Listing;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.ListingStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
public interface ListingRepository extends JpaRepository<Listing, String>, JpaSpecificationExecutor<Listing> {
    Page<Listing> findAllByOwnerIdAndActiveTrue(String ownerId, Pageable pageable);
    Optional<Listing> findByIdAndActiveTrue(String id);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT l FROM Listing l WHERE l.id = :id AND l.active = true")
    Optional<Listing> findByIdForAppointmentCreation(@org.springframework.data.repository.query.Param("id") String id);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    List<Listing> findAllByStatusAndActiveTrueAndExpiresAtLessThanEqual(ListingStatus status, Instant expiresAt);
    long countByOwnerIdAndStatusAndActiveTrue(String ownerId, ListingStatus status);
    @org.springframework.data.jpa.repository.Query("SELECT COUNT(l) FROM Listing l WHERE l.branchId = :branchId AND l.active = true")
    long countByBranchIdAndActiveTrue(@org.springframework.data.repository.query.Param("branchId") String branchId);

    List<Listing> findAllByBranchIdAndActiveTrue(String branchId);

    @org.springframework.data.jpa.repository.Query("SELECT l.status, COUNT(l) FROM Listing l WHERE l.ownerId = :ownerId AND l.active = true GROUP BY l.status")
    List<Object[]> countByOwnerGroupedByStatus(@org.springframework.data.repository.query.Param("ownerId") String ownerId);

    @org.springframework.data.jpa.repository.Query("SELECT l.status, COUNT(l) FROM Listing l WHERE l.active = true GROUP BY l.status")
    List<Object[]> countAllGroupedByStatus();

    long countByActiveTrueAndPendingEditIdIsNotNull();

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("""
            SELECT l FROM Listing l WHERE l.active = true AND l.status = com.hs.listing.model.constant.ListingStatus.PENDING_REVIEW
            AND l.reviewOperationalStatus = com.hs.listing.model.constant.ListingStatus.PUBLISHED AND l.expiresAt <= :at
            """)
    List<Listing> findPendingReviewsPastPublicationExpiry(@org.springframework.data.repository.query.Param("at") Instant at);

    /** Includes signed/expired leases until the parties confirm vacant possession, even if listing status was changed. */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT EXISTS (SELECT 1 FROM rental_requests r WHERE r.listing_id = :id AND r.active = true AND r.status = 'ACCEPTED')
            OR EXISTS (SELECT 1 FROM contracts c LEFT JOIN contract_closures s ON s.contract_id = c.id
              WHERE c.listing_id = :id AND c.active = true AND c.parent_contract_id IS NULL
                AND (c.agreement_kind IS NULL OR c.agreement_kind <> 'DEPOSIT')
                AND c.status NOT IN ('CANCELLED','CLOSED') AND s.accepted_at IS NULL)
            """, nativeQuery = true)
    boolean hasOccupiedRental(@org.springframework.data.repository.query.Param("id") String id);

    /** Published counts for the fixed home metros; optional category filter. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT a.cityId, COUNT(l)
            FROM Listing l, ListingAddress a
            WHERE a.listingId = l.id
              AND a.active = true
              AND l.active = true
              AND l.status = com.hs.listing.model.constant.ListingStatus.PUBLISHED
              AND a.cityId IN :cityIds
              AND (:category IS NULL OR l.category = :category)
            GROUP BY a.cityId
            """)
    List<Object[]> countPublishedByCityIds(
            @org.springframework.data.repository.query.Param("cityIds") Collection<String> cityIds,
            @org.springframework.data.repository.query.Param("category") ListingCategory category);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE Listing l SET l.viewCount = COALESCE(l.viewCount, 0) + 1 WHERE l.id = :id")
    void incrementViewCount(@org.springframework.data.repository.query.Param("id") String id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT l FROM Listing l WHERE l.id = :id AND l.active = true")
    Optional<Listing> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") String id);
}
