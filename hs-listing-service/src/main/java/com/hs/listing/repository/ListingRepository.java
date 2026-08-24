package com.hs.listing.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hs.listing.model.Listing;

public interface ListingRepository extends JpaRepository<Listing, String> {
}
