package com.hs.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.PageResponse;
import com.hs.common.time.BillingTime;
import com.hs.payment.advice.PaymentErrorCode;
import com.hs.payment.dto.*;
import com.hs.payment.event.PaymentDisputed;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.*;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.listing.model.constant.*;
import com.hs.listing.notification.RentalRequestNotificationWriter;
import com.hs.listing.notification.RentalRequestNotificationWriter.Event;
import com.hs.storage.repository.StorageObjectRepository;
import com.hs.storage.model.constant.*;
import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;

/** Cases resolve workflow disputes, never manufacture bank receipts or rewrite an agreed debt. */
@Service @RequiredArgsConstructor
public class PaymentDisputeService {
    private final DisputeCaseRepository cases;
    private final DisputeCaseEventRepository events;
    private final PaymentRequestRepository payments;
    private final PaymentEvidenceRepository evidence;
    private final PaymentEventRepository paymentEvents;
    private final DepositRecordRepository deposits;
    private final RentalRequestRepository rentals;
    private final StorageObjectRepository storage;
    private final ObjectMapper mapper;
    private final BillingTime time;
    private final RentalRequestNotificationWriter notifications;
    @PersistenceContext private EntityManager em;
    @Value("${rental.first-rent-window-hours:24}") private long reopenHours=24;
    @Value("${homespace.payment.confirmation-window-hours:24}") private long confirmationHours=24;

    @EventListener @Transactional(propagation=Propagation.MANDATORY)
    public void onDisputed(PaymentDisputed event) {
        // PaymentRequestService already holds the row lock in this transaction. Do not refresh unflushed changes.
        var p=payments.findById(event.paymentId()).orElseThrow(this::missing);
        party(p,event.actorId());
        if(p.getStatus()!=PaymentStatus.DISPUTED)throw notAllowed("Khoản tiền chưa ở trạng thái đối soát.");
        register(p,event.actorId(),text(event.reason(),1),event.previousStatus(),event.previousDepositStatus(),"OPENED");
    }

    @Transactional
    public DisputeCaseView adoptLegacy(String paymentId,String actor,String reason) {
        var p=lockPayment(paymentId,actor,false);
        if(p.getStatus()!=PaymentStatus.DISPUTED)throw notAllowed("Chỉ tiếp nhận khoản đang đối soát.");
        var active=cases.findByActiveKey(key(p.getId()));
        if(active.isPresent())return view(active.get(),p,actor,false,true);
        PaymentStatus previous=paymentEvents.findByPaymentRequestIdOrderByCreatedAtAsc(p.getId()).stream()
                .filter(e->e.getEventType()==PaymentEventType.DISPUTED && e.getFromStatus()!=PaymentStatus.DISPUTED)
                .reduce((a,b)->b).map(PaymentEvent::getFromStatus).orElse(null);
        if(previous==PaymentStatus.CONFIRMED)previous=null;
        var c=register(p,actor,text(reason,10),previous,null,"LEGACY_ADOPTED");
        return view(c,p,actor,false,true);
    }

    private DisputeCase register(PaymentRequest p,String actor,String reason,PaymentStatus previous,DepositStatus previousDeposit,String action) {
        var existing=cases.findByActiveKey(key(p.getId()));
        if(existing.isPresent())return existing.get();
        var c=DisputeCase.builder().subjectType("PAYMENT").subjectId(p.getId()).activeKey(key(p.getId()))
                .rentalRequestId(p.getRentalRequestId()).contractId(p.getContractId()).payerId(p.getPayerId()).payeeId(p.getPayeeId())
                .openedBy(actor).reason(reason).openedAt(time.now()).status(DisputeCaseStatus.OPEN)
                .previousPaymentStatus(previous).previousDepositStatus(previousDeposit)
                .amountSnapshot(p.getTotalAmount()).currency(p.getCurrency())
                .initialEvidenceSnapshot(json(evidence.findByPaymentRequestIdOrderByCreatedAtDesc(p.getId()).stream().map(PaymentEvidence::getStorageObjectId).distinct().toList()))
                .revision(1).build();
        c=cases.saveAndFlush(c);
        append(c,p,actor,false,action,reason,null,Map.of("previousStatus",Objects.toString(previous,"UNKNOWN")));
        return c;
    }

