package com.hs.listing.repository;

import com.hs.listing.model.ListingViewHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ListingViewHistoryRepository extends JpaRepository<ListingViewHistory, String> {

    Optional<ListingViewHistory> findByUserIdAndListing_Id(String userId, String listingId);

    void deleteByUserIdAndListing_Id(String userId, String listingId);

    void deleteAllByUserId(String userId);

    long countByUserIdAndActiveTrue(String userId);

    @Query("SELECT vh FROM ListingViewHistory vh JOIN FETCH vh.listing l WHERE vh.userId = :userId AND vh.active = true AND l.active = true AND l.status = com.hs.listing.model.constant.ListingStatus.PUBLISHED ORDER BY vh.viewedAt DESC")
    Page<ListingViewHistory> findAllByUserIdWithListing(@Param("userId") String userId, Pageable pageable);

    @Query("SELECT COUNT(vh) FROM ListingViewHistory vh JOIN vh.listing l WHERE vh.userId = :userId AND vh.active = true AND l.active = true AND l.status = com.hs.listing.model.constant.ListingStatus.PUBLISHED")
    long countActiveHistoryByUserId(@Param("userId") String userId);

    @Query("SELECT vh.listing.id FROM ListingViewHistory vh JOIN vh.listing l WHERE vh.userId = :userId AND vh.active = true AND l.active = true AND l.status = com.hs.listing.model.constant.ListingStatus.PUBLISHED ORDER BY vh.viewedAt DESC")
    List<String> findListingIdsByUserId(@Param("userId") String userId, Pageable pageable);

    @Query("SELECT vh.id FROM ListingViewHistory vh WHERE vh.userId = :userId AND vh.active = true ORDER BY vh.viewedAt DESC")
    List<String> findTopIdsByUserId(@Param("userId") String userId, Pageable pageable);

    @Modifying
    @Query("DELETE FROM ListingViewHistory vh WHERE vh.userId = :userId AND vh.id NOT IN :retainedIds")
    void deleteByUserIdAndIdNotIn(@Param("userId") String userId, @Param("retainedIds") List<String> retainedIds);

    @Modifying
    @Query("DELETE FROM ListingViewHistory vh WHERE vh.viewedAt < :cutoff")
    int deleteByViewedAtBefore(@Param("cutoff") Instant cutoff);
}
