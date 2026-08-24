package com.hs.listing.repository;

import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.hs.listing.model.Listing;
import com.hs.listing.model.constant.ListingStatus;

public interface ListingRepository extends JpaRepository<Listing, String> {
    Page<Listing> findAllByStatusIn(Collection<ListingStatus> statuses, Pageable pageable);
}