    @Transactional(readOnly=true)
    public List<DisputeCaseView> forPayment(String id,String actor) {
        var p=payments.findById(id).orElseThrow(this::missing);party(p,actor);
        return cases.findBySubjectTypeAndSubjectIdOrderByCreatedAtDescIdDesc("PAYMENT",id,PageRequest.of(0,20))
                .stream().map(c->view(c,p,actor,false,true)).toList();
    }

    @Transactional
    public DisputeCaseView act(String caseId,String actor,String action,DisputeCaseAction request) {
        var before=cases.findById(caseId).orElseThrow(this::caseMissing);
        if(!"PAYMENT".equals(before.getSubjectType()))throw caseMissing();
        var p=lockPayment(before.getSubjectId(),actor,false);
        var c=lockCase(caseId,p,request);
        party(p,actor);
        var reason=text(request.reason(),10);
        if(!"COMMENT".equals(action) && request.evidenceStorageId()!=null)
            throw invalid("Chứng cứ được gửi qua phần phản hồi; không ghép với quyết định.");
        var metadata=new LinkedHashMap<String,Object>();
        switch(action) {
            case "COMMENT" -> attachEvidence(c,p,actor,request.evidenceStorageId(),reason);
            case "PROPOSE" -> {
                requireBilateral(c);requireResolution(request.resolution());
                if(c.getProposedBy()!=null)throw notAllowed("Hãy phản hồi đề xuất hiện tại trước khi gửi đề xuất mới.");
                validateRoute(c,p,request.resolution());
                c.setProposalId(UUID.randomUUID().toString());c.setProposedBy(actor);
                c.setProposedResolution(request.resolution());c.setProposalReason(reason);
                metadata.put("proposalId",c.getProposalId());metadata.put("resolution",request.resolution().name());
            }
            case "ACCEPT" -> {
                requireBilateral(c);requireOtherProposer(c,actor);
                metadata.put("proposalId",c.getProposalId());metadata.put("proposer",c.getProposedBy());
                metadata.put("resolution",c.getProposedResolution().name());
                route(c,p,c.getProposedResolution(),actor,reason,false);
            }
            case "DECLINE" -> {
                requireBilateral(c);requireOtherProposer(c,actor);
                metadata.put("proposalId",c.getProposalId());metadata.put("resolution",c.getProposedResolution().name());
                clearProposal(c);
            }
            case "ESCALATE" -> {
                requireBilateral(c);c.setStatus(DisputeCaseStatus.ESCALATED);
                metadata.put("proposalId",Objects.toString(c.getProposalId(),""));clearProposal(c);
            }
            default -> throw invalid("Thao tác đối soát không được hỗ trợ.");
        }
        finish(c,p,actor,false,action,reason,request.evidenceStorageId(),metadata);
        return view(c,p,actor,false,true);
    }

    @Transactional(readOnly=true)
    public PageResponse<DisputeCaseView> adminList(String actor,DisputeCaseStatus status,int page,int size) {
        admin(actor);
        var pageable=PageRequest.of(Math.max(0,page-1),Math.min(50,Math.max(1,size)),Sort.by(Sort.Direction.ASC,"openedAt","id"));
        var result=status==null?cases.findBySubjectType("PAYMENT",pageable):cases.findBySubjectTypeAndStatus("PAYMENT",status,pageable);
        return new PageResponse<>(result.map(c->view(c,payments.findById(c.getSubjectId()).orElseThrow(this::missing),actor,true,false)));
    }

    @Transactional(readOnly=true)
    public DisputeCaseView adminGet(String id,String actor) {
        admin(actor);var c=cases.findById(id).orElseThrow(this::caseMissing);
        if(!"PAYMENT".equals(c.getSubjectType()))throw caseMissing();
        return view(c,payments.findById(c.getSubjectId()).orElseThrow(this::missing),actor,true,true);
    }

