package com.hs.contract.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.*;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.listing.repository.*;
import com.hs.listing.service.*;
import com.hs.payment.dto.PaymentRequestResponse;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.payment.repository.DepositRecordRepository;
import com.hs.payment.service.PaymentRequestService;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Coordinates only the pre-tenancy workflow. Confirmed funds never expire into a vacant room. */
@Service @RequiredArgsConstructor @Slf4j
public class RentalOnboardingService {
    private final RentalRequestRepository rentals;
    private final RentalStartChangeRepository changes;
    private final RentalOnboardingEventRepository events;
    private final PaymentRequestRepository payments;
    private final PaymentRequestService paymentService;
    private final ContractRepository contracts;
    private final ContractRevisionRepository revisions;
    private final DepositRecordRepository deposits;
    private final SignatureRequestRepository signatures;
    private final ContractDocumentRepository documents;
    private final ParkingReservationService parking;
    private final ListingStatusService listingStatus;
    private final RentalCostCalculator calculator;
    private final ListingRepository listings;
    private final PropertyBranchRepository branches;
    private final BillingTime time;
    private final ObjectMapper mapper;
    private final com.hs.contract.service.engine.ContractDataBuilder builder;
    private final PlatformTransactionManager transactions;
    @Value("${rental.start-change-response-hours:24}") private long responseHours=24;
    @Value("${rental.first-rent-window-hours:24}") private long rentWindowHours=24;
    @Value("${homespace.payment.contract-due-hours:48}") private long signingHours=48;
    @Value("${rental.default-response-hours:24}") private long defaultResponseHours=24;
    @Value("${homespace.signature.mode:INTERNAL}") private String signatureMode="INTERNAL";

    public record StartRequest(@NotNull LocalDate startsOn,@NotBlank @Size(min=10,max=1000) String reason) {}
    public record Decision(boolean approve,@Size(max=1000) String note) {}
    public record View(Instant serverNow,String timezone,String stage,LocalDate startsOn,LocalDate endExclusive,
                       Instant paymentOpensAt,Instant paymentDueAt,Instant signingDueAt,boolean canPrepareContract,
                       boolean canSign,boolean canRequestEarlierStart,long holdMinutes,
                       List<PaymentRequestResponse> payments,List<RentalStartChange> startChanges,String message,String askerRole,
                       Instant cancellationRequestedAt,String cancellationReason,List<RentalOnboardingEvent> history,
                       DefaultView defaultCase,DepositView deposit,boolean canRequestCancellation) {}
    public record DepositView(String status,java.math.BigDecimal heldAmount,java.math.BigDecimal retainedAmount,
                              java.math.BigDecimal refundableAmount,String retainedReason) {}
    public record DefaultView(String eligibleKind,Instant notifiedAt,Instant respondBy,String reason,
                              Instant disputedAt,Instant completedAt,boolean canComplete,String policyVersion) {}
    @Value("${rental.hold-duration-minutes:60}") private long holdMinutes=60;

