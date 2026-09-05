package com.hs.contract.repository;

import com.hs.contract.model.ContractRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContractRevisionRepository extends JpaRepository<ContractRevision, String> {

    List<ContractRevision> findByContractIdOrderByRevisionNumberDesc(String contractId);

    Optional<ContractRevision> findByContractIdAndRevisionNumber(String contractId, Integer revisionNumber);

    @Query("SELECT COALESCE(MAX(r.revisionNumber), 0) FROM ContractRevision r WHERE r.contract.id = :contractId")
    Integer findMaxRevisionNumberByContractId(@Param("contractId") String contractId);
}
