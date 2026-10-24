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

    @Query("select c from Contract c where c.rentalRequestId = :rentalRequestId and c.parentContractId is null and (c.agreementKind is null or c.agreementKind <> 'DEPOSIT')")
    Optional<Contract> findByRentalRequestId(@Param("rentalRequestId") String rentalRequestId);

    @Query("select c from Contract c where c.parentContractId is null and (c.agreementKind is null or c.agreementKind <> 'DEPOSIT') and c.status in :statuses")
    Page<Contract> findByParentContractIdIsNullAndStatusIn(java.util.Collection<ContractStatus> statuses, Pageable pageable);

    @Query("select c.id from Contract c where c.parentContractId is null and (c.agreementKind is null or c.agreementKind <> 'DEPOSIT') and c.status in :statuses "
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

    @Query("select (count(c)>0) from Contract c where c.rentalRequestId=:rentalRequestId and (c.agreementKind is null or c.agreementKind <> 'DEPOSIT')")
    boolean existsByRentalRequestId(@Param("rentalRequestId") String rentalRequestId);

    boolean existsByRentalRequestIdAndStatusIn(String rentalRequestId, Set<ContractStatus> statuses);

    /**
     * Root contracts visible to {@code userId} (same visibility as the contract list: landlords see their own
     * drafts, tenants never see drafts). Used by read-only AI queries; bounded by {@code limit}.
     */
    @Query("select new com.hs.contract.dto.ai.ContractLeaseRow(c.id, c.contractNumber, c.status, c.landlordId, "
            + "c.tenantId, c.createdAt, c.signedAt, c.effectiveLeaseMonths, r.moveInDate, r.leaseMonths, "
            + "c.currentRevisionId) from Contract c, RentalRequest r where r.id = c.rentalRequestId "
            + "and c.parentContractId is null and (c.agreementKind is null or c.agreementKind <> 'DEPOSIT') and c.active = true and (c.landlordId = :userId or "
            + "(c.tenantId = :userId and c.status <> com.hs.contract.model.constant.ContractStatus.DRAFT)) "
            + "order by c.createdAt desc")
    List<com.hs.contract.dto.ai.ContractLeaseRow> findLeaseRowsForUser(@Param("userId") String userId, Pageable limit);

    /** Text match on contract number or the current revision's property snapshot, within already-authorized ids. */
    @Query("select c.id from Contract c, ContractRevision v where v.id = c.currentRevisionId and c.id in :ids "
            + "and (lower(c.contractNumber) like :pattern or lower(v.propertySnapshot) like :pattern)")
    List<String> findIdsMatchingText(@Param("ids") java.util.Collection<String> ids, @Param("pattern") String pattern);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Contract c WHERE c.id = :id")
    Optional<Contract> findByIdForUpdate(@Param("id") String id);
}
