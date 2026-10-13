package com.hs.contract.repository;

import com.hs.contract.model.Contract;
import com.hs.contract.model.constant.ContractStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import jakarta.persistence.LockModeType;

@Repository
public interface ContractRepository extends JpaRepository<Contract, String>, JpaSpecificationExecutor<Contract> {

    @Query("select c from Contract c where c.rentalRequestId = :rentalRequestId and c.parentContractId is null")
    Optional<Contract> findByRentalRequestId(@Param("rentalRequestId") String rentalRequestId);

    Page<Contract> findByParentContractIdIsNullAndStatusIn(java.util.Collection<ContractStatus> statuses, Pageable pageable);

    @Query("select c.id from Contract c where c.parentContractId is null and c.status in :statuses "
            + "and (:afterId is null or c.id > :afterId) order by c.id")
    List<String> findLifecycleIdsAfter(@Param("statuses") java.util.Collection<ContractStatus> statuses,
                                      @Param("afterId") String afterId, Pageable pageable);

    Optional<Contract> findByContractNumber(String contractNumber);

    List<Contract> findByLandlordIdOrderByCreatedAtDesc(String landlordId);

    List<Contract> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    @Query("SELECT c FROM Contract c WHERE (c.landlordId = :userId OR c.tenantId = :userId) ORDER BY c.createdAt DESC")
    List<Contract> findByUserInvolved(@Param("userId") String userId);

    @Query("SELECT c FROM Contract c WHERE (c.landlordId = :userId OR c.tenantId = :userId) AND c.status = :status ORDER BY c.createdAt DESC")
    List<Contract> findByUserInvolvedAndStatus(@Param("userId") String userId, @Param("status") ContractStatus status);

    List<Contract> findAllByOrderByCreatedAtDesc();

    List<Contract> findByStatusOrderByCreatedAtDesc(ContractStatus status);

    Page<Contract> findByStatus(ContractStatus status, Pageable pageable);

    boolean existsByRentalRequestId(String rentalRequestId);

    boolean existsByRentalRequestIdAndStatusIn(String rentalRequestId, Set<ContractStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Contract c WHERE c.id = :id")
    Optional<Contract> findByIdForUpdate(@Param("id") String id);
}
