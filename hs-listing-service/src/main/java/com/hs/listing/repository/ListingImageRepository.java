package com.hs.listing.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hs.listing.model.ListingImage;

public interface ListingImageRepository extends JpaRepository<ListingImage, String> {
    List<ListingImage> findAllByListingIdOrderBySortOrderAsc(String listingId);

    boolean existsByListingIdAndStorageId(String listingId, String storageId);

    @Modifying
    @Query("update ListingImage image set image.cover = false where image.listingId = :listingId")
    void clearCoverByListingId(@Param("listingId") String listingId);
}
