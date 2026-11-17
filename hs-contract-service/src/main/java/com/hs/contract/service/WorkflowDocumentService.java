package com.hs.contract.service;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.*;
import com.hs.contract.event.WorkflowDocumentSigned;
import com.hs.common.payment.ReservationPolicy;
import com.hs.payment.repository.DisputeCaseRepository;
import com.hs.storage.service.StorageService;
import com.hs.storage.model.constant.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** Dedicated signing envelopes; no new rent/deposit payment and no reuse of the lease's signature. */
@Service @RequiredArgsConstructor
public class WorkflowDocumentService {
    public static final String KIND="WORKFLOW";
    private final RentalWorkflowSupport support;
    private final WorkflowSigningDocumentRepository records;
    private final ContractDocumentRepository documents;
    private final SignatureRequestRepository signatures;
    private final DisputeCaseRepository complaints;
    private final WorkflowPdfRenderer renderer;
    private final StorageService storage;
    private final com.hs.contract.pdf.PdfSignatureEngine pdfEngine;
    private final ApplicationEventPublisher events;
    private final com.hs.contract.notification.AgreementSigningNotificationWriter signingNotices;
    @org.springframework.beans.factory.annotation.Autowired private ContractTermsResolver effectiveTerms;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
    @Value("${homespace.signature.mode:INTERNAL}") private String signatureMode="INTERNAL";
    public record View(String signingContractId,long sourceRevision,String status,Instant preparedAt,Instant signedAt,boolean current){}
    public static boolean applies(Contract c){return KIND.equals(c.getAgreementKind())&&c.getParentContractId()!=null;}
    public static String hash(RentalWorkflowRecord w){
        String generation="";
        if(w.getHistoryJson()!=null)try{var history=new com.fasterxml.jackson.databind.ObjectMapper().readValue(w.getHistoryJson(),new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>(){});long proposal=0;for(var event:history)if("PROPOSE".equals(event.get("action"))&&event.get("revision") instanceof Number revision)proposal=Math.max(proposal,revision.longValue());if(proposal>0)generation="\nproposal:"+proposal;}catch(Exception e){throw new IllegalStateException("Cannot read proposal generation",e);}
        return ReservationPolicy.hash(Objects.toString(w.getSourceRevisionId(),"")+"\n"+Objects.toString(w.getPayloadJson(),"")+"\n"+Objects.toString(w.getCalculationJson(),"")+generation);
    }
    private String kind(String kind){return switch(kind){case "early-exit","EARLY_EXIT"->"EARLY_EXIT";default->throw support.invalid("Chỉ có văn bản riêng cho chấm dứt sớm.");};}
    @Transactional public List<View> list(String rental,String kind,String actor){var s=support.lock(rental,actor,kind(kind));return records.findByWorkflowIdOrderByPreparedAtDesc(s.record().getId()).stream().map(d->view(d,s.record())).toList();}
    @Transactional public View prepare(String rental,String kind,String actor,Long revision){
        var s=support.lock(rental,actor,kind(kind));support.version(s,revision);eligible(s.record(),s.contract());
        var old=records.findByWorkflowIdAndContentHash(s.record().getId(),hash(s.record()));if(old.isPresent())return view(old.get(),s.record());
        if(!"APPROVED".equals(s.record().getStatus()))throw support.invalid("Chỉ tạo phụ lục khi chủ nhà đã đồng ý và hai bên chưa ký đủ.");
        var src=support.revisions.findById(s.record().getSourceRevisionId()).filter(r->Objects.equals(r.getId(),effectiveTerms.at(s.contract(),support.time.today()).getId())).orElseThrow(support::missing);
        var c=support.contracts.saveAndFlush(Contract.builder().contractNumber("CT-"+UUID.randomUUID().toString().substring(0,12).toUpperCase()).rentalRequestId(rental).listingId(s.contract().getListingId()).branchId(s.contract().getBranchId()).landlordId(s.contract().getLandlordId()).tenantId(s.contract().getTenantId()).templateId(s.contract().getTemplateId()).templateVersionId(src.getTemplateVersionId()).parentContractId(s.contract().getId()).agreementKind(KIND).paymentStatus(s.contract().getPaymentStatus()).rentalPaymentId(s.contract().getRentalPaymentId()).paidAt(s.contract().getPaidAt()).status(ContractStatus.DRAFT).build());
        var rev=new ContractRevision();BeanUtils.copyProperties(src,rev,"id","contract","revisionNumber","createdAt","updatedAt");rev.setContract(c);rev.setRevisionNumber(1);var lease=new LinkedHashMap<>(support.map(src.getLeaseSnapshot()));var payload=support.map(s.record().getPayloadJson());
        java.time.LocalDate end=java.time.LocalDate.parse(payload.get("endExclusive").toString());
        lease.put("endDateText",end.minusDays(1).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        var rentalRecord=s.rental();int months=0;for(int n=1;n<=ContractLeaseCalc.months(s.contract(),rentalRecord);n++)if(rentalRecord.getMoveInDate().plusMonths(n).equals(end)){months=n;break;}
        if(months>0){lease.put("durationMonths",months);lease.put("durationText",months+" tháng");}
        rev.setLeaseSnapshot(support.json(lease));rev.setRevisionNote("Bản chụp bất biến của thỏa thuận; không sửa HĐ gốc");rev=support.revisions.saveAndFlush(rev);c.setCurrentRevisionId(rev.getId());support.contracts.saveAndFlush(c);
        byte[] docx=renderer.renderDocx(s.contract(),s.record(),rev),pdf;
        try{pdf=pdfEngine.addSignaturePage(renderer.pdf(docx));}catch(java.io.IOException e){throw new IllegalStateException("Cannot prepare annex signature page",e);}
        var word=storage.uploadDirect(docx,c.getContractNumber()+".docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",StoragePurpose.CONTRACT_DOCUMENT,"CONTRACT",c.getId(),StorageVisibility.PRIVATE,actor);
        documents.saveAndFlush(ContractDocument.builder().contractId(c.getId()).revisionId(rev.getId()).templateVersionId(c.getTemplateVersionId()).documentType(ContractDocumentType.DOCX).purpose(DocumentPurpose.OFFICIAL).storageObjectId(word.id()).fileName(c.getContractNumber()+".docx").fileSize((long)docx.length).renderFingerprint(ContractPreviewIdentity.fingerprint(rev,signatureMode)).status(DocumentGenerationStatus.READY).generatedAt(support.time.now()).build());
        var file=storage.uploadDirect(pdf,c.getContractNumber()+".pdf","application/pdf",StoragePurpose.CONTRACT_DOCUMENT,"CONTRACT",c.getId(),StorageVisibility.PRIVATE,actor);
        documents.saveAndFlush(ContractDocument.builder().contractId(c.getId()).revisionId(rev.getId()).templateVersionId(c.getTemplateVersionId()).documentType(ContractDocumentType.PDF).purpose(DocumentPurpose.OFFICIAL).storageObjectId(file.id()).fileName(c.getContractNumber()+".pdf").fileSize((long)pdf.length).renderFingerprint(ContractPreviewIdentity.fingerprint(rev,signatureMode)).status(DocumentGenerationStatus.READY).generatedAt(support.time.now()).build());
        var record=new WorkflowSigningDocument();record.setWorkflowId(s.record().getId());record.setContentHash(hash(s.record()));record.setSigningContractId(c.getId());record.setSourceRevision(s.record().getRevision());record.setSourceSnapshotJson(support.json(Map.of("proposal",support.map(s.record().getPayloadJson()),"calculation",support.map(s.record().getCalculationJson()))));record.setPreparedAt(support.time.now());records.saveAndFlush(record);
        // Only a genuinely new document prepared by the tenant is the landlord's review cue; owner prepare and replays stay silent.
        if(actor.equals(c.getTenantId())&&!actor.equals(c.getLandlordId()))signingNotices.reviewReady(c);
        return view(record,s.record());
    }
    private View view(WorkflowSigningDocument d,RentalWorkflowRecord source){var c=support.contracts.findById(d.getSigningContractId()).orElseThrow(support::missing);return new View(c.getId(),d.getSourceRevision(),c.getStatus().name(),d.getPreparedAt(),d.getSignedAt(),d.getContentHash().equals(hash(source))&&!Set.of("WITHDRAWN","REJECTED").contains(source.getStatus()));}
    private void eligible(RentalWorkflowRecord w,Contract root){
        if(root.getSignedAt()==null||!Set.of(ContractStatus.ACTIVE,ContractStatus.EXPIRED).contains(root.getStatus())||w.getRevision()==0)throw support.invalid("Cần HĐ thuê đã ký và hồ sơ nguồn hợp lệ.");
        if(!Objects.equals(w.getContractId(),root.getId())||"APPROVED".equals(w.getStatus())&&!Objects.equals(w.getSourceRevisionId(),effectiveTerms.at(root,support.time.today()).getId()))throw support.invalid("Hợp đồng nguồn đã đổi; cần đối chiếu lại thỏa thuận trước khi ký.");
        // Legacy MOVE_IN sources stay read-only: never prepared, signed or applied again.
        if(!"EARLY_EXIT".equals(w.getKind())||!Set.of("APPROVED","AGREED","RETURNED").contains(w.getStatus()))throw support.invalid("Nguồn đang phản đối hoặc chưa được lập để ký.");
        if(complaints.findByActiveKey("RENTAL:"+w.getRentalRequestId()+":TERMINATION").isPresent())throw support.invalid("Cần giải quyết khiếu nại nguồn trước khi ký văn bản.");
    }
    public void validateSigning(Contract agreement){
        var doc=records.findBySigningContractId(agreement.getId()).orElseThrow(support::missing);
        var w=support.records.findById(doc.getWorkflowId()).orElseThrow(support::missing);var root=support.contracts.findById(agreement.getParentContractId()).orElseThrow(support::missing);eligible(w,root);
        if(!Objects.equals(w.getContractId(),root.getId())||!Objects.equals(agreement.getRentalRequestId(),w.getRentalRequestId())
            ||!Objects.equals(agreement.getLandlordId(),root.getLandlordId())||!Objects.equals(agreement.getTenantId(),root.getTenantId())
            ||!Objects.equals(agreement.getListingId(),root.getListingId())||!doc.getContentHash().equals(hash(w)))throw support.invalid("Thỏa thuận đã đổi bản hoặc không khớp hồ sơ nguồn; không ký hoặc áp dụng PDF cũ.");
        if("EARLY_EXIT".equals(w.getKind())&&"APPROVED".equals(w.getStatus())){var p=support.map(w.getPayloadJson());if(java.time.LocalDate.parse(p.get("endExclusive").toString()).minusDays(1).isBefore(support.time.today()))throw support.invalid("Ngày đề nghị đã qua; cần hai bên đối chiếu bản mới.");}
    }
    /** Invoked only inside the verified SmartCA embedding transaction. */
    @Transactional public void bothSigned(Contract agreement){
        // Serialize direct completion callbacks too, in the same root-first order as signing.
        var root=support.contracts.findByIdForUpdate(agreement.getParentContractId()).orElseThrow(support::missing);
        em.refresh(root,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        var record=records.findBySigningContractId(agreement.getId()).orElseThrow(support::missing);
        em.refresh(record,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if(record.getSignedAt()!=null)return;validateSigning(agreement);
        for(var role:SignerRole.values())if(signatures.findFirstByContractIdAndSignerRoleOrderByCreatedAtDesc(agreement.getId(),role).filter(s->s.getStatus()==SignatureRequestStatus.SIGNED&&agreement.getCurrentRevisionId().equals(s.getRevisionId())&&Objects.equals(s.getSignerUserId(),role==SignerRole.LANDLORD?agreement.getLandlordId():agreement.getTenantId())).isEmpty())throw support.invalid("Chưa có đủ chữ ký được kiểm chứng cho bản này.");
        if(documents.findByContractIdAndRevisionId(agreement.getId(),agreement.getCurrentRevisionId()).stream().noneMatch(d->d.getPurpose()==DocumentPurpose.SIGNED_FINAL&&d.getStatus()==DocumentGenerationStatus.READY&&d.getDocumentType()==ContractDocumentType.PDF&&d.getStorageObjectId()!=null&&!d.getStorageObjectId().isBlank()))throw support.invalid("Chưa có PDF đã ký đủ.");
        events.publishEvent(new WorkflowDocumentSigned(record.getWorkflowId(),record.getContentHash(),agreement.getId()));record.setSignedAt(support.time.now());records.saveAndFlush(record);
    }
}
