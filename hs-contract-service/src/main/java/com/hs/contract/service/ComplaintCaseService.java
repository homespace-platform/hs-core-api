package com.hs.contract.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.PageResponse;
import com.hs.common.time.BillingTime;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.dto.complaint.*;
import com.hs.contract.dto.billing.ClosureEvent;
import com.hs.contract.event.ClosureDisputed;
import com.hs.contract.model.*;
import com.hs.contract.repository.*;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.dto.DisputeCaseView;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.*;
import com.hs.storage.model.constant.*;
import com.hs.storage.repository.StorageObjectRepository;
import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Shares cases/audit with payment disputes; never turns a finding into a receipt or unilateral forfeiture. */
@Service @RequiredArgsConstructor
public class ComplaintCaseService {
    private final DisputeCaseRepository cases;
    private final DisputeCaseEventRepository events;
    private final RentalRequestRepository rentals;
    private final ContractRepository contracts;
    private final ContractClosureRepository closures;
    private final StorageObjectRepository storage;
    private final ContractLifecycleService lifecycle;
    private final ObjectMapper mapper;
    private final BillingTime time;
    @PersistenceContext private EntityManager em;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private RentalWorkflowRecordRepository workflows;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.hs.contract.repository.WorkflowSigningDocumentRepository workflowDocuments;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.hs.contract.repository.ContractDocumentRepository contractDocuments;
    @Value("${homespace.complaint.response-hours:48}") private long responseHours=48;

    @EventListener @Transactional(propagation=Propagation.MANDATORY)
    public void onClosureDisputed(ClosureDisputed event){
        // Source is already locked and changed by lifecycle in this transaction; do not refresh unflushed data.
        var c=contracts.findById(event.contractId()).orElseThrow(this::missing);
        var r=rentals.findById(c.getRentalRequestId()).orElseThrow(this::missing);party(r,event.actorId());
        var close=closures.findById(c.getId()).orElseThrow(this::missing);
        if(!"DISPUTED".equals(close.getStatus()))throw forbidden("Quyết toán chưa ở trạng thái phản đối.");
        var active=cases.findByActiveKey(key(r.getId(),ComplaintCategory.DEPOSIT_SETTLEMENT));
        if(active.isPresent()){
            var d=active.get();em.refresh(d,LockModeType.PESSIMISTIC_WRITE);party(d,event.actorId());
            var metadata=new LinkedHashMap<String,Object>();metadata.put("evidenceIds",closureEvidence(close));
            metadata.put("previousDecision",Objects.toString(d.getComplaintDecision(),""));metadata.put("proposalVersion",close.getProposalVersion());
            d.setSourceVersion((long)close.getProposalVersion());d.setComplaintOutcome(null);d.setComplaintDecision(null);clearProposal(d);
            d.setTenantCompletedAt(null);d.setLandlordCompletedAt(null);
            d.setComplaintStage(d.getStatus()==DisputeCaseStatus.ESCALATED?"UNDER_REVIEW":"WAITING_RESPONSE");
            d.setResponseDueFor(d.getStatus()==DisputeCaseStatus.ESCALATED?"ADMIN":other(d,event.actorId()));d.setRespondBy(deadline());
            finish(d,event.actorId(),role(d,event.actorId()),"SOURCE_DISPUTED",text(event.reason(),2000),metadata);return;
        }
        register(r,c,event.actorId(),ComplaintCategory.DEPOSIT_SETTLEMENT,event.reason(),closureEvidence(close),false,close);
    }

    @Transactional public ComplaintView open(String rentalId,String actor,ComplaintOpen request){
        var r=rentals.findById(rentalId).orElseThrow(this::missing);party(r,actor);
        var root=lockSource(rentalId,null);r=rentals.findById(rentalId).orElseThrow(this::missing);party(r,actor);
        if(request==null||request.category()==null)throw invalid("Chọn loại khiếu nại.");
        String reason=text(request.reason(),500);
        if(cases.findByActiveKey(key(rentalId,request.category())).isPresent())throw forbidden("Đã có hồ sơ đang mở cùng nhóm vấn đề. Hãy phản hồi vào hồ sơ đó.");
        if(request.urgent()&&!Set.of(ComplaintCategory.SAFETY,ComplaintCategory.HANDOVER).contains(request.category()))
            throw invalid("Chuyển cấp khẩn dùng cho an toàn hoặc sự cố bàn giao; các vấn đề khác phản hồi hai bên trước.");
        ContractClosure close=null;var evidence=new LinkedHashSet<>(validateFiles(r,actor,request.evidenceIds()));
        if(request.category()==ComplaintCategory.DEPOSIT_SETTLEMENT){
            if(root==null)throw forbidden("Chưa có hợp đồng/quyết toán để khiếu nại.");
            close=closures.findById(root.getId()).orElseThrow(this::missing);
            if(!"DISPUTED".equals(close.getStatus())||close.getAcceptedAt()!=null)throw forbidden("Hãy phản đối đúng phiên bản đề xuất quyết toán trước khi tiếp nhận hồ sơ.");
            evidence.addAll(closureEvidence(close));
        }
        return view(register(r,root,actor,request.category(),reason,List.copyOf(evidence),request.urgent(),close),actor,false,true);
    }

