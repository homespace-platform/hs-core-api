package com.hs.contract.repository;
import com.hs.contract.model.ContractRenewal;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ContractRenewalRepository extends JpaRepository<ContractRenewal,String> {
    List<ContractRenewal> findByContractIdOrderByRequestedAtDesc(String contractId);
    Optional<ContractRenewal> findByAgreementContractId(String agreementContractId);
}
