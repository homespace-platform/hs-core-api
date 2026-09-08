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

    /** Mẫu hệ thống đã xuất bản (dùng chung), lọc theo loại hình nếu có. */
    @Query("SELECT t FROM ContractTemplate t WHERE t.status = 'ACTIVE' " +
           "AND t.source = com.hs.contract.model.constant.ContractTemplateSource.SYSTEM " +
           "AND t.latestPublishedVersionId IS NOT NULL " +
           "AND t.active = true " +
           "AND (:category IS NULL OR t.category = :category) " +
           "ORDER BY t.name ASC")
    List<ContractTemplate> findPublishedSystemTemplates(@Param("category") ListingCategory category);

    /**
     * Mẫu áp dụng khi tạo hợp đồng từ yêu cầu thuê:
     * - mẫu hệ thống đã xuất bản khớp loại hình, hoặc
     * - mẫu của chủ nhà (ownerUserId) đã xuất bản khớp loại hình.
     */
    @Query("SELECT t FROM ContractTemplate t WHERE t.status = 'ACTIVE' " +
           "AND t.latestPublishedVersionId IS NOT NULL " +
           "AND t.active = true " +
           "AND (:category IS NULL OR t.category = :category) " +
           "AND (t.source = com.hs.contract.model.constant.ContractTemplateSource.SYSTEM " +
           "     OR (t.source = com.hs.contract.model.constant.ContractTemplateSource.LANDLORD AND t.ownerUserId = :ownerUserId)) " +
           "ORDER BY t.source ASC, t.name ASC")
    List<ContractTemplate> findApplicablePublishedTemplates(
            @Param("category") ListingCategory category,
            @Param("ownerUserId") String ownerUserId
    );
}
