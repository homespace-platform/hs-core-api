package com.hs.contract.repository;

import com.hs.contract.model.*;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.listing.model.constant.AppointmentStatus;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.payment.model.constant.PaymentStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;

/** Batch reads of the attention list across many branches. Select only, never locked. */
public interface BranchAttentionReadRepository extends org.springframework.data.repository.Repository<Contract, String> {

    record RoomLink(String listingId, String roomId, String branchId) {}

    record ListingRow(String id, String branchId, String title, ListingStatus status,
                      ListingStatus reviewOperationalStatus, Instant expiresAt) {}

    record RentalRow(String id, String listingId, String renterId, String renterName, LocalDate moveInDate,
                     RentalRequestStatus status, Boolean active, Instant respondBy, java.math.BigDecimal depositAmount,
                     Instant defaultCompletedAt, Instant cancellationRefundApprovedAt, String resolutionWorkflowId,
                     Instant cancellationRequestedAt, ListingStatus listingStatus,
                     ListingStatus listingReviewStatus) {}

    record Appointment(String id, String listingId, AppointmentStatus status, Boolean rescheduleRequested,
                       LocalDate date, LocalTime startTime, LocalTime endTime, LocalDate proposedDate,
                       LocalTime proposedStartTime) {}

    record Signature(String contractId, String revisionId) {}

    /** {@code rootId}: the lease the revision belongs to, directly or through one of its annexes. */
    record RevisionCharges(String id, String chargesSnapshot, String rootId) {
        public RevisionCharges(String id, String chargesSnapshot) {
            this(id, chargesSnapshot, null);
        }

        /** The queries always fill {@code rootId}; only a hand-built value has none. */
        public boolean belongsTo(String contractId) {
            return rootId == null || rootId.equals(contractId);
        }
    }

    /** Pairs of (branch id, slot). */
    @Query("select s.branch.id, s from BranchSlot s where s.branch.id in :branchIds")
    List<Object[]> findSlots(@Param("branchIds") Collection<String> branchIds);

    @Query("select new com.hs.contract.repository.BranchAttentionReadRepository$RoomLink(k.listingId, k.roomId, "
            + "s.branch.id) from BranchRoomListing k, BranchSlot s where s.id = k.roomId and s.branch.id in :branchIds")
    List<RoomLink> findRoomLinks(@Param("branchIds") Collection<String> branchIds);

    @Query("select new com.hs.contract.repository.BranchAttentionReadRepository$ListingRow(l.id, l.branchId, l.title, "
            + "l.status, l.reviewOperationalStatus, l.expiresAt) from Listing l "
            + "where l.branchId in :branchIds and l.ownerId = :ownerId and l.active = true")
    List<ListingRow> findListings(@Param("branchIds") Collection<String> branchIds, @Param("ownerId") String ownerId);

    @Query("select a from BranchContractAssignment a where a.managementBranchId in :branchIds")
    List<BranchContractAssignment> findAssignments(@Param("branchIds") Collection<String> branchIds);

    /** Same contract filter as BranchContractAssignmentRepository.findManagedRoots. */
    @Query("select c from Contract c, BranchContractAssignment a where a.contractId = c.id "
            + "and a.managementBranchId in :branchIds and c.landlordId = :ownerId and c.active = true "
            + "and c.parentContractId is null and (c.agreementKind is null or c.agreementKind <> 'DEPOSIT')")
    List<Contract> findManagedRoots(@Param("branchIds") Collection<String> branchIds, @Param("ownerId") String ownerId);

    String RENTAL = "select new com.hs.contract.repository.BranchAttentionReadRepository$RentalRow(r.id, l.id, "
            + "r.renterId, r.renterName, r.moveInDate, r.status, r.active, r.respondBy, r.depositAmount, "
            + "r.defaultCompletedAt, r.cancellationRefundApprovedAt, r.resolutionWorkflowId, "
            + "r.cancellationRequestedAt, l.status, l.reviewOperationalStatus) from RentalRequest r join r.listing l "
            + "where r.ownerId = :ownerId ";

    @Query(RENTAL + "and r.id in :ids")
    List<RentalRow> findRentals(@Param("ids") Collection<String> rentalIds, @Param("ownerId") String ownerId);

