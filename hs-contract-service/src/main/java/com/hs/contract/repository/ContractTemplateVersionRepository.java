package com.hs.contract.repository;

import com.hs.contract.model.ContractTemplateVersion;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.contract.model.constant.TemplateVersionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContractTemplateVersionRepository extends JpaRepository<ContractTemplateVersion, String> {

    List<ContractTemplateVersion> findByTemplateIdOrderByVersionNumberDesc(String templateId);

    Optional<ContractTemplateVersion> findByTemplateIdAndVersionNumber(String templateId, Integer versionNumber);

    Optional<ContractTemplateVersion> findByTemplateIdAndStatus(String templateId, TemplateVersionStatus status);

    @Query("SELECT COALESCE(MAX(v.versionNumber), 0) FROM ContractTemplateVersion v WHERE v.template.id = :templateId")
    Integer findMaxVersionNumberByTemplateId(@Param("templateId") String templateId);

    @Query("SELECT v FROM ContractTemplateVersion v JOIN v.template t WHERE " +
           "(t.source IS NULL OR t.source = com.hs.contract.model.constant.ContractTemplateSource.SYSTEM) " +
           "AND t.status = com.hs.contract.model.constant.ContractTemplateStatus.ACTIVE " +
           "AND t.active = true AND t.category = :category " +
           "AND t.latestPublishedVersionId = v.id " +
           "AND v.status = com.hs.contract.model.constant.TemplateVersionStatus.PUBLISHED")
    List<ContractTemplateVersion> findCurrentPublishedSystemVersions(@Param("category") ListingCategory category);

    @Query("SELECT COUNT(t) FROM ContractTemplate t WHERE " +
           "(t.source IS NULL OR t.source = com.hs.contract.model.constant.ContractTemplateSource.SYSTEM) " +
           "AND t.status = com.hs.contract.model.constant.ContractTemplateStatus.ACTIVE " +
           "AND t.active = true AND t.category = :category")
    long countActiveSystemTemplates(@Param("category") ListingCategory category);
}
