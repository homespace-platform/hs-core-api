package com.hs.listing.repository;
import com.hs.listing.model.ListingAddress;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ListingAddressRepository extends JpaRepository<ListingAddress,String> {
    Optional<ListingAddress> findByListingId(String listingId);
    Optional<ListingAddress> findByListingIdAndActiveTrue(String listingId);
    List<ListingAddress> findAllByListingIdInAndActiveTrue(Collection<String> listingIds);
}
