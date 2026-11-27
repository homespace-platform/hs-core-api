package com.hs.contract.repository;

import com.hs.contract.dto.branch.BranchHoldRow;
import com.hs.contract.dto.branch.BranchLeaseRow;
import com.hs.contract.model.BranchContractAssignment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface BranchContractAssignmentRepository extends JpaRepository<BranchContractAssignment, String> {

    long countByManagementBranchId(String managementBranchId);

    List<BranchContractAssignment> findAllByManagementBranchId(String managementBranchId);

    @Query("select new com.hs.contract.dto.branch.BranchRoomContract(a.roomId, a.contractId) "
            + "from BranchContractAssignment a where a.roomId in :roomIds")
    List<com.hs.contract.dto.branch.BranchRoomContract> findRoomContracts(@Param("roomIds") Collection<String> roomIds);

    @Modifying(flushAutomatically = true)
    @Query("update BranchContractAssignment a set a.roomId = null where a.roomId in :roomIds")
    void unplaceRooms(@Param("roomIds") Collection<String> roomIds);

    @Query("select new com.hs.contract.dto.branch.BranchLeaseRow(c.id, c.contractNumber, c.status, "
            + "c.agreedEarlyEndExclusive, c.agreedEarlyBillingMonths, c.effectiveLeaseMonths, r.moveInDate, "
            + "r.leaseMonths, r.renterName, "
            + "s.acceptedAt, c.terminationProposalInvoiceId, "
            + "c.unilateralTerminationSnapshot) from Contract c "
            // A legacy link to another owner's request must read as "no request".
            + "left join RentalRequest r on r.id = c.rentalRequestId and r.ownerId = c.landlordId "
            + "left join ContractClosure s on s.contractId = c.id where c.id in :ids")
    List<BranchLeaseRow> findLeaseRows(@Param("ids") Collection<String> contractIds);

    /** The owner's active root leases managed by this branch; the only contracts the monitoring views may use. */
    @Query("select c from Contract c, BranchContractAssignment a where a.contractId = c.id "
            + "and a.managementBranchId = :branchId and c.landlordId = :ownerId and c.active = true "
            + "and c.parentContractId is null and (c.agreementKind is null or c.agreementKind <> 'DEPOSIT')")
    List<com.hs.contract.model.Contract> findManagedRoots(@Param("branchId") String branchId,
                                                          @Param("ownerId") String ownerId);

    /** The owner's root leases of a listing that can occupy a room (ACTIVE or EXPIRED); the caller checks the rest. */
    @Query("select c.id from Contract c where c.listingId = :listingId and c.landlordId = :ownerId "
            + "and c.active = true and c.parentContractId is null "
            + "and (c.agreementKind is null or c.agreementKind <> 'DEPOSIT') "
            + "and c.status in (com.hs.contract.model.constant.ContractStatus.ACTIVE, "
            + "com.hs.contract.model.constant.ContractStatus.EXPIRED) order by c.id")
    List<String> findEffectiveRootIds(@Param("listingId") String listingId, @Param("ownerId") String ownerId);

    @Query("select new com.hs.contract.dto.branch.BranchRentalRow(r.id, r.listing.id, r.renterId, r.renterName, "
            + "r.moveInDate, r.status, r.active) from RentalRequest r where r.id in :ids and r.ownerId = :ownerId")
    List<com.hs.contract.dto.branch.BranchRentalRow> findOwnerRentals(@Param("ids") Collection<String> rentalIds,
                                                                      @Param("ownerId") String ownerId);

    /** Closures that left the tenant a settlement balance to transfer. */
    @Query("select s from ContractClosure s where s.contractId in :contractIds and s.balancePaymentId is not null")
    List<com.hs.contract.model.ContractClosure> findBalanceClosures(
            @Param("contractIds") Collection<String> contractIds);

    /** Signed amendments only; the caller picks the effective one with ContractTermsResolver. */
    @Query("select a from ContractAmendment a where a.contractId in :contractIds "
            + "and a.status in ('SCHEDULED', 'APPLIED') and a.signedAt is not null")
    List<com.hs.contract.model.ContractAmendment> findSignedAmendments(
            @Param("contractIds") Collection<String> contractIds);

    @Query("select new com.hs.contract.dto.branch.BranchRevisionTerms(r.id, r.financialSnapshot, "
            + "coalesce(c.parentContractId, c.id)) from ContractRevision r join r.contract c where r.id in :ids")
    List<com.hs.contract.dto.branch.BranchRevisionTerms> findRevisionTerms(@Param("ids") Collection<String> revisionIds);

    @Query("select new com.hs.contract.repository.BranchAttentionReadRepository$RevisionCharges(r.id, "
            + "r.chargesSnapshot, coalesce(c.parentContractId, c.id)) "
            + "from ContractRevision r join r.contract c where r.id in :ids")
    List<BranchAttentionReadRepository.RevisionCharges> findRevisionCharges(@Param("ids") Collection<String> revisionIds);

    /** Accepted requests of these listings that have no root lease anywhere. */
    @Query("select new com.hs.contract.dto.branch.BranchRentalRow(r.id, r.listing.id, r.renterId, r.renterName, "
            + "r.moveInDate, r.status, r.active) from RentalRequest r where r.listing.id in :listingIds "
            + "and r.ownerId = :ownerId and r.active = true "
            + "and r.status = com.hs.listing.model.constant.RentalRequestStatus.ACCEPTED "
            + "and not exists (select c.id from Contract c where c.rentalRequestId = r.id and c.parentContractId is null "
            + "and (c.agreementKind is null or c.agreementKind <> 'DEPOSIT'))")
    List<com.hs.contract.dto.branch.BranchRentalRow> findUncontractedRentals(
            @Param("listingIds") Collection<String> listingIds, @Param("ownerId") String ownerId);

    /** Accepted requests without a root lease, only while the linked listing still belongs to the room's branch. */
    @Query("select new com.hs.contract.dto.branch.BranchHoldRow(k.roomId, r.id, r.moveInDate, r.renterName) "
            + "from RentalRequest r, BranchRoomListing k, BranchSlot s, Listing l "
            + "where k.roomId in :roomIds and s.id = k.roomId and l.id = k.listingId and r.listing.id = l.id "
            + "and l.active = true and l.branchId = s.branch.id and r.active = true and r.ownerId = l.ownerId "
            + "and r.ownerId = s.branch.ownerId "
            + "and r.status = com.hs.listing.model.constant.RentalRequestStatus.ACCEPTED "
            + "and not exists (select c.id from Contract c where c.rentalRequestId = r.id and c.parentContractId is null "
            + "and (c.agreementKind is null or c.agreementKind <> 'DEPOSIT')) order by r.moveInDate, r.id")
    List<BranchHoldRow> findHolds(@Param("roomIds") Collection<String> roomIds);

    /** The owner's root leases that have no branch yet or already belong to this one. */
    @Query(value = "select c.id from Contract c where c.landlordId = :ownerId and c.active = true "
            + "and c.parentContractId is null and (c.agreementKind is null or c.agreementKind <> 'DEPOSIT') "
            + "and not exists (select a.contractId from BranchContractAssignment a where a.contractId = c.id "
            + "and a.managementBranchId <> :branchId) order by c.id",
            countQuery = "select count(c) from Contract c where c.landlordId = :ownerId and c.active = true "
                    + "and c.parentContractId is null and (c.agreementKind is null or c.agreementKind <> 'DEPOSIT') "
                    + "and not exists (select a.contractId from BranchContractAssignment a where a.contractId = c.id "
                    + "and a.managementBranchId <> :branchId)")
    Page<String> findCandidateIds(@Param("ownerId") String ownerId, @Param("branchId") String branchId, Pageable pageable);
}
