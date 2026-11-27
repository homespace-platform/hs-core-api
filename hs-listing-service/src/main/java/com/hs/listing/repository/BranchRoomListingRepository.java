package com.hs.listing.repository;

import com.hs.listing.dto.response.BranchListingRow;
import com.hs.listing.model.BranchRoomListing;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BranchRoomListingRepository extends JpaRepository<BranchRoomListing, String> {

    @Query("select k from BranchRoomListing k, BranchSlot s where s.id = k.roomId and s.branch.id = :branchId")
    List<BranchRoomListing> findAllInBranch(@Param("branchId") String branchId);

    @Query("select s.branch.id from BranchRoomListing k, BranchSlot s where s.id = k.roomId and k.listingId = :listingId")
    Optional<String> findLinkedBranchId(@Param("listingId") String listingId);

    @Modifying(flushAutomatically = true)
    @Query("delete from BranchRoomListing k where k.listingId = :listingId")
    void deleteLink(@Param("listingId") String listingId);

    @Modifying(flushAutomatically = true)
    @Query("delete from BranchRoomListing k where k.roomId in :roomIds")
    void deleteByRoomIds(@Param("roomIds") Collection<String> roomIds);

    @Query("select new com.hs.listing.dto.response.ListingBranchRef(l.id, l.branchId) from Listing l "
            + "where l.id = :id and l.ownerId = :ownerId and l.active = true")
    Optional<com.hs.listing.dto.response.ListingBranchRef> findOwnListing(@Param("id") String listingId,
                                                                          @Param("ownerId") String ownerId);

    /** Live branch of an active listing of this owner; empty when it has no branch. */
    @Query("select l.branchId from Listing l where l.id = :id and l.ownerId = :ownerId and l.active = true")
    Optional<String> findActiveListingBranchId(@Param("id") String listingId, @Param("ownerId") String ownerId);

    @Query("select new com.hs.listing.dto.response.BranchListingRow(l.id, l.title, l.status, l.reviewOperationalStatus) "
            + "from Listing l where l.branchId = :branchId and l.ownerId = :ownerId and l.active = true order by l.id")
    List<BranchListingRow> findBranchListings(@Param("branchId") String branchId, @Param("ownerId") String ownerId);

    @Query("select new com.hs.listing.dto.response.BranchOverviewListingRow(l.id, l.status, "
            + "l.reviewOperationalStatus, l.expiresAt) from Listing l "
            + "where l.branchId = :branchId and l.ownerId = :ownerId and l.active = true")
    List<com.hs.listing.dto.response.BranchOverviewListingRow> findOverviewListings(
            @Param("branchId") String branchId, @Param("ownerId") String ownerId);

    @Query(value = "select new com.hs.listing.dto.response.BranchListingRow(l.id, l.title, l.status, l.reviewOperationalStatus) "
            + "from Listing l where l.branchId = :branchId and l.ownerId = :ownerId and l.active = true order by l.id",
            countQuery = "select count(l) from Listing l where l.branchId = :branchId and l.ownerId = :ownerId and l.active = true")
    Page<BranchListingRow> findBranchListingPage(@Param("branchId") String branchId, @Param("ownerId") String ownerId,
                                                 Pageable pageable);
}
