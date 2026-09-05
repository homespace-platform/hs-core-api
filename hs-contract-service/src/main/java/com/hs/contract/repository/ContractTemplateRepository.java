package com.hs.contract.repository;

import com.hs.contract.model.ContractTemplate;
import com.hs.contract.model.constant.ContractTemplateStatus;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.RentalMode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ContractTemplateRepository extends JpaRepository<ContractTemplate, String>, JpaSpecificationExecutor<ContractTemplate> {

    List<ContractTemplate> findByStatusOrderByCreatedAtDesc(ContractTemplateStatus status);

    @Query("SELECT t FROM ContractTemplate t WHERE t.status = 'ACTIVE' " +
           "AND t.latestPublishedVersionId IS NOT NULL " +
           "AND (:category IS NULL OR t.category IS NULL OR t.category = :category) " +
           "AND (:rentalMode IS NULL OR t.rentalMode IS NULL OR t.rentalMode = :rentalMode) " +
           "ORDER BY t.name ASC")
    List<ContractTemplate> findApplicablePublishedTemplates(
            @Param("category") ListingCategory category,
            @Param("rentalMode") RentalMode rentalMode
    );
}
