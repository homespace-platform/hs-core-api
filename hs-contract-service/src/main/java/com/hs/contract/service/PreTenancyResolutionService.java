package com.hs.contract.service;

import com.hs.contract.dto.billing.*;
import com.hs.contract.dto.complaint.*;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.*;
import com.hs.contract.deposit.DepositAgreementService;
import com.hs.listing.model.constant.*;
import com.hs.listing.notification.RentalRequestNotificationWriter;
import com.hs.listing.notification.RentalRequestNotificationWriter.Event;
import com.hs.listing.repository.*;
import com.hs.listing.service.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.event.InitialRefundConfirmed;
import com.hs.payment.repository.*;
import com.hs.payment.service.PaymentRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.util.*;

/** Exit before the lease is fully signed: full refund, or deposit forfeited once the deposit agreement is fully signed. */
@Service @RequiredArgsConstructor
public class PreTenancyResolutionService {
    private final RentalWorkflowSupport support;
    private final SignatureRequestRepository signatures;
    private final DepositAgreementService depositAgreements;
    private final DepositRecordRepository deposits;
    private final PaymentRequestService paymentService;
    private final ParkingReservationService parking;
    private final ListingStatusService listingStatus;
    private final ListingRepository listings;
    private final RentalStartChangeRepository changes;
    private final ComplaintCaseService complaints;
    private final DisputeCaseRepository cases;
    private final RentalRequestNotificationWriter notifications;
    @Value("${rental.resolution-response-hours:48}") private long responseHours=48;
    @Value("${homespace.payment.contract-due-hours:48}") private long signingHours=48;

