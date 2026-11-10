package com.hs.listing.repository;

import com.hs.listing.model.ListingStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ListingStatusHistoryRepository extends JpaRepository<ListingStatusHistory, String> {
    List<ListingStatusHistory> findAllByListingIdOrderByCreatedAtAsc(String listingId);

    @org.springframework.data.jpa.repository.Query("""
            SELECT COUNT(h) FROM ListingStatusHistory h, Listing l
            WHERE h.listingId = l.id AND l.ownerId = :ownerId
              AND h.toStatus = com.hs.listing.model.constant.ListingStatus.PUBLISHED
              AND h.fromStatus IN (com.hs.listing.model.constant.ListingStatus.PENDING_REVIEW,
                  com.hs.listing.model.constant.ListingStatus.EXPIRED,
                  com.hs.listing.model.constant.ListingStatus.RENTED)
              AND (h.reason IS NULL OR h.reason NOT LIKE 'Đã duyệt nội dung %')
            """)
    long countLegacyPublications(@org.springframework.data.repository.query.Param("ownerId") String ownerId);
}
