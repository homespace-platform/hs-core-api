package com.hs.contract.repository;

import com.hs.contract.model.ContractAmendment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface ContractAmendmentRepository extends JpaRepository<ContractAmendment, String> {
    List<ContractAmendment> findByContractIdOrderByCreatedAtDesc(String contractId);
    Optional<ContractAmendment> findByAgreementContractId(String agreementContractId);
    List<ContractAmendment> findByStatusIn(Collection<String> statuses);
}