    @Transactional
    public DisputeCaseView adminAct(String id,String actor,String action,DisputeCaseAction request) {
        admin(actor);
        var before=cases.findById(id).orElseThrow(this::caseMissing);
        if(!"PAYMENT".equals(before.getSubjectType()))throw caseMissing();
        var p=lockPayment(before.getSubjectId(),actor,true);
        if(actor.equals(p.getPayerId())||actor.equals(p.getPayeeId()))throw notAllowed("Quản trị viên không tự phân xử hồ sơ của chính mình.");
        var c=lockCase(id,p,request);var reason=text(request.reason(),10);
        if(c.getStatus()!=DisputeCaseStatus.ESCALATED)throw notAllowed("Chỉ xử lý hồ sơ đã chuyển quản trị viên.");
        if(request.evidenceStorageId()!=null)throw invalid("Quyết định quản trị viên không phải chứng từ chuyển tiền.");
        var metadata=new LinkedHashMap<String,Object>();
        switch(action) {
            case "CLAIM" -> {metadata.put("previousAssignee",Objects.toString(c.getAssignedTo(),""));c.setAssignedTo(actor);}
            case "DECIDE" -> {
                if(!actor.equals(c.getAssignedTo()))throw notAllowed("Hãy tiếp nhận hồ sơ trước khi ra quyết định.");
                requireResolution(request.resolution());
                metadata.put("resolution",request.resolution().name());
                route(c,p,request.resolution(),actor,reason,true);
            }
            default -> throw invalid("Thao tác quản trị viên không được hỗ trợ.");
        }
        finish(c,p,actor,true,action,reason,null,metadata);
        return view(c,p,actor,true,true);
    }

    private DisputeCase lockCase(String id,PaymentRequest p,DisputeCaseAction request) {
        var c=cases.findById(id).orElseThrow(this::caseMissing);
        em.refresh(c,LockModeType.PESSIMISTIC_WRITE);
        if(!"PAYMENT".equals(c.getSubjectType())||!p.getId().equals(c.getSubjectId())
                ||!Objects.equals(c.getPayerId(),p.getPayerId())||!Objects.equals(c.getPayeeId(),p.getPayeeId())
                ||!Objects.equals(c.getContractId(),p.getContractId())||!Objects.equals(c.getRentalRequestId(),p.getRentalRequestId()))throw notAllowed("Chủ thể hoặc nghĩa vụ nguồn đã thay đổi.");
        if(request==null||request.expectedRevision()==null||request.expectedRevision()!=c.getRevision())
            throw new AppException(PaymentErrorCode.DISPUTE_CASE_STALE);
        if(c.getStatus()==DisputeCaseStatus.RESOLVED||p.getStatus()!=PaymentStatus.DISPUTED)
            throw notAllowed("Hồ sơ đã giải quyết hoặc khoản tiền không còn đang đối soát.");
        if(c.getAmountSnapshot().compareTo(p.getTotalAmount())!=0||!Objects.equals(c.getCurrency(),p.getCurrency()))
            throw notAllowed("Giá trị nghĩa vụ đã thay đổi; không áp dụng quyết định từ số liệu cũ.");
        return c;
    }

    private PaymentRequest lockPayment(String id,String actor,boolean administrator) {
        var before=payments.findById(id).orElseThrow(this::missing);
        if(!administrator)party(before,actor);
        if(PaymentRequestService.isOnboarding(before)&&before.getRentalRequestId()!=null) {
            var r=rentals.findById(before.getRentalRequestId()).orElseThrow(this::missing);em.refresh(r,LockModeType.PESSIMISTIC_WRITE);
        }
        var p=payments.findById(id).orElseThrow(this::missing);
        em.refresh(p,LockModeType.PESSIMISTIC_WRITE);
        if(!administrator)party(p,actor);
        return p;
    }

    private void validateRoute(DisputeCase c,PaymentRequest p,DisputeResolution resolution) {
        if(resolution==DisputeResolution.REVIEW_TRANSFER && !hasValidEvidence(c,p))
            throw notAllowed("Cần bổ sung chứng từ hợp lệ của khoản tiền trước khi mở bước xác nhận nhận tiền.");
        if(PaymentRequestService.isOnboarding(p)) {
            var r=rentals.findById(p.getRentalRequestId()).orElseThrow(this::missing);
            if(r.getStatus()!=RentalRequestStatus.ACCEPTED||r.getListing()==null||r.getListing().getOperationalStatus()!=ListingStatus.RESERVED
                    ||r.getCancellationRequestedAt()!=null||!Objects.equals(p.getPayerId(),r.getRenterId())||!Objects.equals(p.getPayeeId(),r.getOwnerId()))
                throw notAllowed("Hồ sơ không còn giữ chỗ hợp lệ hoặc đang chờ hủy. Không thể mở lại thanh toán/ký thuê từ đối soát này.");
        }
    }

