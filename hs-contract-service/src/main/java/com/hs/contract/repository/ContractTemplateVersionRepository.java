package com.hs.contract.repository;

import com.hs.contract.model.ContractTemplateVersion;
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
}
