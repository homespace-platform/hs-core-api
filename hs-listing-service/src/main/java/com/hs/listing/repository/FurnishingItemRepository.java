package com.hs.listing.repository;
import com.hs.listing.model.FurnishingItem; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface FurnishingItemRepository extends JpaRepository<FurnishingItem,String> {
 List<FurnishingItem> findAllByCodeInAndActiveTrue(Collection<String> codes); List<FurnishingItem> findAllByActiveTrue(); boolean existsByCode(String code); List<FurnishingItem> findAllByActiveTrueOrderBySortOrderAscCodeAsc();
}