    /** Trusted bridge: rereads the reviewed aggregate, not uploader IDs or snapshots supplied by a client. */
    @Transactional public ComplaintView openFromWorkflow(String rentalId,String actor,String kind,long revision,String reason){
        var r=rentals.findById(rentalId).orElseThrow(this::missing);party(r,actor);var root=lockSource(rentalId,null);
        if(workflows==null)throw forbidden("Chưa có nguồn biên bản/thỏa thuận.");
        var source=workflows.findById(rentalId+":"+kind).orElseThrow(this::missing);
        if(!rentalId.equals(source.getRentalRequestId())||source.getRevision()!=revision)throw new AppException(ContractErrorCode.COMPLAINT_STALE);
        ComplaintCategory category=switch(kind){case "MOVE_IN"->ComplaintCategory.HANDOVER;case "CANCELLATION","EARLY_EXIT"->ComplaintCategory.TERMINATION;default->throw invalid("Nguồn khiếu nại không hợp lệ.");};
        List<com.hs.contract.dto.billing.RentalWorkflowView.Event> history;
        Map<String,Object> proposal;
        try{history=mapper.readValue(Objects.toString(source.getHistoryJson(),"[]"),new TypeReference<>(){});proposal=mapper.readValue(Objects.toString(source.getPayloadJson(),"{}"),new TypeReference<>(){});}
        catch(Exception e){throw new IllegalStateException("Cannot capture workflow audit",e);}
        var evidence=new LinkedHashSet<String>();for(var e:history)if(e.evidenceIds()!=null)evidence.addAll(e.evidenceIds());
        for(String id:evidence){var file=storage.findById(id).orElseThrow(this::missing);
            if(!Boolean.TRUE.equals(file.getActive())||file.getStatus()!=StorageStatus.READY||file.getPurpose()!=StoragePurpose.GENERAL||file.getVisibility()!=StorageVisibility.PRIVATE
                ||!"RENTAL_COMPLAINT_EVIDENCE".equals(file.getReferenceType())||!rentalId.equals(file.getReferenceId())
                ||!Set.of(r.getOwnerId(),r.getRenterId()).contains(file.getOwnerId()))throw forbidden("Chứng cứ nguồn không còn hợp lệ hoặc không thuộc hồ sơ.");}
        Map<String,Object> calculation;try{calculation=mapper.readValue(Objects.toString(source.getCalculationJson(),"{}"),new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException("Cannot capture final-rent calculation",e);}
        var pdfs=new ArrayList<ComplaintWorkflowSource.Document>();
        if(workflowDocuments!=null&&contractDocuments!=null)workflowDocuments.findByWorkflowIdAndContentHash(source.getId(),WorkflowDocumentService.hash(source)).ifPresent(binding->{
            var envelope=contracts.findById(binding.getSigningContractId()).orElseThrow(this::missing);
            if(!Objects.equals(source.getContractId(),envelope.getParentContractId())||!Objects.equals(rentalId,envelope.getRentalRequestId()))throw forbidden("Văn bản nguồn không khớp hồ sơ khiếu nại.");
            for(var document:contractDocuments.findByContractIdAndRevisionId(envelope.getId(),envelope.getCurrentRevisionId())){
                if(document.getStatus()!=com.hs.contract.model.constant.DocumentGenerationStatus.READY||document.getPurpose()==com.hs.contract.model.constant.DocumentPurpose.PREVIEW)continue;
                var file=storage.findById(document.getStorageObjectId()).orElseThrow(this::missing);
                if(!Boolean.TRUE.equals(file.getActive())||file.getStatus()!=StorageStatus.READY||file.getPurpose()!=StoragePurpose.CONTRACT_DOCUMENT||file.getVisibility()!=StorageVisibility.PRIVATE
                    ||!"CONTRACT".equals(file.getReferenceType())||!envelope.getId().equals(file.getReferenceId()))throw forbidden("PDF căn cứ không còn hợp lệ.");
                evidence.add(file.getId());pdfs.add(new ComplaintWorkflowSource.Document(file.getId(),envelope.getId(),document.getPurpose().name(),binding.getSourceRevision(),binding.getSignedAt()));
            }
        });
        var snapshot=new ComplaintWorkflowSource(source.getId(),source.getKind(),source.getRevision(),source.getContractId(),source.getSourceRevisionId(),source.getStatus(),source.getProposedBy(),source.getOwnerConfirmedAt(),source.getTenantConfirmedAt(),time.now(),proposal,List.copyOf(history),List.copyOf(evidence),calculation,WorkflowDocumentService.hash(source),List.copyOf(pdfs));
        var active=cases.findByActiveKey(key(rentalId,category));DisputeCase d;
        if(active.isPresent())d=lockCase(active.get().getId(),null);
        else d=register(r,root,actor,category,text(reason,1000),List.copyOf(evidence),false,null);
        party(d,actor);
        var metadata=new LinkedHashMap<String,Object>();metadata.put("workflowSource",snapshot);metadata.put("evidenceIds",List.copyOf(evidence));
        finish(d,actor,role(d,actor),"SOURCE_ATTACHED",text(reason,1000),metadata);
        return view(d,actor,false,true);
    }

    private List<ComplaintWorkflowSource> workflowSources(List<DisputeCaseEvent> history){
        var result=new ArrayList<ComplaintWorkflowSource>();
        try{for(var e:history)if("SOURCE_ATTACHED".equals(e.getAction())){
            var metadata=mapper.readTree(Objects.toString(e.getMetadataJson(),"{}"));
            if(metadata.has("workflowSource"))result.add(mapper.treeToValue(metadata.get("workflowSource"),ComplaintWorkflowSource.class));
        }}catch(Exception e){throw new IllegalStateException("Cannot read complaint source audit",e);}return List.copyOf(result);
    }

    private DisputeCase register(RentalRequest r,Contract contract,String actor,ComplaintCategory category,String reason,List<String> evidence,boolean urgent,ContractClosure close){
        if(r.getOwnerId().equals(r.getRenterId())||contract!=null&&(!r.getOwnerId().equals(contract.getLandlordId())||!r.getRenterId().equals(contract.getTenantId())))throw forbidden("Hai bên của hồ sơ nguồn không khớp.");
        var d=DisputeCase.builder().subjectType("RENTAL").subjectId(r.getId()).rentalRequestId(r.getId())
            .contractId(contract==null?null:contract.getId()).payerId(r.getRenterId()).payeeId(r.getOwnerId())
            .activeKey(key(r.getId(),category)).openedBy(actor).openedAt(time.now()).reason(text(reason,2000))
            .status(urgent?DisputeCaseStatus.ESCALATED:DisputeCaseStatus.OPEN).complaintCategory(category.name())
            .complaintStage(urgent?"WAITING_ADMIN":"WAITING_RESPONSE").respondBy(deadline()).responseDueFor(urgent?"ADMIN":other(r,actor))
            .escalatedAt(urgent?time.now():null).sourceVersion(close==null?null:(long)close.getProposalVersion())
            .amountSnapshot(BigDecimal.ZERO).currency("VND").initialEvidenceSnapshot(json(evidence)).revision(1).complaintRoundOpenedBy(actor).complaintRoundStartRevision(1L).build();
        cases.saveAndFlush(d);audit(d,actor,role(d,actor),"OPENED",d.getReason(),null,Map.of("urgent",urgent,"evidenceIds",evidence));return d;
    }

    @Transactional public List<ComplaintView> forRental(String rentalId,String actor){
        var r=rentals.findById(rentalId).orElseThrow(this::missing);party(r,actor);
        var visible=new LinkedHashMap<String,DisputeCase>();
        cases.findBySubjectTypeAndRentalRequestIdAndStatusNotOrderByOpenedAtDesc("RENTAL",rentalId,DisputeCaseStatus.RESOLVED).forEach(d->visible.put(d.getId(),d));
        cases.findBySubjectTypeAndRentalRequestIdOrderByOpenedAtDesc("RENTAL",rentalId,PageRequest.of(0,50)).forEach(d->visible.putIfAbsent(d.getId(),d));
        return visible.values().stream().map(d->get(d.getId(),actor)).toList();
    }
    @Transactional public ComplaintView get(String id,String actor){
        var before=complaint(id);party(before,actor);lockSource(before.getRentalRequestId(),before.getContractId());
        var d=lockCase(id,null);party(d,actor);syncFollowUp(d);return view(d,actor,false,true);
    }
    @Transactional public ComplaintView act(String id,String actor,String action,ComplaintAction p){
        if(p==null)throw invalid("Thiếu nội dung thao tác.");
        var before=complaint(id);party(before,actor);lockSource(before.getRentalRequestId(),before.getContractId());
        var d=lockCase(id,p);party(d,actor);String reason=text(p.reason(),500);var metadata=new LinkedHashMap<String,Object>();
        if(!"COMMENT".equals(action)&&p.evidenceIds()!=null&&!p.evidenceIds().isEmpty())throw invalid("Bổ sung chứng cứ bằng phản hồi riêng.");
        if(d.getStatus()==DisputeCaseStatus.RESOLVED&& !"REOPEN".equals(action))throw forbidden("Hồ sơ đã đóng; cần mở lại có lý do.");
        switch(action){
            case "COMMENT" -> {
                var r=rentals.findById(d.getRentalRequestId()).orElseThrow(this::missing);
                metadata.put("evidenceIds",validateFiles(r,actor,p.evidenceIds()));
                if(actor.equals(d.getResponseDueFor())&&Set.of("WAITING_RESPONSE","PROPOSAL_PENDING","WAITING_INFO").contains(d.getComplaintStage())){
                    d.setResponseDueFor(d.getStatus()==DisputeCaseStatus.ESCALATED?"ADMIN":null);
                    d.setRespondBy(d.getStatus()==DisputeCaseStatus.ESCALATED?deadline():null);
                    d.setComplaintStage(d.getStatus()==DisputeCaseStatus.ESCALATED?"UNDER_REVIEW":"DISCUSSING");
                }
            }
            case "PROPOSE" -> {
                bilateral(d);if(d.getProposedBy()!=null)throw forbidden("Phản hồi đề xuất đang chờ trước.");
                d.setProposedBy(actor);d.setProposalReason(reason);d.setComplaintProposal(json(Map.of("agreement",reason)));
                d.setComplaintStage("PROPOSAL_PENDING");d.setResponseDueFor(other(d,actor));d.setRespondBy(deadline());
            }
            case "ACCEPT" -> {
                bilateral(d);otherProposer(d,actor);metadata.put("agreement",d.getProposalReason());metadata.put("proposedBy",d.getProposedBy());
                resolve(d,actor,reason,"AGREED");
            }
            case "DECLINE" -> {
                bilateral(d);otherProposer(d,actor);metadata.put("agreement",d.getProposalReason());clearProposal(d);
                d.setComplaintStage("DISCUSSING");d.setResponseDueFor(null);d.setRespondBy(null);
            }
            case "ESCALATE" -> {
                bilateral(d);
                if(!discussed(d,actor)&&!overdue(d))throw forbidden("Chờ bên còn lại phản hồi hoặc hết thời hạn theo cấu hình; có thể chuyển khi hai bên không thống nhất.");
                d.setStatus(DisputeCaseStatus.ESCALATED);d.setComplaintStage("WAITING_ADMIN");d.setEscalatedAt(time.now());d.setRespondBy(deadline());d.setResponseDueFor("ADMIN");clearProposal(d);
            }
            case "WITHDRAW" -> {bilateral(d);if(!actor.equals(roundOpener(d)))throw forbidden("Chỉ người mở lượt khiếu nại này được rút trước khi chuyển admin.");resolve(d,actor,reason,"WITHDRAWN");}
            case "REOPEN" -> {
                if(d.getStatus()!=DisputeCaseStatus.RESOLVED)throw forbidden("Hồ sơ còn đang mở.");
                if(cases.findByActiveKey(key(d)).isPresent())throw forbidden("Đã có một hồ sơ đang mở cùng vấn đề.");
                metadata.put("previousDecision",Objects.toString(d.getComplaintDecision(),""));
                d.setStatus(DisputeCaseStatus.OPEN);d.setActiveKey(key(d));d.setComplaintStage("WAITING_RESPONSE");
                d.setResolvedBy(null);d.setResolutionReason(null);d.setResolvedAt(null);d.setAssignedTo(null);d.setComplaintOutcome(null);d.setComplaintDecision(null);
                d.setTenantCompletedAt(null);d.setLandlordCompletedAt(null);d.setRespondBy(deadline());d.setResponseDueFor(other(d,actor));clearProposal(d);
                d.setComplaintRoundOpenedBy(actor);d.setComplaintRoundStartRevision(d.getRevision()+1);
                if(category(d)==ComplaintCategory.DEPOSIT_SETTLEMENT&&d.getContractId()!=null)closures.findById(d.getContractId()).ifPresent(c->d.setSourceVersion((long)c.getProposalVersion()));
            }
            case "APPROVE_SETTLEMENT" -> {
                if(!actor.equals(d.getPayeeId())||!"FOLLOW_UP".equals(d.getComplaintStage())||!"REVISE_SETTLEMENT".equals(d.getComplaintOutcome()))throw forbidden("Chủ nhà chỉ đồng ý đề xuất quyết toán đang theo dõi.");
                lifecycle.approveComplaintSettlement(d.getContractId(),d.getId(),Math.toIntExact(d.getSourceVersion()),actor);
                d.setResponseDueFor(d.getPayerId());d.setRespondBy(deadline());
            }
            case "CONFIRM_COMPLETE" -> {
                if(!"FOLLOW_UP".equals(d.getComplaintStage())||!"RECORD_FINDING".equals(d.getComplaintOutcome()))throw forbidden("Nghĩa vụ tiền phải xác nhận tại mục thanh toán.");
                if(actor.equals(d.getPayerId())){if(d.getTenantCompletedAt()!=null)throw forbidden("Bạn đã xác nhận.");d.setTenantCompletedAt(time.now());}
                else {if(d.getLandlordCompletedAt()!=null)throw forbidden("Bạn đã xác nhận.");d.setLandlordCompletedAt(time.now());}
                if(d.getTenantCompletedAt()!=null&&d.getLandlordCompletedAt()!=null)resolve(d,actor,reason,"CLOSED");
            }
            case "REJECT_DECISION" -> {
                if(d.getStatus()!=DisputeCaseStatus.ESCALATED||!"FOLLOW_UP".equals(d.getComplaintStage()))throw forbidden("Chưa có kết luận đang theo dõi.");
                if(settlementAccepted(d))throw forbidden("Quyết toán đã được chấp thuận; không đảo nghĩa vụ tiền qua thao tác này. Đối soát khoản thanh toán tại hồ sơ tiền.");
                if(d.getContractId()!=null&&category(d)==ComplaintCategory.DEPOSIT_SETTLEMENT)
                    closures.findById(d.getContractId()).filter(c->d.getId().equals(c.getComplaintCaseId())&&c.getAcceptedAt()==null)
                        .ifPresent(c->{c.setComplaintOwnerApprovedAt(null);closures.save(c);});
                metadata.put("previousDecision",Objects.toString(d.getComplaintDecision(),""));
                d.setComplaintStage("UNDER_REVIEW");d.setComplaintOutcome(null);d.setComplaintDecision(null);d.setRespondBy(deadline());d.setResponseDueFor("ADMIN");
                d.setTenantCompletedAt(null);d.setLandlordCompletedAt(null);
            }
            default -> throw invalid("Thao tác khiếu nại không được hỗ trợ.");
        }
        finish(d,actor,role(d,actor),action,reason,metadata);return view(d,actor,false,true);
    }

    @Transactional(readOnly=true) public PageResponse<ComplaintView> adminList(String actor,DisputeCaseStatus status,ComplaintCategory category,Boolean overdue,int page,int size){
        admin(actor);
        Specification<DisputeCase> spec=(root,q,cb)->{
            var filters=new ArrayList<jakarta.persistence.criteria.Predicate>();filters.add(cb.equal(root.get("subjectType"),"RENTAL"));
            if(status!=null)filters.add(cb.equal(root.get("status"),status));if(category!=null)filters.add(cb.equal(root.get("complaintCategory"),category.name()));
            if(Boolean.TRUE.equals(overdue)){filters.add(cb.notEqual(root.get("status"),DisputeCaseStatus.RESOLVED));filters.add(cb.lessThanOrEqualTo(root.get("respondBy"),time.now()));}
            return cb.and(filters.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return new PageResponse<>(cases.findAll(spec,PageRequest.of(Math.max(0,page-1),Math.min(50,Math.max(1,size)),Sort.by("openedAt","id")))
            .map(d->view(d,actor,true,false)));
    }
    @Transactional public ComplaintView adminGet(String id,String actor){admin(actor);var before=complaint(id);lockSource(before.getRentalRequestId(),before.getContractId());var d=lockCase(id,null);syncFollowUp(d);return view(d,actor,true,true);}
    @Transactional public ComplaintView adminAct(String id,String actor,String action,ComplaintAction p){
        if(p==null)throw invalid("Thiếu nội dung thao tác.");
        admin(actor);var before=complaint(id);lockSource(before.getRentalRequestId(),before.getContractId());var d=lockCase(id,p);
        if(actor.equals(d.getPayerId())||actor.equals(d.getPayeeId()))throw forbidden("Admin không tự phân xử vụ việc của mình.");
        if(d.getStatus()!=DisputeCaseStatus.ESCALATED)throw forbidden("Chỉ tiếp nhận hồ sơ đã chuyển HomeSpace.");
        if(p.evidenceIds()!=null&&!p.evidenceIds().isEmpty())throw invalid("Admin không thay chứng cứ hai bên bằng file quyết định.");
        String reason=text(p.reason(),500);var metadata=new LinkedHashMap<String,Object>();
        if("CLAIM".equals(action)){
            metadata.put("previousAssignee",Objects.toString(d.getAssignedTo(),""));d.setAssignedTo(actor);
            if(!"FOLLOW_UP".equals(d.getComplaintStage())){d.setComplaintStage("UNDER_REVIEW");d.setResponseDueFor("ADMIN");d.setRespondBy(deadline());}
        } else {
            if(!actor.equals(d.getAssignedTo()))throw forbidden("Tiếp nhận hồ sơ trước khi xử lý.");
            switch(action){
                case "REQUEST_INFO" -> {
                    if(p.requestedFrom()==null||!Set.of(d.getPayerId(),d.getPayeeId()).contains(p.requestedFrom()))throw invalid("Chọn một trong hai bên để yêu cầu bổ sung.");
                    if("FOLLOW_UP".equals(d.getComplaintStage()))throw forbidden("Hãy ghi nhận cần xem xét lại kết luận trước.");
                    d.setComplaintStage("WAITING_INFO");d.setResponseDueFor(p.requestedFrom());d.setRespondBy(deadline());metadata.put("requestedFrom",p.requestedFrom());
                }
                case "DECIDE" -> {
                    if(!"UNDER_REVIEW".equals(d.getComplaintStage()))throw forbidden("Đọc phản hồi và tiếp nhận xử lý trước khi kết luận.");
                    if(p.outcome()==null||!Set.of("RECORD_FINDING","REVISE_SETTLEMENT").contains(p.outcome()))throw invalid("Chọn hướng giải quyết.");
                    if("REVISE_SETTLEMENT".equals(p.outcome())){
                        if(category(d)!=ComplaintCategory.DEPOSIT_SETTLEMENT||d.getContractId()==null||p.settlement()==null)throw invalid("Chỉ lập lại đề xuất từ khiếu nại quyết toán cọc.");
                        var response=lifecycle.proposeComplaintSettlement(d.getContractId(),id,actor,p.settlement());
                        d.setSourceVersion((long)response.closure().getProposalVersion());d.setResponseDueFor(d.getPayeeId());
                        metadata.put("settlement",p.settlement());metadata.put("proposalVersion",d.getSourceVersion());
                    } else {if(p.settlement()!=null)throw invalid("Kết luận thông thường không sửa số tiền.");d.setResponseDueFor(null);}
                    d.setComplaintOutcome(p.outcome());d.setComplaintDecision(reason);d.setComplaintStage("FOLLOW_UP");d.setRespondBy(deadline());
                    d.setTenantCompletedAt(null);d.setLandlordCompletedAt(null);metadata.put("outcome",p.outcome());
                }
                case "CLOSE" -> {
                    if(!"FOLLOW_UP".equals(d.getComplaintStage())||!"RECORD_FINDING".equals(d.getComplaintOutcome()))throw forbidden("Khoản quyết toán chỉ đóng khi nghĩa vụ thực tế đã hoàn tất.");
                    resolve(d,actor,reason,"CLOSED");
                }
                default -> throw invalid("Thao tác quản trị không được hỗ trợ.");
            }
        }
        finish(d,actor,"ADMIN",action,reason,metadata);return view(d,actor,true,true);
    }

    private Contract lockSource(String rentalId,String contractId){
        var root=contractId==null?contracts.findByRentalRequestId(rentalId).orElse(null):contracts.findById(contractId).orElseThrow(this::missing);
        if(root==null){
            var r=rentals.findById(rentalId).orElseThrow(this::missing);em.refresh(r,LockModeType.PESSIMISTIC_WRITE);
            // A contract may have been committed while this request was waiting for the rental lock.
            root=contracts.findByRentalRequestId(rentalId).orElse(null);
        }
        if(root!=null){em.refresh(root,LockModeType.PESSIMISTIC_WRITE);closures.findById(root.getId()).ifPresent(em::refresh);if(!rentalId.equals(root.getRentalRequestId())||root.getParentContractId()!=null||"DEPOSIT".equals(root.getAgreementKind()))throw forbidden("Hồ sơ nguồn đã thay đổi.");}
        return root;
    }
    private DisputeCase lockCase(String id,ComplaintAction p){
        var d=complaint(id);em.refresh(d,LockModeType.PESSIMISTIC_WRITE);
        if(p!=null&&(p.expectedRevision()==null||p.expectedRevision()!=d.getRevision()))throw new AppException(ContractErrorCode.COMPLAINT_STALE);
        var r=rentals.findById(d.getRentalRequestId()).orElseThrow(this::missing);
        if(!r.getRenterId().equals(d.getPayerId())||!r.getOwnerId().equals(d.getPayeeId()))throw forbidden("Các bên của hồ sơ nguồn đã thay đổi.");return d;
    }
    private void syncFollowUp(DisputeCase d){
        if(d.getStatus()!=DisputeCaseStatus.ESCALATED||!"FOLLOW_UP".equals(d.getComplaintStage())||!"REVISE_SETTLEMENT".equals(d.getComplaintOutcome()))return;
        var c=contracts.findById(d.getContractId()).orElseThrow(this::missing);lifecycle.syncComplaintSource(c);
        var close=closures.findById(c.getId()).orElseThrow(this::missing);
        if(!d.getId().equals(close.getComplaintCaseId())||d.getSourceVersion()==null||d.getSourceVersion()!=close.getProposalVersion())return;
        if("CLOSED".equals(close.getStatus())){resolve(d,"SYSTEM","Nghĩa vụ quyết toán đã hoàn tất qua luồng xác nhận thực nhận.","CLOSED");finish(d,"SYSTEM","SYSTEM","COMPLETED",d.getResolutionReason(),Map.of("proposalVersion",close.getProposalVersion()));}
        else if(close.getAcceptedAt()!=null&&!Objects.equals(d.getRespondBy(),close.getRefundDueAt())){d.setRespondBy(close.getRefundDueAt());d.setResponseDueFor(close.getRefundPaymentId()!=null?d.getPayeeId():d.getPayerId());finish(d,"SYSTEM","SYSTEM","FOLLOW_UP_PAYMENT","Hai bên đã đồng ý quyết toán; tiếp tục nghĩa vụ thanh toán, chưa coi là đã nhận tiền.",Map.of("proposalVersion",close.getProposalVersion()));}
    }
    private boolean settlementAccepted(DisputeCase d){return "REVISE_SETTLEMENT".equals(d.getComplaintOutcome())&&d.getContractId()!=null&&closures.findById(d.getContractId()).filter(c->c.getAcceptedAt()!=null).isPresent();}
    private void resolve(DisputeCase d,String actor,String reason,String stage){d.setStatus(DisputeCaseStatus.RESOLVED);d.setComplaintStage(stage);d.setActiveKey(null);d.setResolvedAt(time.now());d.setResolvedBy(actor);d.setResolutionReason(reason);d.setRespondBy(null);d.setResponseDueFor(null);clearProposal(d);}
    private void clearProposal(DisputeCase d){d.setProposedBy(null);d.setProposalReason(null);d.setComplaintProposal(null);}
    private void finish(DisputeCase d,String actor,String role,String action,String reason,Map<String,Object> metadata){d.setRevision(d.getRevision()+1);cases.saveAndFlush(d);audit(d,actor,role,action,reason,null,metadata);}
    private void audit(DisputeCase d,String actor,String role,String action,String reason,String file,Map<String,Object> metadata){events.save(DisputeCaseEvent.builder().caseId(d.getId()).revision(d.getRevision()).actorId(actor).actorRole(role).action(action).reason(reason).evidenceStorageId(file).metadataJson(json(metadata)).occurredAt(time.now()).build());}
    private ComplaintView view(DisputeCase d,String actor,boolean administrator,boolean details){
        var allowed=new ArrayList<String>();
        if(d.getStatus()==DisputeCaseStatus.RESOLVED){if(!administrator)allowed.add("REOPEN");}
        else if(administrator){if(d.getStatus()==DisputeCaseStatus.ESCALATED&&!actor.equals(d.getPayerId())&&!actor.equals(d.getPayeeId())){
            allowed.add("CLAIM");if(actor.equals(d.getAssignedTo())){if(!"FOLLOW_UP".equals(d.getComplaintStage())){allowed.add("REQUEST_INFO");if("UNDER_REVIEW".equals(d.getComplaintStage()))allowed.add("DECIDE");}else if("RECORD_FINDING".equals(d.getComplaintOutcome()))allowed.add("CLOSE");}
        }} else {
            allowed.add("COMMENT");
            if(d.getStatus()==DisputeCaseStatus.OPEN){if(actor.equals(roundOpener(d)))allowed.add("WITHDRAW");if(d.getProposedBy()==null)allowed.add("PROPOSE");else if(!actor.equals(d.getProposedBy())){allowed.add("ACCEPT");allowed.add("DECLINE");}
                if(overdue(d)||discussed(d,actor))allowed.add("ESCALATE");
            } else if("FOLLOW_UP".equals(d.getComplaintStage())){
                if(!settlementAccepted(d))allowed.add("REJECT_DECISION");
                if("RECORD_FINDING".equals(d.getComplaintOutcome())&&(actor.equals(d.getPayerId())?d.getTenantCompletedAt()==null:d.getLandlordCompletedAt()==null))allowed.add("CONFIRM_COMPLETE");
                if("REVISE_SETTLEMENT".equals(d.getComplaintOutcome())&&actor.equals(d.getPayeeId())&&d.getContractId()!=null&&closures.findById(d.getContractId()).filter(c->c.getComplaintOwnerApprovedAt()==null&&c.getAcceptedAt()==null).isPresent())allowed.add("APPROVE_SETTLEMENT");
            }
        }
        var history=details?events.findByCaseIdOrderByRevisionAsc(d.getId()):List.<DisputeCaseEvent>of();
        return new ComplaintView(d.getId(),d.getRentalRequestId(),d.getContractId(),d.getPayerId(),d.getPayeeId(),category(d),d.getReason(),d.getOpenedBy(),d.getOpenedAt(),d.getStatus(),d.getComplaintStage(),d.getRevision(),d.getRespondBy(),d.getResponseDueFor(),overdue(d),d.getAssignedTo(),d.getComplaintOutcome(),d.getComplaintDecision(),d.getProposedBy(),d.getProposalReason(),d.getSourceVersion(),d.getTenantCompletedAt()!=null,d.getLandlordCompletedAt()!=null,allowed,
            history.stream().map(e->new DisputeCaseView.Event(e.getRevision(),e.getAction(),e.getActorId(),e.getActorRole(),e.getReason(),e.getEvidenceStorageId(),e.getMetadataJson(),e.getOccurredAt())).toList(),details?attachedEvidence(d,history):List.of(),
            details&&category(d)==ComplaintCategory.DEPOSIT_SETTLEMENT&&d.getContractId()!=null?closures.findById(d.getContractId()).map(c->new ComplaintView.Settlement(c.getProposalVersion(),c.getStatus(),c.getOtherDeduction(),c.getRefundAmount(),c.getBalanceAmount(),c.getNote(),c.getComplaintOwnerApprovedAt()!=null,c.getAcceptedAt()!=null)).orElse(null):null,d.getResolutionReason(),details?workflowSources(history):List.of());
    }
    public List<String> attachedEvidence(DisputeCase d,List<DisputeCaseEvent> history){
        return ComplaintEvidenceSnapshot.ids(mapper,d,history);
    }
    private List<String> validateFiles(RentalRequest r,String actor,List<String> ids){
        if(ids==null)return List.of();if(ids.size()>5||new HashSet<>(ids).size()!=ids.size())throw invalid("Tối đa 5 chứng cứ khác nhau mỗi phản hồi.");
        for(String id:ids){if(id==null)throw invalid("Chứng cứ không hợp lệ.");var f=storage.findById(id).orElseThrow(this::missing);
            if(!Boolean.TRUE.equals(f.getActive())||!actor.equals(f.getOwnerId())||f.getStatus()!=StorageStatus.READY||f.getVisibility()!=StorageVisibility.PRIVATE||f.getPurpose()!=StoragePurpose.GENERAL
                ||!"RENTAL_COMPLAINT_EVIDENCE".equals(f.getReferenceType())||!r.getId().equals(f.getReferenceId())||f.getSizeBytes()==null||f.getSizeBytes()<=0||f.getSizeBytes()>15*1024*1024||!Set.of("image/jpeg","image/png","image/webp","application/pdf").contains(Objects.toString(f.getContentType(),"")))throw forbidden("Chứng cứ phải riêng tư, đã tải xong, thuộc bạn và đúng hồ sơ thuê.");
        }return List.copyOf(ids);
    }
    private List<String> closureEvidence(ContractClosure close){
        try{List<ClosureEvent> history=mapper.readValue(Objects.toString(close.getHistorySnapshot(),"[]"),new TypeReference<>(){});var ids=new LinkedHashSet<String>();for(var e:history){if(e.evidenceIds()!=null)ids.addAll(e.evidenceIds());if(e.deductions()!=null)e.deductions().forEach(row->{if(row.evidenceIds()!=null)ids.addAll(row.evidenceIds());});}return List.copyOf(ids);}catch(Exception e){throw new IllegalStateException("Cannot capture closure evidence",e);}
    }
    private DisputeCase complaint(String id){return cases.findById(id).filter(d->"RENTAL".equals(d.getSubjectType())).orElseThrow(this::missing);}
    private ComplaintCategory category(DisputeCase d){return ComplaintCategory.valueOf(d.getComplaintCategory());}
    private boolean overdue(DisputeCase d){return d.getStatus()!=DisputeCaseStatus.RESOLVED&&d.getRespondBy()!=null&&!time.now().isBefore(d.getRespondBy());}
    private Instant deadline(){if(responseHours<1||responseHours>720)throw new IllegalStateException("homespace.complaint.response-hours must be 1..720");return time.now().plus(Duration.ofHours(responseHours));}
    private String key(String rental,ComplaintCategory category){return "RENTAL:"+rental+":"+category.name();}
    private String key(DisputeCase d){return key(d.getRentalRequestId(),category(d));}
    private String roundOpener(DisputeCase d){return d.getComplaintRoundOpenedBy()==null?d.getOpenedBy():d.getComplaintRoundOpenedBy();}
    private boolean discussed(DisputeCase d,String actor){return events.findByCaseIdOrderByRevisionAsc(d.getId()).stream().anyMatch(e->e.getRevision()>=(d.getComplaintRoundStartRevision()==null?1:d.getComplaintRoundStartRevision())&&!actor.equals(e.getActorId())&&Set.of("COMMENT","DECLINE","PROPOSE").contains(e.getAction()));}
    private String other(RentalRequest r,String actor){return actor.equals(r.getRenterId())?r.getOwnerId():r.getRenterId();}
    private String other(DisputeCase d,String actor){return actor.equals(d.getPayerId())?d.getPayeeId():d.getPayerId();}
    private String role(DisputeCase d,String actor){return actor.equals(d.getPayerId())?"TENANT":"LANDLORD";}
    private void bilateral(DisputeCase d){if(d.getStatus()!=DisputeCaseStatus.OPEN)throw forbidden("Hồ sơ đã chuyển admin; không tự áp dụng đề xuất cũ.");}
    private void otherProposer(DisputeCase d,String actor){if(d.getProposedBy()==null||actor.equals(d.getProposedBy()))throw forbidden("Đề xuất cần bên còn lại phản hồi.");}
    private void party(RentalRequest r,String actor){if(actor==null||!actor.equals(r.getRenterId())&&!actor.equals(r.getOwnerId()))throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);}
    private void party(DisputeCase d,String actor){if(actor==null||!actor.equals(d.getPayerId())&&!actor.equals(d.getPayeeId()))throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);}
    private void admin(String actor){var auth=SecurityContextHolder.getContext().getAuthentication();var c=UserContextHolder.get();if(actor==null||c==null||!actor.equals(c.userId())||auth==null||!auth.isAuthenticated()||auth.getAuthorities().stream().noneMatch(a->"ADMIN".equals(a.getAuthority())))throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);}
    private String text(String s,int max){if(s==null||s.trim().length()<10||s.trim().length()>max)throw invalid("Nội dung cần 10–"+max+" ký tự.");return s.trim();}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot write complaint audit",e);}}
    private AppException missing(){return new AppException(ContractErrorCode.COMPLAINT_NOT_FOUND);}
    private AppException invalid(String reason){return new AppException(ContractErrorCode.COMPLAINT_INVALID,reason);}
    private AppException forbidden(String reason){return new AppException(ContractErrorCode.COMPLAINT_NOT_ALLOWED,reason);}
}
