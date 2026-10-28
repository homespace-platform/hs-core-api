package com.hs.listing.nearby;

import com.hs.listing.nearby.model.NearbyCategory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NearbyCategoryRepository extends JpaRepository<NearbyCategory, String> {}
