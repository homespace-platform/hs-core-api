package com.hs.contract.repository;

import com.hs.contract.model.ContractDocument;
import com.hs.contract.model.constant.ContractDocumentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContractDocumentRepository extends JpaRepository<ContractDocument, String> {

    List<ContractDocument> findByContractIdOrderByGeneratedAtDesc(String contractId);

    List<ContractDocument> findByContractIdAndRevisionId(String contractId, String revisionId);

    Optional<ContractDocument> findFirstByContractIdAndRevisionIdAndDocumentTypeOrderByGeneratedAtDesc(
            String contractId,
            String revisionId,
            ContractDocumentType documentType
    );
}
