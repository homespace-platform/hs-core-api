package com.hs.contract.repository;
import com.hs.contract.model.ContractClosure;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface ContractClosureRepository extends JpaRepository<ContractClosure,String> {
    Optional<ContractClosure> findByRefundPaymentId(String refundPaymentId);
    Optional<ContractClosure> findByBalancePaymentId(String balancePaymentId);
}
