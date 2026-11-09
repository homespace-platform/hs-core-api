package com.hs.contract.repository;

import com.hs.contract.model.ContractDocument;
import com.hs.contract.model.constant.ContractDocumentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContractDocumentRepository extends JpaRepository<ContractDocument, String> {

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update ContractDocument d set d.integrityStatus = :status, d.integrityCheckedAt = :checkedAt, d.integrityMessage = :message where d.id = :documentId")
    int updateIntegrityResult(@org.springframework.data.repository.query.Param("documentId") String documentId,
            @org.springframework.data.repository.query.Param("status") String status,
            @org.springframework.data.repository.query.Param("checkedAt") java.time.Instant checkedAt,
            @org.springframework.data.repository.query.Param("message") String message);

    boolean existsByStorageObjectIdAndPurposeAndDocumentType(String storageObjectId,
            com.hs.contract.model.constant.DocumentPurpose purpose, ContractDocumentType documentType);

    List<ContractDocument> findByContractIdOrderByGeneratedAtDesc(String contractId);

    List<ContractDocument> findByContractIdAndRevisionId(String contractId, String revisionId);

    Optional<ContractDocument> findFirstByContractIdAndRevisionIdAndDocumentTypeOrderByGeneratedAtDesc(
            String contractId,
            String revisionId,
            ContractDocumentType documentType
    );
}