    @Transactional public RentalWorkflowView get(String rental,String actor){return view(support.lock(rental,actor,"CANCELLATION"));}
    @Transactional public RentalWorkflowView propose(String rental,String actor,RentalWorkflowPayloads.Cancellation p){
        var s=support.lock(rental,actor,"CANCELLATION");support.version(s,p.expectedRevision());eligible(s);
        if(s.rental().getCancellationRequestedAt()!=null)throw support.invalid("Hãy xử lý đề nghị hủy hiện tại trước.");
        if(Set.of("PROPOSED","RESUME_PROPOSED").contains(s.record().getStatus()))throw support.invalid("Đang chờ phản hồi đề nghị hiện tại; hãy rút hoặc phản hồi trước.");
        if(activeComplaint(s))throw support.invalid("Cần xử lý khiếu nại đang mở trước khi gửi đề nghị khác.");
        if(responseHours<1||responseHours>720)throw new IllegalStateException("rental.resolution-response-hours must be 1..720");
        support.propose(s,p,support.text(p.reason()),support.evidence(s,p.evidenceIds()),support.time.now().plus(Duration.ofHours(responseHours)));
        s.rental().setResolutionWorkflowId(s.record().getId());support.rentals.save(s.rental());
        notifications.enqueueOthers(s.rental(),actor,Event.RENTAL_CANCELLATION_REQUESTED);return view(s);
    }
    @Transactional public RentalWorkflowView act(String rental,String actor,String action,RentalWorkflowPayloads.Action p){
        var s=support.lock(rental,actor,"CANCELLATION");support.version(s,p.expectedRevision());String reason=support.text(p.reason());
        var w=s.record();
        if(Set.of("REQUEST_RESUME","RESUME","DECLINE_RESUME","WITHDRAW_RESUME").contains(action))return resumeAction(s,action,p);
        if(!"PROPOSED".equals(w.getStatus()))throw support.invalid("Đề nghị không còn chờ xử lý.");
        switch(action){
            case "ACCEPT" -> {support.otherProposer(s);support.acknowledge(p);complete(s,true);}
            case "FULL_REFUND_EXIT" -> {
                support.ownerOnly(s);support.acknowledge(p);
                if(!actor.equals(w.getProposedBy())||w.getRespondBy()==null||support.time.now().isBefore(w.getRespondBy())||!p.tenantContacted())
                    throw support.invalid("Cần hết hạn phản hồi, đã liên hệ khách và xác nhận phòng chưa bàn giao. Chỉ được hoàn toàn bộ tiền.");
                complete(s,false); // the owner waives forfeiture by choosing a full-refund exit
            }
            case "WITHDRAW" -> {if(!actor.equals(w.getProposedBy()))throw support.missing();w.setStatus("WITHDRAWN");resume(s);notifications.enqueueOthers(s.rental(),actor,Event.RENTAL_CANCELLATION_WITHDRAWN);}
            case "REJECT" -> {support.otherProposer(s);w.setStatus("REJECTED");resume(s);notifications.enqueueOthers(s.rental(),actor,Event.RENTAL_CANCELLATION_REJECTED);}
            case "ESCALATE" -> {
                var complaint=complaints.openFromWorkflow(rental,actor,"CANCELLATION",w.getRevision(),reason);
                w.setComplaintId(complaint.id());w.setStatus("DISPUTED");
                notifications.enqueueOthers(s.rental(),actor,Event.RENTAL_CANCELLATION_DISPUTED);
                // Keep the signing pause until the case is resolved and an explicit new proposal is reviewed.
            }
            default -> throw support.invalid("Thao tác không hợp lệ.");
        }
        support.save(s,action,reason,List.of());return view(s);
    }
    private void resume(RentalWorkflowSupport.Source s){s.rental().setResolutionWorkflowId(null);support.rentals.save(s.rental());}
    private boolean complaintResolved(RentalWorkflowSupport.Source s){return s.record().getComplaintId()!=null&&cases.findById(s.record().getComplaintId()).filter(c->s.rental().getId().equals(c.getRentalRequestId())&&c.getStatus()==DisputeCaseStatus.RESOLVED).isPresent()&&!activeComplaint(s);}
    private void resumeReady(RentalWorkflowSupport.Source s){
        eligible(s);if(!complaintResolved(s)||s.rental().getResolutionWorkflowId()==null||s.rental().getCancellationRequestedAt()!=null||s.rental().getListing().getOperationalStatus()!=ListingStatus.RESERVED)throw support.invalid("Chỉ tiếp tục sau khi khiếu nại đã giải quyết và đúng lượt giữ phòng còn hiệu lực.");
        signatureSafe(s.contract());signatureSafe(depositAgreements.find(s.rental().getId()).orElse(null));
        for(var type:List.of(PaymentType.RESERVATION_DEPOSIT,PaymentType.INITIAL))support.payments.findByRentalRequestIdAndType(s.rental().getId(),type).ifPresent(cash->{if(Set.of(PaymentStatus.TRANSFER_REPORTED,PaymentStatus.REJECTED).contains(cash.getStatus()))throw support.invalid("Khoản chuyển tiền đang chờ xử lý: hãy xác nhận hoặc gửi lại chứng từ trước khi tiếp tục.");});
    }
    private RentalWorkflowView resumeAction(RentalWorkflowSupport.Source s,String action,RentalWorkflowPayloads.Action p){
        var w=s.record();String reason=support.text(p.reason());resumeReady(s);
        Event event;
        switch(action){
            case "REQUEST_RESUME"->{
                support.acknowledge(p);if(!"DISPUTED".equals(w.getStatus()))throw support.invalid("Không có hồ sơ sau khiếu nại cần tiếp tục.");
                w.setStatus("RESUME_PROPOSED");w.setProposedBy(s.actor());w.setOwnerConfirmedAt(support.owner(s)?support.time.now():null);w.setTenantConfirmedAt(support.owner(s)?null:support.time.now());
                event=Event.RENTAL_RESUME_REQUESTED;
            }
            case "RESUME"->{
                support.acknowledge(p);support.otherProposer(s);if(!"RESUME_PROPOSED".equals(w.getStatus()))throw support.invalid("Chưa có đề nghị tiếp tục thuê.");
                if(support.owner(s))w.setOwnerConfirmedAt(support.time.now());else w.setTenantConfirmedAt(support.time.now());
                if(signingHours<1||signingHours>720)throw new IllegalStateException("Invalid signing window");
                // Both parties explicitly reopen the existing lease-signing window; no receipt or terms are rewritten.
                if(s.contract()!=null&&s.contract().getStatus()==ContractStatus.TENANT_SIGNATURE_PENDING){s.contract().setTenantSigningDueAt(support.time.now().plus(Duration.ofHours(signingHours)));support.contracts.save(s.contract());}
                w.setStatus("RESUMED");w.setRespondBy(null);resume(s);
                event=Event.RENTAL_RESUMED;
            }
            case "DECLINE_RESUME","WITHDRAW_RESUME"->{
                if(!"RESUME_PROPOSED".equals(w.getStatus()))throw support.invalid("Không có đề nghị tiếp tục đang chờ.");
                if("DECLINE_RESUME".equals(action))support.otherProposer(s);else if(!s.actor().equals(w.getProposedBy()))throw support.missing();
                w.setStatus("DISPUTED");w.setOwnerConfirmedAt(null);w.setTenantConfirmedAt(null);
                event="DECLINE_RESUME".equals(action)?Event.RENTAL_RESUME_REJECTED:Event.RENTAL_RESUME_WITHDRAWN;
            }
            default->throw support.invalid("Thao tác không hợp lệ.");
        }
        support.save(s,action,reason,List.of());
        notifications.enqueueOthers(s.rental(),s.actor(),event);return view(s);
    }
    private boolean activeComplaint(RentalWorkflowSupport.Source s){return cases.findByActiveKey("RENTAL:"+s.rental().getId()+":TERMINATION").isPresent();}
    private void eligible(RentalWorkflowSupport.Source s){
        if(s.rental().getStatus()!=RentalRequestStatus.ACCEPTED||s.rental().getDefaultCompletedAt()!=null)throw support.invalid("Hồ sơ không còn ở bước trước thuê.");
        if(s.rental().getCancellationRefundApprovedAt()!=null)throw support.invalid("Đã đồng ý hủy. Chờ người thuê xác nhận nhận đủ tiền hoàn.");
        if(PreTenancyCancellation.fullySigned(s.contract()))throw support.invalid(PreTenancyCancellation.LEASE_SIGNED);
        if(List.of(PaymentType.RESERVATION_DEPOSIT,PaymentType.INITIAL).stream().map(t->support.payments.findByRentalRequestIdAndType(s.rental().getId(),t)).flatMap(Optional::stream).noneMatch(p->p.getStatus()==PaymentStatus.CONFIRMED))
            throw support.invalid("Chưa có tiền thực nhận; hãy dùng hủy giữ chỗ chưa thanh toán.");
    }
    private void signatureSafe(Contract c){
        if(c!=null&&PreTenancyCancellation.signatureInProgress(signatures.findByContractIdOrderByCreatedAtDesc(c.getId())))
            throw support.invalid(PreTenancyCancellation.SIGNATURE_PENDING);
    }
    private void complete(RentalWorkflowSupport.Source s,boolean mayForfeit){
        eligible(s);if(activeComplaint(s))throw support.invalid("Khiếu nại đang mở cần được giải quyết trước.");
        var held=deposits.findByRentalRequestIdForUpdate(s.rental().getId()).orElse(null);
        if(held!=null)support.reload(held);
        if(s.rental().getDepositAmount()!=null&&s.rental().getDepositAmount().signum()>0
            &&(held==null||held.getStatus()!=DepositStatus.HELD||held.getHeldAmount()==null||held.getHeldAmount().compareTo(s.rental().getDepositAmount())!=0))
            throw support.invalid("Sổ cọc đang xử lý hoặc không khớp khoản nhận; cần đối soát trước khi hoàn.");
        signatureSafe(s.contract());var dc=depositAgreements.find(s.rental().getId()).orElse(null);
        if(dc!=null)dc=support.contracts.findByIdForUpdate(dc.getId()).orElseThrow(support::missing);signatureSafe(dc);
        var r=s.rental();var listing=listings.findByIdForUpdate(r.getListing().getId()).orElseThrow(support::missing);
        support.reload(listing);
        if(listing.getOperationalStatus()!=ListingStatus.RESERVED||support.rentals.findByListingIdAndStatus(listing.getId(),RentalRequestStatus.ACCEPTED).stream().anyMatch(other->!other.getId().equals(r.getId())))
            throw support.invalid("Lượt giữ phòng đã thay đổi; không giải phóng lượt của hồ sơ khác.");
        boolean anyConfirmed=false;
        var receipts=new ArrayList<com.hs.payment.model.PaymentRequest>();
        for(var type:List.of(PaymentType.RESERVATION_DEPOSIT,PaymentType.INITIAL)) {
            var cash=support.payments.findByRentalRequestIdAndTypeForUpdate(r.getId(),type).orElse(null);if(cash==null)continue;
            support.reload(cash);
            if(cash.getStatus()==PaymentStatus.TRANSFER_REPORTED)throw support.invalid("Hãy xác nhận hoặc từ chối chứng từ chuyển khoản trước khi kết thúc và hoàn tiền.");
            if(cash.getStatus()==PaymentStatus.CONFIRMED){
                anyConfirmed=true;
                if(cash.getTotalAmount().signum()>0)receipts.add(cash);
            }
            else {cash.setStatus(PaymentStatus.CANCELLED);cash.setCancelledAt(support.time.now());cash.setCancelledReason("Kết thúc trước thuê; hoàn toàn bộ khoản đã xác nhận");support.payments.save(cash);}
        }
        if(!anyConfirmed)throw support.invalid("Chưa có khoản thực nhận; dùng hủy giữ chỗ chưa thanh toán.");
        boolean forfeit=mayForfeit&&PreTenancyCancellation.forfeits(dc,held);
        boolean refundRequired=PreTenancyCancellation.refund(paymentService,receipts,held,forfeit,support.time.now());
        if(forfeit)deposits.save(held);
        if(refundRequired){
            r.setCancellationRefundApprovedAt(support.time.now());
            r.setHoldExpiresAt(null);
            support.rentals.save(r);
            s.record().setStatus("REFUND_PENDING");
            s.record().setRespondBy(null);
            // The request stays ACCEPTED until every refund receipt is confirmed; this is not a cancellation yet.
            notifications.enqueueOthers(r,s.actor(),Event.RENTAL_CANCELLATION_REFUND_PENDING);
        }else finishCancellation(s,listing,dc);
    }

