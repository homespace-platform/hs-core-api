package com.hs.contract.repository;

import com.hs.contract.model.Contract;
import com.hs.contract.model.constant.ContractStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContractRepository extends JpaRepository<Contract, String>, JpaSpecificationExecutor<Contract> {

    Optional<Contract> findByRentalRequestId(String rentalRequestId);

    Optional<Contract> findByContractNumber(String contractNumber);

    List<Contract> findByLandlordIdOrderByCreatedAtDesc(String landlordId);

    List<Contract> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    @Query("SELECT c FROM Contract c WHERE (c.landlordId = :userId OR c.tenantId = :userId) ORDER BY c.createdAt DESC")
    List<Contract> findByUserInvolved(@Param("userId") String userId);

    @Query("SELECT c FROM Contract c WHERE (c.landlordId = :userId OR c.tenantId = :userId) AND c.status = :status ORDER BY c.createdAt DESC")
    List<Contract> findByUserInvolvedAndStatus(@Param("userId") String userId, @Param("status") ContractStatus status);

    List<Contract> findAllByOrderByCreatedAtDesc();

    List<Contract> findByStatusOrderByCreatedAtDesc(ContractStatus status);

    boolean existsByRentalRequestId(String rentalRequestId);
}
