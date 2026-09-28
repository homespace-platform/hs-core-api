package com.hs.contract.repository;

import com.hs.contract.model.ContractTemplate;
import com.hs.contract.model.constant.ContractTemplateStatus;
import com.hs.listing.model.constant.ListingCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ContractTemplateRepository extends JpaRepository<ContractTemplate, String>, JpaSpecificationExecutor<ContractTemplate> {

    List<ContractTemplate> findByStatusOrderByCreatedAtDesc(ContractTemplateStatus status);

    @Query("SELECT t FROM ContractTemplate t WHERE t.category = :category " +
           "AND t.status = com.hs.contract.model.constant.ContractTemplateStatus.ACTIVE " +
           "AND t.active = true AND (t.source IS NULL OR " +
           "t.source = com.hs.contract.model.constant.ContractTemplateSource.SYSTEM)")
    List<ContractTemplate> findActiveSystemTemplates(@Param("category") ListingCategory category);

    @Query("SELECT t FROM ContractTemplate t WHERE t.category = :category " +
           "AND (t.source IS NULL OR t.source = com.hs.contract.model.constant.ContractTemplateSource.SYSTEM)")
    List<ContractTemplate> findSystemTemplatesByCategory(@Param("category") ListingCategory category);

    /** Mẫu hệ thống đã xuất bản (dùng chung), lọc theo loại hình nếu có. */
    @Query("SELECT t FROM ContractTemplate t WHERE t.status = 'ACTIVE' " +
           "AND (t.source IS NULL OR t.source = com.hs.contract.model.constant.ContractTemplateSource.SYSTEM) " +
           "AND t.latestPublishedVersionId IS NOT NULL " +
           "AND t.active = true " +
           "AND (:category IS NULL OR t.category = :category) " +
           "ORDER BY t.name ASC")
    List<ContractTemplate> findPublishedSystemTemplates(@Param("category") ListingCategory category);

}
