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
}
