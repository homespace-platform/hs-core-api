package com.hs.listing.repository;

import com.hs.listing.model.ListingStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ListingStatusHistoryRepository extends JpaRepository<ListingStatusHistory, String> {
    List<ListingStatusHistory> findAllByListingIdOrderByCreatedAtAsc(String listingId);
}
