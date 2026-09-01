package com.hs.listing.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hs.listing.model.Listing;
import com.hs.listing.model.constant.ListingStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
public interface ListingRepository extends JpaRepository<Listing, String>, JpaSpecificationExecutor<Listing> {
    Page<Listing> findAllByOwnerIdAndActiveTrue(String ownerId, Pageable pageable);
    Optional<Listing> findByIdAndActiveTrue(String id);
    List<Listing> findAllByStatusAndActiveTrueAndExpiresAtLessThanEqual(ListingStatus status, Instant expiresAt);
    long countByOwnerIdAndStatusAndActiveTrue(String ownerId, ListingStatus status);

    @org.springframework.data.jpa.repository.Query("SELECT l.status, COUNT(l) FROM Listing l WHERE l.ownerId = :ownerId AND l.active = true GROUP BY l.status")
    List<Object[]> countByOwnerGroupedByStatus(@org.springframework.data.repository.query.Param("ownerId") String ownerId);

    @org.springframework.data.jpa.repository.Query("SELECT l.status, COUNT(l) FROM Listing l WHERE l.active = true GROUP BY l.status")
    List<Object[]> countAllGroupedByStatus();

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE Listing l SET l.viewCount = COALESCE(l.viewCount, 0) + 1 WHERE l.id = :id")
    void incrementViewCount(@org.springframework.data.repository.query.Param("id") String id);
}
