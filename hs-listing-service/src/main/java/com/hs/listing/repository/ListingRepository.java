package com.hs.listing.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hs.listing.model.Listing;
import java.util.Optional;
public interface ListingRepository extends JpaRepository<Listing, String> {
    Page<Listing> findAllByOwnerIdAndActiveTrue(String ownerId, Pageable pageable);
    Optional<Listing> findByIdAndActiveTrue(String id);
}
