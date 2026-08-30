package com.hs.listing.repository;
import com.hs.listing.model.Amenity; import com.hs.listing.model.constant.ListingCategory; import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param; import java.util.*;
public interface AmenityRepository extends JpaRepository<Amenity,String> {
 List<Amenity> findAllByCodeInAndActiveTrue(Collection<String> codes); List<Amenity> findAllByActiveTrue(); boolean existsByCode(String code);
 @Query("select distinct a from Amenity a join a.categories c where c = :category and a.active = true order by a.sortOrder, a.code") List<Amenity> findPublicByCategory(@Param("category") ListingCategory category);
}