    private AppException invalid(String message) { return new AppException(ContractErrorCode.CONTRACT_SIGNING_NOT_ALLOWED,message); }
    private RentalRequest rental(String id,String actor) {
        var r=rentals.findByIdForUpdate(id).orElseThrow(()->invalid("Không tìm thấy yêu cầu thuê."));
        if(actor==null || (!actor.equals(r.getOwnerId())&&!actor.equals(r.getRenterId())))throw invalid("Không tìm thấy yêu cầu thuê.");
        return r;
    }
    private PaymentRequest payment(String id,PaymentType type) { return payments.findByRentalRequestIdAndType(id,type).orElse(null); }
    private boolean confirmed(PaymentRequest p) { return p!=null&&p.getStatus()==PaymentStatus.CONFIRMED; }
    private boolean unresolved(PaymentRequest p) {
        return p!=null&&Set.of(PaymentStatus.DISPUTED,PaymentStatus.REJECTED).contains(p.getStatus())
            || p!=null&&p.getStatus()==PaymentStatus.TRANSFER_REPORTED&&p.getConfirmationDueAt()!=null&&!time.now().isBefore(p.getConfirmationDueAt());
    }
    private boolean depositReady(RentalRequest r) {
        return confirmed(payment(r.getId(),PaymentType.RESERVATION_DEPOSIT))||confirmed(payment(r.getId(),PaymentType.INITIAL));
    }
    @Transactional public View refresh(String id,String actor) {
        var r=rental(id,actor); sync(r); return view(r,actor);
    }
    private void sync(RentalRequest r) {
        for(var c:changes.findByRentalRequestIdOrderByRequestedAtDesc(r.getId())) {
            if("REQUESTED".equals(c.getStatus())&&(!time.now().isBefore(c.getRespondBy())||c.getRequestedDate().isBefore(time.today()))) {
                c.setStatus("EXPIRED");c.setDecidedAt(time.now());c.setDecidedBy("SYSTEM");changes.save(c);
            }
        }
        if(r.getStatus()!=RentalRequestStatus.ACCEPTED)return;
        if(r.getCancellationRequestedAt()!=null)return;
        var deposit=payment(r.getId(),PaymentType.RESERVATION_DEPOSIT);
        var rent=payment(r.getId(),PaymentType.INITIAL);
        if(confirmed(deposit)&&rent==null&&!time.today().isBefore(r.getMoveInDate())) {
            var opens=r.getMoveInDate().atStartOfDay(BillingTime.ZONE).toInstant();
            // A delayed restart retains the original opening/deadline; it does not invent a new lease date.
            rent=paymentService.createFirstRent(r,opens,opens.plus(Duration.ofHours(rentWindowHours)));
        }
        final PaymentRequest firstRent=rent;
        if(firstRent!=null) contracts.findByRentalRequestId(r.getId()).ifPresent(contract->{
            paymentService.attachOnboardingToContract(r.getId(),contract.getId());
            if(contract.getStatus()==ContractStatus.DRAFT) {
                contract.setRentalPaymentId(firstRent.getId());
                contract.setPaymentStatus(confirmed(firstRent)?com.hs.contract.model.constant.ContractPaymentStatus.PAID:com.hs.contract.model.constant.ContractPaymentStatus.UNPAID);
                contract.setPaidAt(firstRent.getConfirmedAt());contracts.save(contract);
                var rev=revisions.findById(contract.getCurrentRevisionId()).orElseThrow();
                try { rev.setInitialPaymentSnapshot(mapper.writeValueAsString(builder.onboardingPayment(r,firstRent,Map.of(),List.of()))); revisions.save(rev); }
                catch(java.io.IOException e){throw new IllegalStateException("Không cập nhật được bảng ghi nhận thanh toán.",e);}
            }
        });
    }
    private View view(RentalRequest r,String actor) {
        var deposit=payment(r.getId(),PaymentType.RESERVATION_DEPOSIT);
        var rent=payment(r.getId(),PaymentType.INITIAL);
        var opens=r.getMoveInDate().atStartOfDay(BillingTime.ZONE).toInstant();
        var signDue=rent!=null&&rent.getConfirmedAt()!=null?(rent.getContractDueAt()!=null?rent.getContractDueAt():rent.getConfirmedAt().plus(Duration.ofHours(signingHours))):null;
        var contract=contracts.findByRentalRequestId(r.getId()).orElse(null);
        if(contract!=null&&contract.getTenantSigningDueAt()!=null)signDue=contract.getTenantSigningDueAt();
        if(contract!=null&&contract.getStatus()==ContractStatus.TENANT_SIGNATURE_PENDING && r.getDefaultRespondBy()!=null
                && "TENANT_SIGNING".equals(r.getDefaultNoticeKind()) && r.getDefaultDisputedAt()==null
                && defaultFingerprint(r,"TENANT_SIGNING").equals(r.getDefaultNoticeFingerprint())
                && (signDue==null||signDue.isBefore(r.getDefaultRespondBy())))signDue=r.getDefaultRespondBy();
        boolean paid=confirmed(rent)&&(deposit==null||confirmed(deposit));
        boolean signingOpen=r.getStatus()==RentalRequestStatus.ACCEPTED&&r.getCancellationRequestedAt()==null
            &&r.getListing().getStatus()==com.hs.listing.model.constant.ListingStatus.RESERVED
            &&paid&&!time.now().isBefore(opens)&&signDue!=null&&time.now().isBefore(signDue);
        String stage,message;
        if(r.getStatus()==RentalRequestStatus.COMPLETED){stage="COMPLETED";message="Đã ký đủ và bắt đầu hợp đồng.";}
        else if(r.getStatus()!=RentalRequestStatus.ACCEPTED){stage=r.getStatus().name();message="Yêu cầu chưa được chấp nhận hoặc không còn hiệu lực.";}
        else if(r.getCancellationRequestedAt()!=null){stage="CANCELLATION_REQUESTED";message="Người thuê đề nghị hủy hồ sơ và hoàn tiền; chờ chủ nhà quyết định.";}
        else if(unresolved(deposit)||unresolved(rent)){stage="REQUIRES_REVIEW";message="Cần đối soát khoản chuyển; phòng tiếp tục được giữ, không tự xác nhận hay hoàn tiền.";}
        else if(!depositReady(r)&&(deposit!=null&&deposit.getStatus()==PaymentStatus.TRANSFER_REPORTED||rent!=null&&rent.getStatus()==PaymentStatus.TRANSFER_REPORTED)){stage="AWAITING_DEPOSIT_CONFIRMATION";message="Đã báo chuyển khoản giữ chỗ, chờ chủ nhà xác nhận nhận đủ tiền.";}
        else if(!depositReady(r)){stage="AWAITING_DEPOSIT";message="Chờ chuyển khoản giữ chỗ trước hạn ghi trên yêu cầu thanh toán.";}
        else if(rent==null){stage="WAITING_START";message="Đã xác nhận cọc. Tiền tháng đầu và ký hợp đồng mở vào ngày bắt đầu thuê.";}
        else if(!paid){
            boolean reported=rent.getStatus()==PaymentStatus.TRANSFER_REPORTED;
            stage=reported?"AWAITING_RENT_CONFIRMATION":rent.getDueAt()!=null&&!time.now().isBefore(rent.getDueAt())?"REQUIRES_REVIEW":"AWAITING_FIRST_RENT";
            message=reported?"Chờ chủ nhà xác nhận tiền tháng đầu.":"Chờ tiền thuê tháng đầu; không thu lại tiền cọc. Quá hạn cần hai bên đối soát, không tự đổi ngày thuê.";
        } else {stage=signingOpen?"READY_TO_SIGN":"REQUIRES_REVIEW";message=signingOpen?"Đã xác nhận đủ tiền. Hoàn thiện văn bản và ký trước hạn.":"Đã quá hạn hoàn tất ký; cần hai bên xử lý hồ sơ, không tự giải phóng phòng hoặc ghi lùi ngày ký.";}
        var rows=new ArrayList<PaymentRequestResponse>();
        for(var p:Arrays.asList(deposit,rent))if(p!=null)rows.add(paymentService.getPaymentRequest(p.getId(),actor));
        for(var refund:payments.findByRentalRequestIdAndTypeIn(r.getId(),List.of(PaymentType.INITIAL_PAYMENT_REFUND)))rows.add(paymentService.getPaymentRequest(refund.getId(),actor));
        boolean earlier=r.getStatus()==RentalRequestStatus.ACCEPTED&&r.getCancellationRequestedAt()==null&&depositReady(r)&&rent==null
            &&r.getMoveInDate().isAfter(time.today())&&(contract==null||contract.getStatus()==ContractStatus.DRAFT)
            &&changes.findByRentalRequestIdOrderByRequestedAtDesc(r.getId()).stream().noneMatch(c->"REQUESTED".equals(c.getStatus()));
        return new View(time.now(),BillingTime.ZONE.toString(),stage,r.getMoveInDate(),r.getMoveInDate().plusMonths(r.getLeaseMonths()),
            opens,rent!=null?rent.getDueAt():null,signDue,r.getStatus()==RentalRequestStatus.ACCEPTED&&r.getCancellationRequestedAt()==null&&depositReady(r)&&(contract==null||contract.getStatus()==ContractStatus.DRAFT),signingOpen,earlier,holdMinutes,
            rows,changes.findByRentalRequestIdOrderByRequestedAtDesc(r.getId()),message,actor.equals(r.getOwnerId())?"LANDLORD":"TENANT",r.getCancellationRequestedAt(),r.getCancellationReason(),events.findByRentalRequestIdOrderByOccurredAtDesc(r.getId()),
            defaultView(r),deposits.findByRentalRequestId(r.getId()).map(d->new DepositView(d.getStatus().name(),d.getHeldAmount(),d.getRetainedAmount(),d.getRefundableAmount(),d.getRetainedReason())).orElse(null),canCancel(r,contract));
    }
    @Transactional public View requestEarlier(String id,String actor,StartRequest request) {
        var r=rental(id,actor);sync(r);
        if(!actor.equals(r.getRenterId())||!view(r,actor).canRequestEarlierStart())throw invalid("Hồ sơ không cho phép đề nghị bắt đầu sớm.");
        validateDate(r,request.startsOn());
        changes.save(RentalStartChange.builder().rentalRequestId(id).previousDate(r.getMoveInDate()).requestedDate(request.startsOn())
            .reason(request.reason().trim()).status("REQUESTED").requestedAt(time.now())
            .respondBy(min(time.now().plus(Duration.ofHours(responseHours)),request.startsOn().plusDays(1).atStartOfDay(BillingTime.ZONE).toInstant())).build());
        return view(r,actor);
    }
    private void validateDate(RentalRequest r,LocalDate date) {
        if(date==null||date.isBefore(time.today())||!date.isBefore(r.getMoveInDate()))throw invalid("Ngày mới phải từ hôm nay và trước ngày bắt đầu hiện tại.");
        if(r.getListing().getAvailableFrom()!=null&&date.isBefore(r.getListing().getAvailableFrom()))throw invalid("Tài sản chưa sẵn sàng bàn giao vào ngày này.");
    }
    @Transactional public View decideEarlier(String id,String changeId,String actor,Decision decision) {
        var r=rental(id,actor);sync(r);
        var change=changes.findById(changeId).filter(c->id.equals(c.getRentalRequestId())).orElseThrow(()->invalid("Không tìm thấy đề nghị."));
        if(!actor.equals(r.getOwnerId())||!"REQUESTED".equals(change.getStatus())||r.getStatus()!=RentalRequestStatus.ACCEPTED)throw invalid("Đề nghị không còn chờ chủ nhà quyết định.");
        if(decision.approve()) {
            if(r.getCancellationRequestedAt()!=null)throw invalid("Đang chờ quyết định hủy hồ sơ; chưa thể đổi ngày bắt đầu thuê.");
            validateDate(r,change.getRequestedDate());
            if(!change.getPreviousDate().equals(r.getMoveInDate())||!depositReady(r)||payment(id,PaymentType.INITIAL)!=null)throw invalid("Điều kiện thuê hoặc thanh toán đã thay đổi; vui lòng tạo đề nghị mới.");
            var contract=contracts.findByRentalRequestId(id).orElse(null);
            if(contract!=null) {
                contract=contracts.findByIdForUpdate(contract.getId()).orElseThrow();
                if(contract.getStatus()!=ContractStatus.DRAFT)throw invalid("Đã bắt đầu ký, không thể sửa ngày thuê trực tiếp.");
            }
            var listing=r.getListing();
            if(listing.getStatus()!=com.hs.listing.model.constant.ListingStatus.RESERVED)throw invalid("Tài sản không còn ở trạng thái giữ chỗ.");
            if(listing.getBranchId()!=null)branches.findByIdForUpdate(listing.getBranchId()).orElseThrow();
            else listings.findByIdForUpdate(listing.getId()).orElseThrow();
            parking.releaseReservationsForRequest(id);
            // The old hold is released only inside this transaction; any failed capacity check rolls everything back.
            calculator.calculate(listing,change.getRequestedDate(),r.getLeaseMonths(),r.getOccupantCount(),r.getMotorbikeCount(),r.getCarCount(),r.getDepositAmount());
            r.setMoveInDate(change.getRequestedDate());rentals.save(r);
            parking.createHeldReservations(r,listing,null);
            if(contract!=null) {
                var rev=revisions.findById(contract.getCurrentRevisionId()).orElseThrow();
                try {
                    Map<String,Object> lease=mapper.readValue(rev.getLeaseSnapshot(),new TypeReference<>(){});
                    var fmt=DateTimeFormatter.ofPattern("dd/MM/yyyy");
                    lease.put("startDateText",r.getMoveInDate().format(fmt));lease.put("endDateText",r.getMoveInDate().plusMonths(r.getLeaseMonths()).minusDays(1).format(fmt));
                    var next=new com.hs.contract.model.ContractRevision();
                    org.springframework.beans.BeanUtils.copyProperties(rev,next,"id","revisionNumber","createdAt","updatedAt");
                    next.setRevisionNumber(revisions.findMaxRevisionNumberByContractId(contract.getId())+1);
                    next.setLeaseSnapshot(mapper.writeValueAsString(lease));next.setRevisionNote("Hai bên đồng ý bắt đầu thuê sớm: "+change.getId());
                    next=revisions.saveAndFlush(next);contract.setCurrentRevisionId(next.getId());contracts.save(contract);
                }catch(java.io.IOException e){throw new IllegalStateException("Không đọc được điều khoản thuê.",e);}
            }
        }
        change.setStatus(decision.approve()?"APPROVED":"REJECTED");change.setDecidedAt(time.now());change.setDecidedBy(actor);change.setResponseNote(decision.note());changes.save(change);
        sync(r);return view(r,actor);
    }
    @Transactional public View withdrawEarlier(String id,String changeId,String actor) {
        var r=rental(id,actor);sync(r);
        var c=changes.findById(changeId).filter(x->id.equals(x.getRentalRequestId())).orElseThrow(()->invalid("Không tìm thấy đề nghị."));
        if(!actor.equals(r.getRenterId())||!"REQUESTED".equals(c.getStatus()))throw invalid("Không thể rút đề nghị này.");
        c.setStatus("WITHDRAWN");c.setDecidedAt(time.now());c.setDecidedBy(actor);changes.save(c);return view(r,actor);
    }
    @Transactional public void validateSigning(String rentalId,String actor) {
        var r=rental(rentalId,actor);sync(r);
        if(!view(r,actor).canSign())throw invalid("Chưa đến ngày bắt đầu thuê, chưa xác nhận đủ tiền hoặc đã hết hạn ký. Vui lòng xem tiến trình nhận nhà.");
    }
    public record Cancellation(@NotBlank @Size(min=10,max=1000) String reason) {}
    /** Either party can release an unpaid hold without waiting for its timeout. */
    @Transactional public View cancelUnpaid(String id,String actor) {
        var r=rental(id,actor);
        boolean owner=actor.equals(r.getOwnerId());
        var target=owner?RentalRequestStatus.CANCELLED_BY_OWNER:RentalRequestStatus.CANCELLED_BY_RENTER;
        if(Set.of(RentalRequestStatus.CANCELLED_BY_RENTER,RentalRequestStatus.CANCELLED_BY_OWNER).contains(r.getStatus()))return view(r,actor);
        if(!Set.of(RentalRequestStatus.PENDING,RentalRequestStatus.ACCEPTED).contains(r.getStatus()))throw invalid("Yêu cầu không còn ở bước có thể hủy giữ chỗ.");
        var c=contracts.findByRentalRequestId(id).orElse(null);
        if(c!=null) {
            c=contracts.findByIdForUpdate(c.getId()).orElseThrow();
            if(c.getStatus()!=ContractStatus.DRAFT || c.getSignedAt()!=null || signatureNeedsReconciliation(c))throw invalid("Đã bắt đầu ký hoặc cần đối soát; không thể hủy giữ chỗ ngay.");
        }
        var rows=new ArrayList<PaymentRequest>();
        for(var type:List.of(PaymentType.RESERVATION_DEPOSIT,PaymentType.INITIAL)) {
            var p=payments.findByRentalRequestIdAndTypeForUpdate(id,type).orElse(null);
            if(p!=null) {
                if(!PaymentRequestService.cancellableBeforeTransfer(p))throw invalid("Đã có tiền/chứng từ cần đối soát. Hãy dùng đề nghị hủy và hoàn tiền, không hủy giữ chỗ ngay.");
                rows.add(p);
            }
        }
        var d=deposits.findByRentalRequestIdForUpdate(id).orElse(null);
        if(d!=null && (d.getHeldAmount()!=null&&d.getHeldAmount().signum()>0 || d.getRetainedAmount()!=null&&d.getRetainedAmount().signum()>0))throw invalid("Đã nhận cọc; cần xử lý theo quy trình hủy và hoàn tiền.");
        String reason=owner?"Chủ nhà hủy yêu cầu thuê trước khi chuyển tiền":"Người thuê hủy yêu cầu thuê trước khi chuyển tiền";
        for(var p:rows)if(p.getStatus()!=PaymentStatus.CONFIRMED && p.getStatus()!=PaymentStatus.CANCELLED) {
            p.setStatus(PaymentStatus.CANCELLED);p.setCancelledAt(time.now());p.setCancelledReason(reason);payments.save(p);
        }
        if(c!=null){c.setStatus(ContractStatus.CANCELLED);contracts.save(c);}
        boolean held=r.getStatus()==RentalRequestStatus.ACCEPTED;
        r.setStatus(target);r.setHoldExpiresAt(null);r.setCancellationReason(reason);rentals.save(r);
        for(var change:changes.findByRentalRequestIdOrderByRequestedAtDesc(id))if("REQUESTED".equals(change.getStatus())) {
            change.setStatus("WITHDRAWN");change.setDecidedAt(time.now());change.setDecidedBy(actor);changes.save(change);
        }
        parking.releaseReservationsForRequest(id);
        if(held)listingStatus.releaseReserved(r.getListing(),actor,reason);
        audit(r,actor,owner?"OWNER_CANCELLED_UNPAID":"TENANT_CANCELLED_UNPAID",reason);
        return view(r,actor);
    }
    private void requireUnsigned(RentalRequest r) {
        var contract=contracts.findByRentalRequestId(r.getId()).orElse(null);
        if(contract!=null)contract=contracts.findByIdForUpdate(contract.getId()).orElseThrow();
        if(!canCancel(r,contract))throw invalid("Đã ký đủ hoặc chữ ký chưa rõ kết quả; cần đối soát trước khi hủy hồ sơ nhận nhà.");
    }
    private boolean canCancel(RentalRequest r,com.hs.contract.model.Contract c) {
        return r.getStatus()==RentalRequestStatus.ACCEPTED && (c==null || c.getSignedAt()==null
            && Set.of(ContractStatus.DRAFT,ContractStatus.PENDING_REVIEW,ContractStatus.TENANT_SIGNATURE_PENDING).contains(c.getStatus())
            && !signatureNeedsReconciliation(c));
    }
    private boolean signatureNeedsReconciliation(com.hs.contract.model.Contract c) {
        for(var s:signatures.findByContractIdOrderByCreatedAtDesc(c.getId())) {
            if(s.getStatus()==com.hs.contract.model.constant.SignatureRequestStatus.SIGNED) {
                if(s.getSignerRole()==com.hs.contract.model.constant.SignerRole.TENANT)return true;
            } else if(s.getProviderSignatureValue()!=null || !Set.of(com.hs.contract.model.constant.SignatureRequestStatus.REJECTED,
                    com.hs.contract.model.constant.SignatureRequestStatus.CANCELLED,
                    com.hs.contract.model.constant.SignatureRequestStatus.EXPIRED).contains(s.getStatus()))return true;
        }
        return false;
    }
    @Transactional public View requestCancellation(String id,String actor,Cancellation p) {
        var r=rental(id,actor);requireUnsigned(r);
        if(!actor.equals(r.getRenterId())||r.getCancellationRequestedAt()!=null)throw invalid("Không thể gửi đề nghị này.");
        r.setCancellationRequestedAt(time.now());r.setCancellationReason(p.reason().trim());rentals.save(r);
        audit(r,actor,"CANCELLATION_REQUESTED",p.reason().trim());return view(r,actor);
    }
    @Transactional public View decideCancellation(String id,String actor,Decision p) {
        var r=rental(id,actor);requireUnsigned(r);
        if(!actor.equals(r.getOwnerId())||r.getCancellationRequestedAt()==null)throw invalid("Không có đề nghị hủy chờ chủ nhà quyết định.");
        if(p.approve()) {
            for(var type:List.of(PaymentType.RESERVATION_DEPOSIT,PaymentType.INITIAL)) {
                var payment=payments.findByRentalRequestIdAndTypeForUpdate(id,type).orElse(null);
                if(payment==null)continue;
                if(Set.of(PaymentStatus.TRANSFER_REPORTED,PaymentStatus.DISPUTED,PaymentStatus.REJECTED).contains(payment.getStatus()))
                    throw invalid("Phải đối soát khoản chuyển trước khi quyết định hoàn tiền.");
                if(confirmed(payment))paymentService.createRefundObligation(payment);
                else {payment.setStatus(PaymentStatus.CANCELLED);payment.setCancelledAt(time.now());payment.setCancelledReason("Hai bên đồng ý hủy hồ sơ nhận nhà");payments.save(payment);}
            }
            r.setStatus(RentalRequestStatus.CANCELLED_BY_RENTER);r.setHoldExpiresAt(null);rentals.save(r);
            contracts.findByRentalRequestId(id).ifPresent(c->{c.setStatus(ContractStatus.CANCELLED);contracts.save(c);});
            parking.releaseReservationsForRequest(id);listingStatus.releaseReserved(r.getListing(),actor,"Hai bên đồng ý hủy; nghĩa vụ hoàn tiền được theo dõi riêng");
        }else {r.setCancellationRequestedAt(null);r.setCancellationReason(null);rentals.save(r);}
        audit(r,actor,p.approve()?"CANCELLATION_APPROVED":"CANCELLATION_REJECTED",p.note());
        return view(r,actor);
    }
    public record ReviewDecision(boolean received,@NotBlank @Size(min=10,max=1000) String reason) {}
    @Transactional public View reopenSigning(String id,String actor,Cancellation note) {
        var r=rental(id,actor);var rent=payments.findByRentalRequestIdAndTypeForUpdate(id,PaymentType.INITIAL).orElseThrow(()->invalid("Chưa có khoản tiền tháng đầu."));
        if(!actor.equals(r.getOwnerId())||r.getStatus()!=RentalRequestStatus.ACCEPTED||!confirmed(rent)||!depositReady(r)||r.getCancellationRequestedAt()!=null)
            throw invalid("Chỉ chủ nhà được mở lại ký sau khi đã đối soát đủ tiền.");
        rent.setContractDueAt(time.now().plus(Duration.ofHours(signingHours)));payments.save(rent);
        contracts.findByRentalRequestId(id).ifPresent(c->{
            if(c.getTenantSigningDueAt()!=null){c.setTenantSigningDueAt(rent.getContractDueAt());contracts.save(c);}
        });
        audit(r,actor,"SIGNING_REOPENED",note.reason());return view(r,actor);
    }
    @Transactional public View resolveReview(String id,String paymentId,String actor,ReviewDecision decision) {
        var r=rental(id,actor);
        if(!actor.equals(r.getOwnerId())||r.getStatus()!=RentalRequestStatus.ACCEPTED)throw invalid("Không có quyền xử lý đối soát.");
        var p=payments.findByIdForUpdate(paymentId).filter(x->id.equals(x.getRentalRequestId())).orElseThrow(()->invalid("Không tìm thấy khoản chuyển."));
        if(!PaymentRequestService.isOnboarding(p)||!(Set.of(PaymentStatus.DISPUTED,PaymentStatus.REJECTED).contains(p.getStatus())
                ||p.getStatus()==PaymentStatus.AWAITING_TRANSFER&&p.getDueAt()!=null&&!time.now().isBefore(p.getDueAt())))throw invalid("Khoản chuyển không ở trạng thái cần đối soát.");
        audit(r,actor,decision.received()?"PAYMENT_RECEIPT_VERIFIED":"PAYMENT_REOPENED",decision.reason());
        if(decision.received()) {p.setStatus(PaymentStatus.TRANSFER_REPORTED);payments.save(p);paymentService.confirmReceipt(p.getId(),actor);}
        else {p.setStatus(PaymentStatus.AWAITING_TRANSFER);p.setRejectedReason(decision.reason().trim());p.setDueAt(time.now().plus(Duration.ofHours(rentWindowHours)));payments.save(p);r.setHoldExpiresAt(p.getDueAt());rentals.save(r);}
        sync(r);return view(r,actor);
    }
    private Instant min(Instant a,Instant b){return a.isBefore(b)?a:b;}