    private void route(DisputeCase c,PaymentRequest p,DisputeResolution resolution,String actor,String reason,boolean administrator) {
        validateRoute(c,p,resolution);
        PaymentStatus next;
        com.hs.listing.model.RentalRequest extendedHold=null;
        if(resolution==DisputeResolution.REVIEW_TRANSFER) {
            next=PaymentStatus.TRANSFER_REPORTED;
            p.setConfirmationDueAt(time.now().plus(Duration.ofHours(confirmationHours)));
        } else {
            next=PaymentTimePolicy.usesBillingTime(p)&&p.getDueAt()!=null&&!time.now().isBefore(p.getDueAt())?PaymentStatus.OVERDUE:PaymentStatus.AWAITING_TRANSFER;
            if(PaymentRequestService.isOnboarding(p)) {
                Instant opens=p.getOpensAt()!=null&&p.getOpensAt().isAfter(time.now())?p.getOpensAt():time.now();
                p.setDueAt(opens.plus(Duration.ofHours(reopenHours)));next=PaymentStatus.AWAITING_TRANSFER;
                var r=rentals.findById(p.getRentalRequestId()).orElseThrow(this::missing);
                if(!Objects.equals(r.getHoldExpiresAt(),p.getDueAt()))extendedHold=r;
                r.setHoldExpiresAt(p.getDueAt());rentals.save(r);
            }
            p.setConfirmationDueAt(null);
        }
        // Only the refund workflow label changes. No held/retained/refundable amount is changed here.
        if(p.getType()==PaymentType.DEPOSIT_REFUND) {
            var d=deposits.findByRentalRequestIdForUpdate(p.getRentalRequestId()).orElseThrow(this::missing);em.refresh(d);
            if(!p.getId().equals(d.getRefundPaymentRequestId())||d.getStatus()!=DepositStatus.DISPUTED)
                throw notAllowed("Sổ cọc không còn khớp khoản hoàn đang đối soát.");
            d.setStatus(next==PaymentStatus.TRANSFER_REPORTED?DepositStatus.REFUND_REPORTED:DepositStatus.REFUND_DUE);deposits.save(d);
        }
        p.setStatus(next);payments.save(p);
        c.setStatus(DisputeCaseStatus.RESOLVED);c.setActiveKey(null);c.setResolvedAt(time.now());
        c.setResolvedBy(actor);c.setResolution(resolution);c.setResolutionReason(reason);
        paymentEvents.save(PaymentEvent.builder().paymentRequestId(p.getId()).eventType(PaymentEventType.DISPUTE_ROUTED)
                .fromStatus(PaymentStatus.DISPUTED).toStatus(next).actorId(actor).actorRole(administrator?"ADMIN":role(p,actor))
                .reason(reason).createdAt(time.now()).metadataJson(json(Map.of("caseId",c.getId(),"resolution",resolution.name(),
                        "previousStatus",Objects.toString(c.getPreviousPaymentStatus(),"UNKNOWN"),"receiptConfirmed",false))).build());
        // Only a real deadline change; the acting admin (or party) is not notified about their own action.
        if(extendedHold!=null)notifications.enqueueOthers(extendedHold,actor,Event.RENTAL_HOLD_EXTENDED);
    }