    /** Runs inside the same transaction as the recipient's receipt confirmation. */
    @EventListener
    public void onInitialRefundConfirmed(InitialRefundConfirmed event) {
        var r=support.rentals.findById(event.rentalRequestId()).orElse(null);
        if(r==null||r.getStatus()!=RentalRequestStatus.ACCEPTED||r.getCancellationRefundApprovedAt()==null)return;
        var s=support.lock(r.getId(),event.recipientId(),"CANCELLATION");
        var refunds=support.payments.findByRentalRequestIdAndTypeIn(r.getId(),List.of(PaymentType.INITIAL_PAYMENT_REFUND));
        if(refunds.isEmpty()||refunds.stream().anyMatch(p->p.getStatus()!=PaymentStatus.CONFIRMED))return;
        var deposit=depositAgreements.find(r.getId()).orElse(null);
        if(deposit!=null)deposit=support.contracts.findByIdForUpdate(deposit.getId()).orElseThrow(support::missing);
        var listing=listings.findByIdForUpdate(r.getListing().getId()).orElseThrow(support::missing);
        support.reload(listing);
        boolean workflow="REFUND_PENDING".equals(s.record().getStatus());
        finishCancellation(s,listing,deposit);
        if(workflow)support.save(s,"REFUND_CONFIRMED","Người thuê đã xác nhận nhận đủ các khoản hoàn.",List.of());
    }