    /** Called in the verified landlord-signature transaction. Never use the machine clock for this window. */
    public void landlordSigned(com.hs.contract.model.Contract contract) {
        contract.setTenantSigningDueAt(time.now().plus(Duration.ofHours(signingHours)));
    }

    private String defaultKind(RentalRequest r) {
        if(r.getStatus()!=RentalRequestStatus.ACCEPTED || r.getCancellationRequestedAt()!=null || r.getDefaultDisputedAt()!=null
                || !"RESERVATION_DEFAULT_V1".equals(r.getReservationPolicyVersion()) || r.getReservationPolicyAcceptedAt()==null
                || r.getListing().getStatus()!=com.hs.listing.model.constant.ListingStatus.RESERVED)return null;
        var d=deposits.findByRentalRequestId(r.getId()).orElse(null);
        if(d==null || d.getStatus()!=DepositStatus.HELD || d.getHeldAmount()==null || d.getHeldAmount().signum()<=0)return null;
        var c=contracts.findByRentalRequestId(r.getId()).orElse(null);
        if(c==null || c.getSignedAt()!=null || c.getParentContractId()!=null || c.getCurrentRevisionId()==null
                || !Set.of(ContractStatus.DRAFT,ContractStatus.TENANT_SIGNATURE_PENDING).contains(c.getStatus()))return null;
        // A signed/unknown provider transaction must be reconciled, never classified as a tenant no-show.
        for(var s:signatures.findByContractIdOrderByCreatedAtDesc(c.getId())) {
            if(s.getProviderSignatureValue()!=null && s.getStatus()!=com.hs.contract.model.constant.SignatureRequestStatus.SIGNED)return null;
            if(s.getStatus()==com.hs.contract.model.constant.SignatureRequestStatus.SIGNED
                    && s.getSignerRole()==com.hs.contract.model.constant.SignerRole.TENANT)return null;
            if(!Set.of(com.hs.contract.model.constant.SignatureRequestStatus.SIGNED,
                    com.hs.contract.model.constant.SignatureRequestStatus.REJECTED,
                    com.hs.contract.model.constant.SignatureRequestStatus.CANCELLED,
                    com.hs.contract.model.constant.SignatureRequestStatus.EXPIRED).contains(s.getStatus()))return null;
        }
        var deposit=payment(r.getId(),PaymentType.RESERVATION_DEPOSIT);
        var rent=payment(r.getId(),PaymentType.INITIAL);
        if(!depositReady(r) || rent==null || deposit!=null&&!confirmed(deposit)
                || Set.of(PaymentStatus.TRANSFER_REPORTED,PaymentStatus.DISPUTED,PaymentStatus.REJECTED).contains(rent.getStatus()))return null;
        var revision=revisions.findById(c.getCurrentRevisionId()).orElse(null);
        if(revision==null)return null;
        var renderIdentity=ContractPreviewIdentity.fingerprint(revision,signatureMode);
        boolean documentReady=documents.findByContractIdAndRevisionId(c.getId(),c.getCurrentRevisionId()).stream()
            .anyMatch(doc->doc.getStatus()==com.hs.contract.model.constant.DocumentGenerationStatus.READY
                && doc.getDocumentType()==com.hs.contract.model.constant.ContractDocumentType.PDF
                && doc.getStorageObjectId()!=null && (doc.getPurpose()==com.hs.contract.model.constant.DocumentPurpose.SIGNED_LANDLORD
                    || renderIdentity.equals(doc.getRenderFingerprint())));
        if(!documentReady)return null; // The landlord must have prepared a complete reviewable contract.
        if(c.getStatus()==ContractStatus.DRAFT && Set.of(PaymentStatus.AWAITING_TRANSFER,PaymentStatus.OVERDUE,PaymentStatus.EXPIRED).contains(rent.getStatus())
                && rent.getDueAt()!=null&&!time.now().isBefore(rent.getDueAt()))return "FIRST_RENT";
        if(c.getStatus()==ContractStatus.TENANT_SIGNATURE_PENDING && confirmed(rent) && c.getTenantSigningDueAt()!=null
                && !time.now().isBefore(c.getTenantSigningDueAt())
                && signatures.findByContractIdOrderByCreatedAtDesc(c.getId()).stream().anyMatch(s->s.getStatus()==com.hs.contract.model.constant.SignatureRequestStatus.SIGNED
                    && s.getSignerRole()==com.hs.contract.model.constant.SignerRole.LANDLORD && c.getCurrentRevisionId().equals(s.getRevisionId())))return "TENANT_SIGNING";
        return null;
    }
    private String defaultFingerprint(RentalRequest r,String kind) {
        var c=contracts.findByRentalRequestId(r.getId()).orElseThrow();
        var p=payment(r.getId(),PaymentType.INITIAL);
        var revision=revisions.findById(c.getCurrentRevisionId()).orElseThrow();
        return kind+":"+c.getCurrentRevisionId()+":"+ContractPreviewIdentity.fingerprint(revision,signatureMode)+":"+c.getTenantSigningDueAt()+":"+p.getId()+":"+p.getDueAt()+":"+p.getContractDueAt()+":"+p.getStatus();
    }
    private DefaultView defaultView(RentalRequest r) {
        var kind=defaultKind(r);
        boolean complete=kind!=null && kind.equals(r.getDefaultNoticeKind()) && r.getDefaultRespondBy()!=null
            && !time.now().isBefore(r.getDefaultRespondBy()) && defaultFingerprint(r,kind).equals(r.getDefaultNoticeFingerprint());
        return new DefaultView(kind,r.getDefaultNoticeAt(),r.getDefaultRespondBy(),r.getDefaultNoticeReason(),r.getDefaultDisputedAt(),r.getDefaultCompletedAt(),complete,r.getReservationPolicyVersion());
    }
    private void owner(RentalRequest r,String actor) { if(!actor.equals(r.getOwnerId()))throw invalid("Chỉ chủ nhà được thực hiện."); }
    @Transactional public View notifyDefault(String id,String actor,Cancellation note) {
        var r=rental(id,actor);owner(r,actor);sync(r);
        contracts.findByRentalRequestId(id).ifPresent(c->contracts.findByIdForUpdate(c.getId()).orElseThrow());
        var kind=defaultKind(r);if(kind==null)throw invalid("Chưa đủ điều kiện: cần cọc thực nhận, thỏa thuận giữ cọc, văn bản sẵn sàng và quá hạn do người thuê; không có khoản/chữ ký đang đối soát.");
        if(defaultView(r).canComplete())return view(r,actor);
        var fingerprint=defaultFingerprint(r,kind);
        if(fingerprint.equals(r.getDefaultNoticeFingerprint()))return view(r,actor);
        r.setDefaultNoticeKind(kind);r.setDefaultNoticeAt(time.now());
        r.setDefaultRespondBy(time.now().plus(Duration.ofHours(Math.max(24,defaultResponseHours))));
        r.setDefaultNoticeReason(note.reason().trim());
        // The final response window is actionable: the tenant can still pay/sign, not merely wait to lose the deposit.
        if("FIRST_RENT".equals(kind)) {
            var rent=payments.findByRentalRequestIdAndTypeForUpdate(id,PaymentType.INITIAL).orElseThrow();
            rent.setStatus(PaymentStatus.AWAITING_TRANSFER);rent.setDueAt(r.getDefaultRespondBy());payments.save(rent);
        } else {
            var c=contracts.findByRentalRequestId(id).orElseThrow();
            c.setTenantSigningDueAt(r.getDefaultRespondBy());contracts.save(c);
        }
        r.setDefaultNoticeFingerprint(defaultFingerprint(r,kind));rentals.save(r);
        audit(r,actor,"DEFAULT_NOTICE",kind+"; hạn phản hồi "+r.getDefaultRespondBy()+"; "+note.reason());return view(r,actor);
    }
    @Transactional public View disputeDefault(String id,String actor,Cancellation note) {
        var r=rental(id,actor);
        if(!actor.equals(r.getRenterId()) || r.getDefaultNoticeAt()==null || r.getStatus()!=RentalRequestStatus.ACCEPTED)throw invalid("Không có thông báo đang xử lý.");
        r.setDefaultDisputedAt(time.now());rentals.save(r);audit(r,actor,"DEFAULT_DISPUTED",note.reason());return view(r,actor);
    }
    public record DefaultCompletion(@NotBlank @Size(min=10,max=500) String reason,
                                    @AssertTrue boolean tenantNotified,@AssertTrue boolean vacantPossessionConfirmed) {}
    @Transactional public View completeDefault(String id,String actor,DefaultCompletion note) {
        var r=rental(id,actor);owner(r,actor);
        if(r.getDefaultCompletedAt()!=null)return view(r,actor); // Retry must not release another tenant's new hold.
        var c=contracts.findByRentalRequestId(id).orElseThrow(()->invalid("Chưa có hợp đồng."));
        c=contracts.findByIdForUpdate(c.getId()).orElseThrow();
        if(!note.tenantNotified() || !note.vacantPossessionConfirmed() || !defaultView(r).canComplete())throw invalid("Chưa đủ hạn, điều kiện đã thay đổi hoặc còn vấn đề cần đối soát.");
        var rent=payments.findByRentalRequestIdAndTypeForUpdate(id,PaymentType.INITIAL).orElseThrow();
        var deposit=deposits.findByRentalRequestIdForUpdate(id).orElseThrow();
        if(confirmed(rent)) {
            var refundable=rent.getTotalAmount().subtract(rent.getId().equals(deposit.getInitialPaymentRequestId())?deposit.getHeldAmount():java.math.BigDecimal.ZERO);
            if(refundable.signum()<0)throw invalid("Sổ cọc không khớp khoản đã nhận, cần đối soát.");
            paymentService.createRentRefundObligation(rent,refundable);
        } else {rent.setStatus(PaymentStatus.CANCELLED);rent.setCancelledAt(time.now());rent.setCancelledReason(note.reason());payments.save(rent);}
        deposit.setRetainedAmount(deposit.getHeldAmount());deposit.setHeldAmount(java.math.BigDecimal.ZERO);
        deposit.setRefundableAmount(java.math.BigDecimal.ZERO);deposit.setStatus(DepositStatus.RETAINED_BY_LANDLORD);
        deposit.setRetainedAt(time.now());deposit.setRetainedReason(note.reason());deposits.save(deposit);
        c.setStatus(ContractStatus.CANCELLED);contracts.save(c);
        r.setStatus(RentalRequestStatus.CANCELLED_BY_SYSTEM);r.setDefaultCompletedAt(time.now());r.setHoldExpiresAt(null);rentals.save(r);
        for(var change:changes.findByRentalRequestIdOrderByRequestedAtDesc(id))if("REQUESTED".equals(change.getStatus())) {
            change.setStatus("EXPIRED");change.setDecidedAt(time.now());change.setDecidedBy(actor);changes.save(change);
        }
        parking.releaseReservationsForRequest(id);listingStatus.releaseReserved(r.getListing(),actor,"Hủy trước khi thuê do vi phạm đã thông báo; giữ cọc, hoàn tiền thuê riêng");
        audit(r,actor,"DEFAULT_COMPLETED",r.getDefaultNoticeKind()+"; giữ cọc "+deposit.getRetainedAmount()+"; "+note.reason());return view(r,actor);
    }
    private void audit(RentalRequest r,String actor,String action,String note) {
        events.save(RentalOnboardingEvent.builder().rentalRequestId(r.getId()).actorId(actor).action(action).note(note).occurredAt(time.now()).build());
    }
    @Scheduled(fixedDelayString="${rental.expiration-check-delay-ms:30000}") public void tick() {
        int page=0; org.springframework.data.domain.Page<RentalRequest> batch;
        do {
            batch=rentals.findByStatus(RentalRequestStatus.ACCEPTED,PageRequest.of(page++,100));
            for(var r:batch)try{new TransactionTemplate(transactions).executeWithoutResult(tx->{var locked=rentals.findByIdForUpdate(r.getId()).orElseThrow();sync(locked);});}
            catch(Exception e){log.error("RENTAL_ONBOARDING_SYNC_FAILED rental={}",r.getId(),e);}
        }while(batch.hasNext());
    }
}
