package com.hs.contract.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.dto.billing.*;
import com.hs.contract.model.*;
import com.hs.contract.repository.*;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.repository.*;
import com.hs.storage.repository.StorageObjectRepository;
import com.hs.storage.model.constant.*;
import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component @RequiredArgsConstructor
public class RentalWorkflowSupport {
    final RentalWorkflowRecordRepository records;
    final RentalRequestRepository rentals;
    final ContractRepository contracts;
    final ContractRevisionRepository revisions;
    final StorageObjectRepository files;
    final PaymentRequestRepository payments;
    final ObjectMapper mapper;
    final BillingTime time;
    @PersistenceContext private EntityManager em;
    public record Source(RentalRequest rental,Contract contract,RentalWorkflowRecord record,String actor) {}
    /** Pre-tenancy shares rental -> lease locks with signing; active tenancy never takes a rental write lock. */
    Source lock(String rentalId,String actor,String kind) {
        RentalRequest r;Contract c;
        if("CANCELLATION".equals(kind)) {
            r=rentals.findByIdForUpdate(rentalId).orElseThrow(this::missing);em.refresh(r,LockModeType.PESSIMISTIC_WRITE);
            c=contracts.findByRentalRequestId(rentalId).orElse(null);
        } else {r=rentals.findById(rentalId).orElseThrow(this::missing);c=contracts.findByRentalRequestId(rentalId).orElseThrow(this::missing);}
        if(!Boolean.TRUE.equals(r.getActive())||actor==null||!actor.equals(r.getOwnerId())&&!actor.equals(r.getRenterId()))throw missing();
        if(c!=null){c=contracts.findByIdForUpdate(c.getId()).orElseThrow(this::missing);em.refresh(c,LockModeType.PESSIMISTIC_WRITE);}
        if(c!=null&&(!Boolean.TRUE.equals(c.getActive())||!r.getOwnerId().equals(c.getLandlordId())||!r.getRenterId().equals(c.getTenantId())||!r.getListing().getId().equals(c.getListingId())))throw missing();
        var w=records.findById(rentalId+":"+kind).orElse(null);
        if(w==null){w=new RentalWorkflowRecord();w.setId(rentalId+":"+kind);w.setRentalRequestId(rentalId);w.setKind(kind);}
        else em.refresh(w);
        return new Source(r,c,w,actor);
    }
    void version(Source s,Long revision){if(revision==null||revision!=s.record().getRevision())throw new AppException(ContractErrorCode.COMPLAINT_STALE,"Nội dung đã thay đổi. Tải lại và kiểm tra trước khi thực hiện.");}
    String text(String value){if(value==null||value.trim().length()<10||value.trim().length()>1000)throw invalid("Nội dung cần 10–1000 ký tự.");return value.trim();}
    void reload(Object entity){em.refresh(entity);}
    String role(Source s){return s.actor().equals(s.rental().getOwnerId())?"LANDLORD":"TENANT";}
    boolean owner(Source s){return "LANDLORD".equals(role(s));}
    void ownerOnly(Source s){if(!owner(s))throw missing();}
    void otherProposer(Source s){if(s.actor().equals(s.record().getProposedBy()))throw invalid("Cần bên còn lại phản hồi đề xuất.");}
    <T> T payload(Source s,Class<T> type){try{return mapper.readValue(s.record().getPayloadJson(),type);}catch(Exception e){throw invalid("Không đọc được nội dung đã lưu; cần đối soát.");}}
    Map<String,Object> map(String json){if(json==null)return Map.of();try{Map<String,Object> result=mapper.readValue(json,new TypeReference<>(){});if(result.get("endExclusive") instanceof List<?>)result.put("endExclusive",mapper.convertValue(result.get("endExclusive"),LocalDate.class).toString());if(result.get("handedOverAt") instanceof Number)result.put("handedOverAt",mapper.convertValue(result.get("handedOverAt"),Instant.class).toString());return result;}catch(Exception e){throw new IllegalStateException("Invalid workflow evidence/audit",e);}}
    List<RentalWorkflowView.Event> history(RentalWorkflowRecord w){if(w.getHistoryJson()==null)return new ArrayList<>();try{return new ArrayList<>(mapper.readValue(w.getHistoryJson(),new TypeReference<List<RentalWorkflowView.Event>>(){}));}catch(Exception e){throw new IllegalStateException("Invalid workflow history",e);}}
    String json(Object o){try{return mapper.writer().without(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsString(o);}catch(Exception e){throw new IllegalStateException("Cannot persist rental workflow audit",e);}}
    List<String> evidence(Source s,List<String> ids){
        if(ids==null)return List.of();if(ids.size()>5||new HashSet<>(ids).size()!=ids.size())throw invalid("Tối đa 5 chứng cứ khác nhau.");
        for(String id:ids){var f=files.findById(id).orElseThrow(this::missing);
            if(!s.actor().equals(f.getOwnerId())||f.getPurpose()!=StoragePurpose.GENERAL||f.getVisibility()!=StorageVisibility.PRIVATE
                ||!Boolean.TRUE.equals(f.getActive())||f.getStatus()!=StorageStatus.READY||!"RENTAL_COMPLAINT_EVIDENCE".equals(f.getReferenceType())||!s.rental().getId().equals(f.getReferenceId())
                ||f.getContentType()==null||!Set.of("image/jpeg","image/png","image/webp","application/pdf").contains(f.getContentType())
                ||f.getSizeBytes()==null||f.getSizeBytes()<=0||f.getSizeBytes()>15L*1024*1024)throw invalid("Chứng cứ phải là ảnh/PDF riêng tư, đã tải xong, đúng người và hồ sơ thuê.");}
        return List.copyOf(ids);
    }
    void propose(Source s,Object payload,String reason,List<String> evidence,Instant deadline){
        var w=s.record();w.setContractId(s.contract()==null?null:s.contract().getId());w.setSourceRevisionId(s.contract()==null?null:s.contract().getCurrentRevisionId());
        w.setPayloadJson(json(payload));w.setStatus("PROPOSED");w.setProposedBy(s.actor());w.setRespondBy(deadline);w.setOwnerConfirmedAt(null);w.setTenantConfirmedAt(null);
        save(s,"PROPOSE",reason,evidence);
    }
    void save(Source s,String action,String reason,List<String> evidence){
        var w=s.record();w.setRevision(w.getRevision()+1);var h=history(w);
        h.add(new RentalWorkflowView.Event(w.getRevision(),action,s.actor(),role(s),time.now(),text(reason),map(w.getPayloadJson()),evidence));
        w.setHistoryJson(json(h));records.saveAndFlush(w);
    }
    RentalWorkflowView view(Source s,List<String> actions,String blocked,String obligation,List<com.hs.payment.dto.PaymentRequestResponse> refunds){
        var w=s.record();return new RentalWorkflowView(w.getKind(),w.getStatus(),w.getRevision(),role(s),s.contract()==null?null:s.contract().getId(),w.getSourceRevisionId(),w.getProposedBy(),time.now(),w.getRespondBy(),w.getOwnerConfirmedAt(),w.getTenantConfirmedAt(),map(w.getPayloadJson()),history(w),actions,blocked,w.getComplaintId(),obligation,refunds,null);
    }
    void acknowledge(RentalWorkflowPayloads.Action p){if(p==null||!p.acknowledged())throw invalid("Cần xác nhận rõ nội dung và việc bàn giao thực tế.");text(p.reason());}
    AppException missing(){return new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);}
    AppException invalid(String reason){return new AppException(ContractErrorCode.LIFECYCLE_NOT_ALLOWED,reason);}
}
