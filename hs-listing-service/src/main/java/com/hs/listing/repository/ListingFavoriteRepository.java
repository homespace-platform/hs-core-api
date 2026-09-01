package com.hs.listing.repository;

import com.hs.listing.model.ListingFavorite;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ListingFavoriteRepository extends JpaRepository<ListingFavorite, String> {

    Optional<ListingFavorite> findByUserIdAndListing_Id(String userId, String listingId);

    boolean existsByUserIdAndListing_Id(String userId, String listingId);

    void deleteByUserIdAndListing_Id(String userId, String listingId);

    @Query("SELECT lf FROM ListingFavorite lf JOIN FETCH lf.listing l WHERE lf.userId = :userId AND lf.active = true AND l.active = true ORDER BY lf.createdAt DESC")
    Page<ListingFavorite> findAllByUserIdWithListing(@Param("userId") String userId, Pageable pageable);

    @Query("SELECT lf.listing.id FROM ListingFavorite lf WHERE lf.userId = :userId AND lf.active = true")
    List<String> findFavoriteListingIdsByUserId(@Param("userId") String userId);
}