    private void finishCancellation(RentalWorkflowSupport.Source s,com.hs.listing.model.Listing listing,Contract dc){
        var r=s.rental();
        if(s.contract()!=null){s.contract().setStatus(ContractStatus.CANCELLED);support.contracts.save(s.contract());}
        // Partial signatures: cancelled. Fully signed: terminated (signed PDF kept as the basis for any forfeiture).
        if(PreTenancyCancellation.endDepositAgreement(depositAgreements,dc,deposits.findByRentalRequestId(r.getId()).orElse(null),r.getId()))support.contracts.save(dc);
        r.setStatus(r.getCancellationRequestedAt()==null && r.getOwnerId().equals(s.record().getProposedBy())
                ?RentalRequestStatus.CANCELLED_BY_OWNER:RentalRequestStatus.CANCELLED_BY_RENTER);
        r.setHoldExpiresAt(null);support.rentals.save(r);
        for(var change:changes.findByRentalRequestIdOrderByRequestedAtDesc(r.getId()))if("REQUESTED".equals(change.getStatus())){change.setStatus("WITHDRAWN");change.setDecidedAt(support.time.now());change.setDecidedBy(s.actor());changes.save(change);}
        parking.releaseReservationsForRequest(r.getId());
        if(listing.getOperationalStatus()==ListingStatus.RESERVED && support.rentals.findByListingIdAndStatus(listing.getId(),RentalRequestStatus.ACCEPTED)
                .stream().noneMatch(other->!other.getId().equals(r.getId())))
            listingStatus.releaseReserved(listing,s.actor(),"Hoàn tất hủy hồ sơ trước khi thuê");
        s.record().setStatus("COMPLETED");s.record().setRespondBy(null);
        notifications.enqueueOthers(r,s.actor(),Event.RENTAL_REQUEST_CANCELLED);
    }
    private RentalWorkflowView view(RentalWorkflowSupport.Source s){
        var actions=new ArrayList<String>();String blocked=null;var w=s.record();
        boolean eligible=true;try{eligible(s);}catch(com.hs.common.advice.entity.AppException e){eligible=false;blocked=e.getMessage();}
        if(eligible){
            if("RESUME_PROPOSED".equals(w.getStatus())){
                if(s.actor().equals(w.getProposedBy()))actions.add("WITHDRAW_RESUME");else actions.addAll(List.of("RESUME","DECLINE_RESUME"));
            }else if("DISPUTED".equals(w.getStatus())&&complaintResolved(s)){actions.add("REQUEST_RESUME");actions.add("PROPOSE");}
            else if("PROPOSED".equals(w.getStatus())){
                if(s.actor().equals(w.getProposedBy())){actions.add("WITHDRAW");if(support.owner(s)&&w.getRespondBy()!=null&&!support.time.now().isBefore(w.getRespondBy()))actions.add("FULL_REFUND_EXIT");}
                else actions.addAll(List.of("ACCEPT","REJECT"));actions.add("ESCALATE");
            }else if(!activeComplaint(s))actions.add("PROPOSE");else blocked="Đang có khiếu nại cần xử lý trước khi tiếp tục.";
        }
        var refunds=support.payments.findByRentalRequestIdAndTypeIn(s.rental().getId(),List.of(PaymentType.INITIAL_PAYMENT_REFUND)).stream().map(p->paymentService.toResponse(p,s.actor())).toList();
        String obligation="REFUND_PENDING".equals(w.getStatus())?"REFUND_PENDING":"COMPLETED".equals(w.getStatus())?(refunds.stream().allMatch(p->p.status()==PaymentStatus.CONFIRMED)?"REFUNDED":"REFUND_PENDING"):s.rental().getStatus()==RentalRequestStatus.COMPLETED?"TRANSFERRED_TO_LEASE":"RESERVATION_PENDING";
        return support.view(s,actions,blocked,obligation,refunds);
    }
}
