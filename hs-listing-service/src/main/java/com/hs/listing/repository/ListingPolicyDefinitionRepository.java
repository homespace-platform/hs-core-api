package com.hs.listing.repository;
import com.hs.listing.model.ListingPolicyDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface ListingPolicyDefinitionRepository extends JpaRepository<ListingPolicyDefinition,String> {
    List<ListingPolicyDefinition> findAllByActiveTrueOrderBySortOrderAsc();
}