    private void attachEvidence(DisputeCase c,PaymentRequest p,String actor,String storageId,String reason) {
        if(storageId==null||storageId.isBlank())return;
        var file=storage.findById(storageId).orElseThrow(()->new AppException(PaymentErrorCode.PAYMENT_PROOF_INVALID));
        if(!Boolean.TRUE.equals(file.getActive())||!actor.equals(file.getOwnerId())||file.getStatus()!=StorageStatus.READY
                ||file.getPurpose()!=StoragePurpose.PAYMENT_PROOF||file.getVisibility()!=StorageVisibility.PRIVATE
                ||!"PAYMENT_REQUEST".equals(file.getReferenceType())||!p.getId().equals(file.getReferenceId()))
            throw new AppException(PaymentErrorCode.PAYMENT_PROOF_INVALID);
        boolean exists=evidence.findByPaymentRequestIdOrderByCreatedAtDesc(p.getId()).stream().anyMatch(e->storageId.equals(e.getStorageObjectId()));
        if(!exists)evidence.save(PaymentEvidence.builder().paymentRequestId(p.getId()).uploadedBy(actor).storageObjectId(storageId)
                .note(reason).build());
    }
    private Set<String> evidenceIds(DisputeCase c,List<DisputeCaseEvent> audit) {
        try {
            var ids=new HashSet<>(mapper.readValue(c.getInitialEvidenceSnapshot()==null?"[]":c.getInitialEvidenceSnapshot(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}));
            audit.stream().map(DisputeCaseEvent::getEvidenceStorageId).filter(Objects::nonNull).forEach(ids::add);
            return ids;
        } catch(Exception e){throw new IllegalStateException("Cannot read captured dispute evidence",e);}
    }
    private boolean hasValidEvidence(DisputeCase c,PaymentRequest p) {
        var ids=evidenceIds(c,events.findByCaseIdOrderByRevisionAsc(c.getId()));
        return evidence.findByPaymentRequestIdOrderByCreatedAtDesc(p.getId()).stream().filter(e->ids.contains(e.getStorageObjectId())).anyMatch(e->
            storage.findById(e.getStorageObjectId()).filter(f->Boolean.TRUE.equals(f.getActive())&&f.getStatus()==StorageStatus.READY
                &&f.getPurpose()==StoragePurpose.PAYMENT_PROOF&&f.getVisibility()==StorageVisibility.PRIVATE
                &&"PAYMENT_REQUEST".equals(f.getReferenceType())
                &&(p.getId().equals(f.getReferenceId())||p.getRentalRequestId()!=null&&p.getRentalRequestId().equals(f.getReferenceId()))
                &&Objects.equals(e.getUploadedBy(),f.getOwnerId())
                &&(p.getPayerId().equals(e.getUploadedBy())||p.getPayeeId().equals(e.getUploadedBy()))).isPresent());
    }