    /**
     * Requests of these listings that matter without a managed lease: waiting ones, and rootless ones that are
     * accepted or still have an open transfer (a cancelled request can still owe a refund).
     */
    @Query(RENTAL + "and l.id in :listingIds and ("
            + "r.active = true and r.status = com.hs.listing.model.constant.RentalRequestStatus.PENDING "
            + "or not exists (select c.id from Contract c where c.rentalRequestId = r.id and c.parentContractId is null "
            + "and (c.agreementKind is null or c.agreementKind <> 'DEPOSIT')) "
            + "and (r.status = com.hs.listing.model.constant.RentalRequestStatus.ACCEPTED "
            + "or exists (select p.id from PaymentRequest p where p.rentalRequestId = r.id and p.active = true "
            + "and p.status in :openStatuses)))")
    List<RentalRow> findListingRentals(@Param("listingIds") Collection<String> listingIds,
            @Param("ownerId") String ownerId, @Param("openStatuses") Collection<PaymentStatus> openStatuses);

    @Query("select new com.hs.contract.repository.BranchAttentionReadRepository$Appointment(a.id, l.id, a.status, "
            + "a.rescheduleRequested, a.appointmentDate, a.startTime, a.endTime, a.proposedDate, a.proposedStartTime) "
            + "from ViewingAppointment a join a.listing l where l.id in :listingIds and a.ownerId = :ownerId "
            + "and a.active = true and (a.status = com.hs.listing.model.constant.AppointmentStatus.PENDING "
            + "or a.status = com.hs.listing.model.constant.AppointmentStatus.CONFIRMED and a.rescheduleRequested = true)")
    List<Appointment> findOpenAppointments(@Param("listingIds") Collection<String> listingIds,
            @Param("ownerId") String ownerId);

    @Query("select i from MonthlyInvoice i where i.contractId in :contractIds")
    List<MonthlyInvoice> findInvoices(@Param("contractIds") Collection<String> contractIds);

    @Query("select a from ContractAmendment a where a.contractId in :contractIds and a.status in :statuses")
    List<ContractAmendment> findAmendments(@Param("contractIds") Collection<String> contractIds,
            @Param("statuses") Collection<String> statuses);

    @Query("select r from ContractRenewal r where r.contractId in :contractIds "
            + "and r.status in ('REQUESTED', 'APPROVED')")
    List<ContractRenewal> findOpenRenewals(@Param("contractIds") Collection<String> contractIds);

    @Query("select w from RentalWorkflowRecord w where w.contractId in :contractIds and w.kind = 'EARLY_EXIT' "
            + "and w.status in ('PROPOSED', 'APPROVED')")
    List<RentalWorkflowRecord> findEarlyExits(@Param("contractIds") Collection<String> contractIds);

    @Query("select d from WorkflowSigningDocument d where d.signingContractId in :contractIds")
    List<WorkflowSigningDocument> findWorkflowDocuments(@Param("contractIds") Collection<String> signingContractIds);

    /** Active complaint keys among the given ones. */
    @Query("select d.activeKey from DisputeCase d where d.activeKey in :keys")
    List<String> findActiveComplaintKeys(@Param("keys") Collection<String> keys);

    /** Child documents of these roots in a state the landlord may still have to sign. */
    @Query("select c from Contract c where c.parentContractId in :rootIds and c.landlordId = :ownerId "
            + "and c.active = true and c.status in :statuses")
    List<Contract> findChildDocuments(@Param("rootIds") Collection<String> rootIds, @Param("ownerId") String ownerId,
            @Param("statuses") Collection<ContractStatus> statuses);

    @Query("select c from Contract c, DepositAgreement d where d.signingContractId = c.id "
            + "and d.rentalRequestId in :rentalIds and c.landlordId = :ownerId and c.active = true")
    List<Contract> findDepositDocuments(@Param("rentalIds") Collection<String> rentalIds,
            @Param("ownerId") String ownerId);

    /** Documents (with the revision signed) this landlord already signed. */
    @Query("select new com.hs.contract.repository.BranchAttentionReadRepository$Signature(s.contractId, s.revisionId) "
            + "from SignatureRequest s where s.contractId in :contractIds and s.signerUserId = :ownerId "
            + "and s.signerRole = com.hs.contract.model.constant.SignerRole.LANDLORD "
            + "and s.status = com.hs.contract.model.constant.SignatureRequestStatus.SIGNED")
    List<Signature> findLandlordSignatures(@Param("contractIds") Collection<String> contractIds,
            @Param("ownerId") String ownerId);

    @Query("select new com.hs.contract.repository.BranchAttentionReadRepository$RevisionCharges(r.id, "
            + "r.chargesSnapshot, coalesce(c.parentContractId, c.id)) "
            + "from ContractRevision r join r.contract c where r.id in :ids")
    List<RevisionCharges> findRevisionCharges(@Param("ids") Collection<String> revisionIds);
}
