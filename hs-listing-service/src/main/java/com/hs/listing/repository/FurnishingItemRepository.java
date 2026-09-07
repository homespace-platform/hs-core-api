package com.hs.listing.repository;
import com.hs.listing.model.FurnishingItem; import com.hs.listing.model.constant.ListingCategory; import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param; import java.util.*;
public interface FurnishingItemRepository extends JpaRepository<FurnishingItem,String> {
 List<FurnishingItem> findAllByCodeInAndActiveTrue(Collection<String> codes); List<FurnishingItem> findAllByActiveTrue(); boolean existsByCode(String code); Optional<FurnishingItem> findByCode(String code); List<FurnishingItem> findAllByActiveTrueOrderBySortOrderAscCodeAsc();
 @Query("select distinct f from FurnishingItem f join f.categories c where c = :category and f.active = true order by f.sortOrder, f.code") List<FurnishingItem> findPublicByCategory(@Param("category") ListingCategory category);
}
