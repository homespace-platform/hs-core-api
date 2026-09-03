package com.hs.listing.repository;

import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.RentalRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RentalRequestRepository extends JpaRepository<RentalRequest, String>, JpaSpecificationExecutor<RentalRequest> {

    List<RentalRequest> findByListingIdAndStatus(String listingId, RentalRequestStatus status);

    List<RentalRequest> findByListingIdAndStatusIn(String listingId, Collection<RentalRequestStatus> statuses);

    Optional<RentalRequest> findFirstByListingIdAndRenterIdAndStatusIn(
            String listingId, String renterId, Collection<RentalRequestStatus> statuses);

    List<RentalRequest> findAllByStatusAndHoldExpiresAtLessThanEqual(RentalRequestStatus status, Instant now);

    long countByListingIdAndStatusIn(String listingId, Collection<RentalRequestStatus> statuses);

    boolean existsByListingIdAndRenterId(String listingId, String renterId);
}