    private void finish(DisputeCase c,PaymentRequest p,String actor,boolean admin,String action,String reason,String file,Map<String,Object> metadata) {
        c.setRevision(c.getRevision()+1);cases.saveAndFlush(c);append(c,p,actor,admin,action,reason,file,metadata);
    }
    private void append(DisputeCase c,PaymentRequest p,String actor,boolean admin,String action,String reason,String file,Map<String,Object> metadata) {
        events.save(DisputeCaseEvent.builder().caseId(c.getId()).revision(c.getRevision()).action(action).actorId(actor)
                .actorRole(admin?"ADMIN":role(p,actor)).reason(reason).evidenceStorageId(file).metadataJson(json(metadata)).occurredAt(time.now()).build());
    }
    private DisputeCaseView view(DisputeCase c,PaymentRequest p,String actor,boolean administrator,boolean details) {
        List<DisputeCaseEvent> audit=details?events.findByCaseIdOrderByRevisionAsc(c.getId()):List.of();
        var capturedEvidence=details?evidenceIds(c,audit):Set.<String>of();
        var allowed=new ArrayList<String>();
        if(c.getStatus()!=DisputeCaseStatus.RESOLVED && p.getStatus()==PaymentStatus.DISPUTED) {
            if(administrator) {
                if(c.getStatus()==DisputeCaseStatus.ESCALATED&&!actor.equals(p.getPayerId())&&!actor.equals(p.getPayeeId())) {
                    allowed.add("CLAIM");if(actor.equals(c.getAssignedTo()))allowed.add("DECIDE");
                }
            } else {
                allowed.add("COMMENT");
                if(c.getStatus()==DisputeCaseStatus.OPEN) {
                    allowed.add("ESCALATE");
                    if(c.getProposedBy()==null)allowed.add("PROPOSE");
                    else if(!actor.equals(c.getProposedBy())){allowed.add("ACCEPT");allowed.add("DECLINE");}
                }
            }
        }
        return new DisputeCaseView(c.getId(),p.getId(),c.getRentalRequestId(),c.getContractId(),c.getPayerId(),c.getPayeeId(),c.getOpenedBy(),
                c.getReason(),c.getOpenedAt(),c.getStatus(),c.getPreviousPaymentStatus(),p.getStatus(),p.getType(),p.getDirection(),c.getAmountSnapshot(),c.getCurrency(),
                c.getRevision(),c.getProposedBy(),c.getProposedResolution(),c.getProposalReason(),c.getAssignedTo(),c.getResolvedBy(),c.getResolution(),c.getResolutionReason(),c.getResolvedAt(),allowed,
                audit.stream().map(e->new DisputeCaseView.Event(e.getRevision(),e.getAction(),e.getActorId(),e.getActorRole(),e.getReason(),e.getEvidenceStorageId(),e.getMetadataJson(),e.getOccurredAt())).toList(),
                details?evidence.findByPaymentRequestIdOrderByCreatedAtDesc(p.getId()).stream().filter(e->capturedEvidence.contains(e.getStorageObjectId())).map(e->new DisputeCaseView.Evidence(e.getStorageObjectId(),e.getUploadedBy(),e.getNote(),e.getCreatedAt())).toList():List.of());
    }
    private void admin(String actor) {
        var auth=SecurityContextHolder.getContext().getAuthentication();var ctx=UserContextHolder.get();
        if(actor==null||ctx==null||!actor.equals(ctx.userId())||auth==null||!auth.isAuthenticated()
                ||auth.getAuthorities().stream().noneMatch(a->"ADMIN".equals(a.getAuthority())))throw new AppException(PaymentErrorCode.PAYMENT_REQUEST_FORBIDDEN);
    }
    private void party(PaymentRequest p,String actor){if(actor==null||!actor.equals(p.getPayerId())&&!actor.equals(p.getPayeeId()))throw new AppException(PaymentErrorCode.PAYMENT_REQUEST_FORBIDDEN);}
    private String role(PaymentRequest p,String actor){return actor.equals(p.getPayerId())?(p.getDirection()==PaymentDirection.LANDLORD_TO_TENANT?"LANDLORD":"TENANT"):(p.getDirection()==PaymentDirection.LANDLORD_TO_TENANT?"TENANT":"LANDLORD");}
    private String key(String id){return "PAYMENT:"+id;}
    private void requireBilateral(DisputeCase c){if(c.getStatus()!=DisputeCaseStatus.OPEN)throw notAllowed("Hồ sơ đã chuyển quản trị viên; không tự đóng đề xuất cũ.");}
    private void requireOtherProposer(DisputeCase c,String actor){if(c.getProposedBy()==null||actor.equals(c.getProposedBy()))throw notAllowed("Đề xuất cần bên còn lại phản hồi.");}
    private void clearProposal(DisputeCase c){c.setProposalId(null);c.setProposedBy(null);c.setProposedResolution(null);c.setProposalReason(null);}
    private void requireResolution(DisputeResolution r){if(r==null)throw invalid("Chọn bước kiểm tra chứng từ hoặc mở lại báo chuyển tiền.");}
    private String text(String s,int min){if(s==null||s.trim().length()<min||s.trim().length()>500)throw invalid("Nội dung cần từ "+min+" đến 500 ký tự.");return s.trim();}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot write dispute audit",e);}}
    private AppException missing(){return new AppException(PaymentErrorCode.PAYMENT_REQUEST_NOT_FOUND);}
    private AppException caseMissing(){return new AppException(PaymentErrorCode.DISPUTE_CASE_NOT_FOUND);}
    private AppException invalid(String reason){return new AppException(PaymentErrorCode.DISPUTE_CASE_INVALID,reason);}
    private AppException notAllowed(String reason){return new AppException(PaymentErrorCode.DISPUTE_CASE_ACTION_NOT_ALLOWED,reason);}
}
