package com.hs.contract.deposit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface DepositAgreementRepository extends JpaRepository<DepositAgreement,String> {
    Optional<DepositAgreement> findByRentalRequestId(String id);
    Optional<DepositAgreement> findBySigningContractId(String id);
}
