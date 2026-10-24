package com.hs.contract.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.dto.amendment.*;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.*;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.listing.service.ParkingReservationService;
import com.hs.storage.model.constant.*;
import com.hs.storage.repository.StorageObjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import static com.hs.contract.service.AmendmentTermsBuilder.*;

@Service @RequiredArgsConstructor @Slf4j
public class ContractAmendmentService {
    public static final Set<String> PENDING = Set.of("REQUESTED", "CHANGES_REQUESTED", "APPROVED", "SCHEDULED", "TENANT_REQUESTED", "TENANT_ACCEPTED");
    private final ContractRepository contracts;
    private final ContractRevisionRepository revisions;
    private final ContractAmendmentRepository amendments;
    private final ContractRenewalRepository renewals;
    private final MonthlyInvoiceRepository invoices;
    private final RentalRequestRepository rentals;
    private final SignatureRequestRepository signatures;
    private final ContractDocumentRepository documents;
    private final StorageObjectRepository files;
    private final ParkingReservationService parking;
    private final AmendmentTermsBuilder builder;
    private final ContractTermsResolver terms;
    private final ObjectMapper mapper;
    private final BillingTime time;
    private final PlatformTransactionManager transactionManager;
    @org.springframework.beans.factory.annotation.Autowired private ContractAmendmentDraftRepository drafts;
    @org.springframework.beans.factory.annotation.Autowired private AmendmentOptionsService options;
    @org.springframework.beans.factory.annotation.Autowired private com.hs.payment.service.PaymentRequestService payments;
    @org.springframework.beans.factory.annotation.Autowired private com.hs.payment.repository.DepositRecordRepository deposits;
    @org.springframework.beans.factory.annotation.Autowired private com.hs.payment.repository.PaymentRequestRepository paymentRecords;
    @Value("${homespace.amendment.response-hours:48}") private long responseHours = 48;
    @Value("${homespace.amendment.signing-hours:48}") private long signingHours = 48;
    @Value("${homespace.signature.mode:INTERNAL}") private String signatureMode = "INTERNAL";

    @Transactional public AmendmentResponse get(String id, String actor) {
        Contract c = root(id, actor); syncLocked(c); return response(c);
    }

    @Transactional public AmendmentTermsBuilder.Result preview(String id, String actor, AmendmentPayload p) {
        Contract c = root(id, actor);
        requireLandlord(c, actor);
        String blocked = blocked(c, false); if (blocked != null) throw invalid(blocked);
        requireFutureDate(c,p);
        var replacing=replacement(c,p.previousProposalId());
        validateReplacement(c,replacing,p.effectiveOn());
        ContractRevision base = terms.at(c, time.today());
        if (!base.getId().equals(p.baseRevisionId())) throw invalid("Điều khoản đã thay đổi. Tải lại để đối chiếu.");
        if (options != null && !Boolean.TRUE.equals(p.changes().get("freeForm"))) options.validate(c,builder.snapshot(base),p.changes());
        var result=buildTerms(base,p.changes(),replacing);validateBusinessTerms(c,base,result.terms(),businessChanges(p.changes(),replacing),p.effectiveOn());return result;
    }

    @Transactional public AmendmentResponse propose(String id, String actor, AmendmentPayload p) {
        Contract c = root(id, actor); syncLocked(c);
        requireLandlord(c, actor);
        if (p == null) throw invalid("Thiếu nội dung đề nghị.");
        try { UUID.fromString(p.requestId()); } catch (Exception e) { throw invalid("Mã yêu cầu không hợp lệ."); }
        Optional<ContractAmendment> duplicate = amendments.findById(p.requestId());
        if (duplicate.isPresent()) {
            ContractAmendment a = duplicate.get();
            if (p.reason() == null || !id.equals(a.getContractId()) || !actor.equals(a.getProposerId()) || !Objects.equals(p.baseRevisionId(), a.getBaseRevisionId())
                    || (a.isImmediateEffect() ? p.effectiveOn() != null : !Objects.equals(p.effectiveOn(), a.getEffectiveOn())) || !Objects.equals(p.reason().trim(), a.getReason())
                    || !Objects.equals(p.previousProposalId(), a.getPreviousProposalId())
                    || !Objects.equals(sourceRequestId(p), a.getTenantRequestId())
                    || !builder.map(a.getChangesSnapshot()).equals(p.changes())
                    || !readIds(a.getEvidenceSnapshot()).equals(p.evidenceIds() == null ? List.of() : p.evidenceIds()))
                throw invalid("Mã yêu cầu đã dùng cho nội dung khác.");
            return response(c);
        }
        String blocked = blocked(c, false); if (blocked != null) throw invalid(blocked);
        ContractAmendment previous = null;
        ContractAmendment tenantRequest = null;
        if (p.tenantRequestId() != null) {
            tenantRequest = proposal(c, p.tenantRequestId());
            if (!"TENANT_ACCEPTED".equals(tenantRequest.getStatus())) throw invalid("Yêu cầu người thuê chưa được chấp thuận để soạn phụ lục.");
        }
        if (p.previousProposalId() != null) {
            previous = proposal(c, p.previousProposalId());
            if (!Set.of("CHANGES_REQUESTED","SCHEDULED").contains(previous.getStatus()) || !actor.equals(previous.getProposerId()))
                throw invalid("Chỉ chủ nhà được chỉnh lại đề xuất cần sửa hoặc phụ lục đã ký còn chờ áp dụng.");
        }
        var replacing=replacement(c,p.previousProposalId());
        requireFutureDate(c,p);validateReplacement(c,replacing,p.effectiveOn());
        for (ContractAmendment a : history(c)) if (PENDING.contains(a.getStatus()) && (previous == null || !a.getId().equals(previous.getId()))
                && (replacing == null || !a.getId().equals(replacing.getId()))
                && (tenantRequest == null || !a.getId().equals(tenantRequest.getId())))
            throw invalid("Hợp đồng đang có yêu cầu điều chỉnh. Hãy xử lý yêu cầu hiện tại.");
        ContractRevision base = terms.at(c, time.today());
        if (!base.getId().equals(p.baseRevisionId())) throw invalid("Điều khoản đã thay đổi. Tải lại trước khi gửi đề nghị.");
        if (options != null && !Boolean.TRUE.equals(p.changes().get("freeForm"))) options.validate(c,builder.snapshot(base),p.changes());
        var result = buildTerms(base, p.changes(),replacing);
        validateBusinessTerms(c,base,result.terms(),businessChanges(p.changes(),replacing),p.effectiveOn());
        String reason = text(p.reason(), 10, 2000);
        List<String> evidence = validateEvidence(c, actor, p.evidenceIds());
        Instant cutoff = cutoff(p.effectiveOn());
        if (previous != null && !"SCHEDULED".equals(previous.getStatus())) { previous.setStatus("SUPERSEDED"); amendments.save(previous); }
        if (tenantRequest != null) { tenantRequest.setStatus("TENANT_FULFILLED"); amendments.save(tenantRequest); }
        amendments.save(ContractAmendment.builder().id(p.requestId()).contractId(id).baseRevisionId(base.getId())
                .previousProposalId(p.previousProposalId()).tenantRequestId(p.tenantRequestId() != null ? p.tenantRequestId() : previous != null ? previous.getTenantRequestId() : null)
                .proposerId(actor).recipientId(other(c, actor)).status("REQUESTED")
                .effectiveOn(p.effectiveOn()).immediateEffect(false).reason(reason).changesSnapshot(builder.write(p.changes()))
                .comparisonSnapshot(builder.write(result.comparison())).evidenceSnapshot(builder.write(evidence))
                .submittedAt(time.now()).respondBy(min(time.now().plus(Duration.ofHours(responseHours)), cutoff)).signBy(cutoff).build());
        if (drafts != null) drafts.deleteById(id);
        return response(c);
    }

    @Transactional public AmendmentResponse requestNegotiation(String id, String actor, TenantAmendmentRequest p) {
        Contract c = root(id, actor); syncLocked(c);
        if (!actor.equals(c.getTenantId())) throw forbidden();
        if (p == null) throw invalid("Thiếu nội dung yêu cầu.");
        try { UUID.fromString(p.requestId()); } catch (Exception e) { throw invalid("Mã yêu cầu không hợp lệ."); }
        String message = text(p.message(), 10, 2000);
        var existing = amendments.findById(p.requestId());
        if (existing.isPresent()) {
            var a = existing.get();
            if (!id.equals(a.getContractId()) || !actor.equals(a.getProposerId()) || !a.getStatus().startsWith("TENANT_")
                    || !message.equals(a.getReason()) || !readIds(a.getEvidenceSnapshot()).equals(p.evidenceIds() == null ? List.of() : p.evidenceIds())) throw invalid("Mã yêu cầu đã dùng cho nội dung khác.");
            return response(c);
        }
        String blocked = blocked(c, true); if (blocked != null) throw invalid(blocked);
        var evidence = validateEvidence(c, actor, p.evidenceIds());
        amendments.save(ContractAmendment.builder().id(p.requestId()).contractId(id).baseRevisionId(terms.at(c,time.today()).getId())
                .proposerId(actor).recipientId(c.getLandlordId()).status("TENANT_REQUESTED").effectiveOn(time.today())
                .changesSnapshot("{}").comparisonSnapshot("[]").evidenceSnapshot(builder.write(evidence)).reason(message)
                .submittedAt(time.now()).respondBy(min(time.now().plus(Duration.ofHours(responseHours)),end(c).atStartOfDay(BillingTime.ZONE).toInstant())).build());
        return response(c);
    }

    @Transactional public AmendmentResponse decideNegotiation(String id, String requestId, String actor, AmendmentDecision p) {
        Contract c = root(id, actor); syncLocked(c); requireLandlord(c, actor);
        var a = proposal(c, requestId);
        if (p == null || p.action() == null) throw invalid("Thiếu phản hồi.");
        if ("ACCEPT".equals(p.action()) && "TENANT_ACCEPTED".equals(a.getStatus())) return response(c);
        if (!Set.of("TENANT_REQUESTED", "TENANT_ACCEPTED").contains(a.getStatus())) throw invalid("Yêu cầu không còn chờ xử lý.");
        String blocked = blocked(c, false); if (blocked != null) throw invalid(blocked);
        switch (p.action()) {
            case "ACCEPT" -> { a.setStatus("TENANT_ACCEPTED"); a.setRespondBy(min(time.now().plus(Duration.ofHours(responseHours)),end(c).atStartOfDay(BillingTime.ZONE).toInstant())); }
            case "REJECT" -> { a.setStatus("TENANT_REJECTED"); a.setResponseNote(text(p.note(),10,2000)); }
            default -> throw invalid("Chọn chấp thuận trao đổi hoặc từ chối.");
        }
        a.setDecidedAt(time.now()); amendments.save(a); return response(c);
    }

    @Transactional public AmendmentPayload getDraft(String id, String actor) {
        Contract c = root(id, actor); requireLandlord(c,actor);
        if (drafts == null) return null;
        return drafts.findById(id).map(d -> {
            try { return mapper.readValue(d.getPayload(),AmendmentPayload.class); }
            catch (Exception e) { throw invalid("Không đọc được bản nháp."); }
        }).orElse(null);
    }
    @Transactional public AmendmentPayload saveDraft(String id, String actor, AmendmentPayload p) {
        Contract c = root(id,actor); syncLocked(c); requireLandlord(c,actor);
        String blocked = blocked(c,false); if (blocked != null) throw invalid(blocked);
        if (p == null || p.changes() == null || p.changes().size() > 14 || builder.write(p).length() > 100_000) throw invalid("Bản nháp không hợp lệ.");
        if(immediate(p.changes()))throw invalid("Phụ lục chỉ áp dụng từ đầu kỳ thuê tương lai, không áp dụng ngay khi ký.");
        validateReplacement(c,replacement(c,p.previousProposalId()),p.effectiveOn());
        if (Boolean.TRUE.equals(p.changes().get("freeForm"))) {
            if (!FreeFormAmendmentTerms.KEYS.containsAll(p.changes().keySet())) throw invalid("Bản nháp có trường không được phép sửa.");
        } else if (!Set.of("rentAmount", "occupantCount", "motorbikeCount", "carCount", "charges", "equipments", "amenities", "policies", "additionalTerms", "applyImmediately").containsAll(p.changes().keySet())) throw invalid("Bản nháp có trường không được phép sửa.");
        validateEvidence(c,actor,p.evidenceIds());
        if (!terms.at(c,time.today()).getId().equals(p.baseRevisionId())) throw invalid("Điều khoản đã thay đổi. Tải lại hợp đồng trước khi sửa.");
        // A draft is private and need not yet have a reason/date or an actual difference.
        drafts.save(ContractAmendmentDraft.builder().contractId(id).ownerId(actor).payload(builder.write(p)).build()); return p;
    }
    @Transactional public void discardDraft(String id,String actor) { requireLandlord(root(id,actor),actor); drafts.deleteById(id); }
    private void requireLandlord(Contract c,String actor) { if (!actor.equals(c.getLandlordId())) throw forbidden(); }
    private String sourceRequestId(AmendmentPayload p) {
        return p.tenantRequestId() != null ? p.tenantRequestId() : p.previousProposalId() == null ? null
                : amendments.findById(p.previousProposalId()).map(ContractAmendment::getTenantRequestId).orElse(null);
    }
    @Transactional public AmendmentOptionsService.Options options(String id,String actor) {
        var c = root(id,actor); return options.get(c,builder.snapshot(terms.at(c,time.today())));
    }

    @Transactional public AmendmentResponse decide(String id, String proposalId, String actor, AmendmentDecision p) {
        Contract c = root(id, actor); syncLocked(c);
        ContractAmendment a = proposal(c, proposalId);
        if (p == null || p.action() == null) throw invalid("Thiếu hành động phản hồi.");
        if ("WITHDRAW".equals(p.action())) {
            if (!actor.equals(a.getProposerId()) || !Set.of("REQUESTED", "CHANGES_REQUESTED", "APPROVED").contains(a.getStatus())) throw forbidden();
            cancelAgreement(a);
            a.setStatus("WITHDRAWN"); a.setDecidedAt(time.now()); amendments.save(a); return response(c);
        }
        if (!actor.equals(a.getRecipientId())) throw forbidden();
        if ("APPROVE".equals(p.action()) && "APPROVED".equals(a.getStatus())) return response(c);
        if (!Set.of("REQUESTED", "APPROVED").contains(a.getStatus()) || !time.now().isBefore("APPROVED".equals(a.getStatus()) ? a.getSignBy() : a.getRespondBy())) throw invalid("Yêu cầu không còn chờ phản hồi.");
        String blocked = blocked(c, false); if (blocked != null) throw invalid(blocked);
        switch (p.action()) {
            case "REJECT", "REQUEST_CHANGES" -> {
                a.setResponseNote(text(p.note(), 10, 2000));
                cancelAgreement(a);
                a.setStatus("REJECT".equals(p.action()) ? "REJECTED" : "CHANGES_REQUESTED");
                if ("CHANGES_REQUESTED".equals(a.getStatus())) {
                    a.setSignBy(a.isImmediateEffect() ? end(c).atStartOfDay(BillingTime.ZONE).toInstant() : cutoff(a.getEffectiveOn()));
                    a.setRespondBy(min(time.now().plus(Duration.ofHours(responseHours)), a.getSignBy()));
                }
            }
            case "APPROVE" -> {
                validateBaseAndDate(c, a);
                ContractRevision base = terms.require(a.getBaseRevisionId());
                var replacing=replacement(c,a.getPreviousProposalId());
                var result = buildTerms(base, builder.map(a.getChangesSnapshot()),replacing);
                validateBusinessTerms(c,base,result.terms(),businessChanges(builder.map(a.getChangesSnapshot()),replacing),a.getEffectiveOn());
                Contract agreement = contracts.save(Contract.builder().contractNumber(c.getContractNumber() + "-PL-" + a.getId().substring(0, 8))
                        .parentContractId(c.getId()).agreementKind("AMENDMENT").rentalRequestId(c.getRentalRequestId())
                        .listingId(c.getListingId()).branchId(c.getBranchId()).landlordId(c.getLandlordId()).tenantId(c.getTenantId())
                        .templateId(c.getTemplateId()).templateVersionId(c.getTemplateVersionId())
                        .paymentStatus(c.getPaymentStatus()).rentalPaymentId(c.getRentalPaymentId()).paidAt(c.getPaidAt())
                        .status(ContractStatus.DRAFT).build());
                Map<String,Object> t = result.terms();
                ContractRevision revision = revisions.save(ContractRevision.builder().contract(agreement).revisionNumber(1)
                        .templateVersionId(base.getTemplateVersionId()).schemaVersion(base.getSchemaVersion())
                        .landlordSnapshot(builder.write(t.get("landlord"))).tenantSnapshot(builder.write(t.get("tenant")))
                        .propertySnapshot(builder.write(t.get("property"))).leaseSnapshot(builder.write(t.get("lease")))
                        .financialSnapshot(builder.write(t.get("financial"))).chargesSnapshot(builder.write(t.get("charges")))
                        .equipmentSnapshot(builder.write(t.get("equipments"))).amenitiesSnapshot(builder.write(t.get("amenities")))
                        .policiesSnapshot(builder.write(t.get("policies"))).specialTerms(builder.documentSpecialTerms(t))
                        .initialMetersSnapshot(base.getInitialMetersSnapshot()).initialPaymentSnapshot(base.getInitialPaymentSnapshot())
                        .revisionNote("Phụ lục điều chỉnh " + a.getId() + "; hiệu lực " + a.getEffectiveOn()).build());
                agreement.setCurrentRevisionId(revision.getId()); contracts.save(agreement);
                a.setAgreementContractId(agreement.getId()); a.setTermsRevisionId(revision.getId()); a.setStatus("APPROVED");
                a.setSignBy(min(a.getSignBy(), time.now().plus(Duration.ofHours(signingHours))));
                var changes = businessChanges(builder.map(a.getChangesSnapshot()),replacing);
                if (vehiclesChanged(changes) || leaseChanged(changes)) {
                    Map<String,Object> tenant = builder.object(t.get("tenant"));
                    parking.holdForAmendment(c.getId(), agreement.getId(), c.getRentalRequestId(), c.getListingId(),
                            a.getEffectiveOn(), proposedEnd(c,t,changes), count(tenant.get("motorbikeCount"), 0, 100), count(tenant.get("carCount"), 0, 100), a.getSignBy());
                }
            }
            default -> throw invalid("Hành động không được hỗ trợ.");
        }
        a.setDecidedAt(time.now()); amendments.save(a); return response(c);
    }

    /** Called before initiating AND before committing a verified signature. */
    public void validateSigning(Contract agreement) {
        ContractAmendment a = amendments.findByAgreementContractId(agreement.getId()).orElseThrow(() -> invalid("Không tìm thấy phụ lục."));
        Contract c = contracts.findById(agreement.getParentContractId()).orElseThrow(this::forbidden);
        if (!"APPROVED".equals(a.getStatus()) || !time.now().isBefore(a.getSignBy()) || blocked(c, false) != null) throw invalid("Phụ lục đã hết hạn hoặc hợp đồng không còn cho phép ký.");
        validateBaseAndDate(c, a);
        var changes = businessChanges(builder.map(a.getChangesSnapshot()), replacement(c,a.getPreviousProposalId()));
        validateParking(c,builder.snapshot(terms.require(a.getTermsRevisionId())),changes,a.getEffectiveOn(),agreement.getId());
    }

    @Transactional public void signed(Contract agreement) {
        Contract c = contracts.findByIdForUpdate(agreement.getParentContractId()).orElseThrow(this::forbidden);
        ContractAmendment a = amendments.findByAgreementContractId(agreement.getId()).orElseThrow(this::forbidden);
        if (Set.of("SCHEDULED", "APPLIED").contains(a.getStatus())) return;
        validateSigning(agreement);
        for (SignerRole role : SignerRole.values()) if (signatures.findFirstByContractIdAndSignerRoleOrderByCreatedAtDesc(agreement.getId(), role)
                .filter(s -> s.getStatus() == SignatureRequestStatus.SIGNED && agreement.getCurrentRevisionId().equals(s.getRevisionId())).isEmpty()) throw invalid("Cần đủ hai chữ ký đúng phiên bản.");
        if (documents.findByContractIdAndRevisionId(agreement.getId(), agreement.getCurrentRevisionId()).stream()
                .noneMatch(d -> d.getPurpose() == DocumentPurpose.SIGNED_FINAL && d.getStatus() == DocumentGenerationStatus.READY)) throw invalid("Chưa có PDF hoàn chỉnh đã ký.");
        var replacing=replacement(c,a.getPreviousProposalId());
        var changes = businessChanges(builder.map(a.getChangesSnapshot()),replacing);
        var newTerms=builder.snapshot(terms.require(a.getTermsRevisionId()));
        validateBusinessTerms(c,terms.require(a.getBaseRevisionId()),newTerms,changes,a.getEffectiveOn(),agreement.getId());
        if (vehiclesChanged(changes) || leaseChanged(changes)) {
            var tenant = builder.map(terms.require(a.getTermsRevisionId()).getTenantSnapshot());
            parking.activateAmendmentHold(c.getId(), agreement.getId(), c.getRentalRequestId(), c.getListingId(), a.getEffectiveOn(), proposedEnd(c,newTerms,changes),
                    count(tenant.get("motorbikeCount"), 0, 100), count(tenant.get("carCount"), 0, 100));
        }
        if(leaseChanged(changes)) {
            var r=rentals.findById(c.getRentalRequestId()).orElseThrow(this::forbidden);
            LocalDate end=proposedEnd(c,newTerms,changes);
            int months=(end.getYear()-r.getMoveInDate().getYear())*12+end.getMonthValue()-r.getMoveInDate().getMonthValue();
            c.setEffectiveLeaseMonths(months);contracts.save(c);
            // Only unissued drafts outside the new term can be removed. Issued records were checked above.
            for(var bill:invoices.findByContractIdOrderByPeriodIndexDesc(c.getId()))
                if(bill.getStatus()==MonthlyInvoiceStatus.DRAFT && !bill.getPeriodStart().isBefore(end))invoices.delete(bill);
        }
        if(replacing!=null){replacing.setStatus("SUPERSEDED");replacing.setDecidedAt(time.now());amendments.save(replacing);}
        a.setStatus("SCHEDULED"); a.setSignedAt(time.now()); amendments.save(a);
        syncLocked(c);
    }

    public boolean hasPending(Contract c) {
        syncLocked(c); return history(c).stream().anyMatch(a -> PENDING.contains(a.getStatus()));
    }
    public void syncLocked(Contract c) {
        for (var a : history(c)) {
            if (Set.of("TENANT_REQUESTED", "TENANT_ACCEPTED").contains(a.getStatus())
                    && (c.getStatus() != ContractStatus.ACTIVE || !time.now().isBefore(a.getRespondBy()) || !time.today().isBefore(end(c)))) {
                a.setStatus("TENANT_EXPIRED"); a.setResponseNote("Đã đóng vì không được xử lý trong thời hạn; điều khoản hợp đồng giữ nguyên.");
                a.setDecidedAt(time.now()); amendments.save(a);
            }
            if (Set.of("REQUESTED", "CHANGES_REQUESTED", "APPROVED").contains(a.getStatus())
                    && (c.getStatus() != ContractStatus.ACTIVE || !time.now().isBefore("APPROVED".equals(a.getStatus()) ? a.getSignBy() : a.getRespondBy()) || !time.today().isBefore(end(c)))) {
                a.setStatus("EXPIRED"); a.setDecidedAt(time.now()); amendments.save(a);
                cancelAgreement(a);
            }
            if ("SCHEDULED".equals(a.getStatus()) && !time.today().isBefore(a.getEffectiveOn())) {
                createDepositDelta(c,a);
                a.setStatus("APPLIED"); Instant starts = a.getEffectiveOn().atStartOfDay(BillingTime.ZONE).toInstant();
                a.setAppliedAt(starts.isBefore(a.getSignedAt()) ? a.getSignedAt() : starts); amendments.save(a);
                log.info("CONTRACT_AMENDMENT_APPLIED contract={} amendment={} effective={}", c.getId(), a.getId(), a.getEffectiveOn());
            }
        }
    }
    @Scheduled(fixedDelayString = "${homespace.billing.sync-delay-ms:60000}")
    public void synchronize() {
        List<String> ids = amendments.findByStatusIn(PENDING).stream().map(ContractAmendment::getContractId).distinct().toList();
        for (String id : ids) try { new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                contracts.findByIdForUpdate(id).ifPresent(this::syncLocked)); }
        catch (Exception e) { log.warn("Unable to synchronize amendment contract={}", id, e); }
    }
    private void validateBaseAndDate(Contract c, ContractAmendment a) {
        if (a.isImmediateEffect() || !terms.at(c, time.today()).getId().equals(a.getBaseRevisionId()) || !dates(c).contains(a.getEffectiveOn()))
            throw invalid("Điều khoản hoặc hóa đơn đã thay đổi; cần lập lại đề nghị.");
        validateReplacement(c,replacement(c,a.getPreviousProposalId()),a.getEffectiveOn());
    }
    private void requireFutureDate(Contract c,AmendmentPayload p) {
        if(p==null||p.changes()==null||immediate(p.changes())||p.effectiveOn()==null||!dates(c).contains(p.effectiveOn()))
            throw invalid("Mọi phụ lục chỉ áp dụng từ đầu kỳ thuê tương lai chưa chốt hóa đơn; không áp dụng ngay khi ký.");
    }
    /** Follow a returned-for-edit chain without discarding the signed scheduled document. */
    private ContractAmendment replacement(Contract c,String previousId) {
        Set<String> seen=new HashSet<>();
        while(previousId!=null) {
            if(!seen.add(previousId)||seen.size()>100)throw invalid("Chuỗi phụ lục thay thế không hợp lệ.");
            var a=proposal(c,previousId);
            if("SCHEDULED".equals(a.getStatus())&&a.getSignedAt()!=null)return a;
            if(a.getSignedAt()!=null)throw invalid("Phụ lục đã áp dụng hoặc đã được thay thế; không sửa hồi tố.");
            previousId=a.getPreviousProposalId();
        }
        return null;
    }
    private void validateReplacement(Contract c,ContractAmendment old,LocalDate date) {
        if(old==null)return;
        if(!"SCHEDULED".equals(old.getStatus())||old.isImmediateEffect()||!time.today().isBefore(old.getEffectiveOn())
                ||!Objects.equals(date,old.getEffectiveOn())||!dates(c).contains(date))
            throw invalid("Chỉ chỉnh lại phụ lục chưa áp dụng, giữ đúng ngày hiệu lực cũ và trước khi kỳ liên quan chốt hóa đơn.");
    }
    private String replacementBlocked(Contract c,ContractAmendment a) {
        if(!"SCHEDULED".equals(a.getStatus())||a.getSignedAt()==null)return "Chỉ phụ lục đã ký đủ và đang chờ áp dụng được chỉnh lại.";
        if(a.isImmediateEffect()||!time.today().isBefore(a.getEffectiveOn())||!dates(c).contains(a.getEffectiveOn()))return "Đã đến ngày hiệu lực hoặc kỳ liên quan đã chốt hóa đơn.";
        String reason=blocked(c,false);if(reason!=null)return reason;
        if(history(c).stream().anyMatch(p->PENDING.contains(p.getStatus())&&!p.getId().equals(a.getId())))return "Đang có đề xuất thay thế chờ xử lý.";
        return null;
    }
    private AmendmentTermsBuilder.Result buildTerms(ContractRevision base,Map<String,Object> changes,ContractAmendment replacing) {
        var result=builder.build(base,changes,replacing!=null);
        if(replacing==null)return result;
        // Stored comparisons may themselves describe a previous replacement, not the effective base.
        var old=new LinkedHashMap<String,AmendmentComparison>();
        builder.build(base,builder.map(replacing.getChangesSnapshot()),true).comparison().forEach(r->old.put(r.field(),r));
        var next=new LinkedHashMap<String,AmendmentComparison>();result.comparison().forEach(r->next.put(r.field(),r));
        var keys=new LinkedHashSet<>(old.keySet());keys.addAll(next.keySet());var diff=new ArrayList<AmendmentComparison>();
        for(String key:keys){var a=old.get(key);var b=next.get(key);String before=a==null?b.before():a.after(),after=b==null?a.before():b.after();
            if(!Objects.equals(before,after))diff.add(new AmendmentComparison(key,b==null?a.label():b.label(),before,after));}
        if(diff.isEmpty())throw invalid("Chưa có sửa đổi so với phụ lục đang chờ áp dụng.");
        return new AmendmentTermsBuilder.Result(result.terms(),diff);
    }
    /** Undo/rebuild future lease and parking segments even when a correction returns to base values. */
    private Map<String,Object> businessChanges(Map<String,Object> changes,ContractAmendment replacing) {
        if(replacing==null)return changes;
        var old=builder.map(replacing.getChangesSnapshot());var result=new LinkedHashMap<String,Object>(changes);
        for(String key:List.of("motorbikeCount","carCount"))if(old.containsKey(key))result.putIfAbsent(key,old.get(key));
        if(leaseChanged(old)&&!leaseChanged(changes)) {
            var fields=new LinkedHashMap<String,Object>(builder.object(changes.getOrDefault("fields",Map.of())));
            fields.put("lease",Map.of("endDateText",true));result.put("fields",fields);
        }
        return result;
    }
    private boolean leaseChanged(Map<String,Object> changes) {
        var fields=FreeFormAmendmentTerms.map(changes.get("fields"));
        return FreeFormAmendmentTerms.map(fields.get("lease")).keySet().stream().anyMatch(Set.of("startDateText","endDateText","durationMonths")::contains);
    }
    private LocalDate proposedEnd(Contract c,Map<String,Object> newTerms,Map<String,Object> changes) {
        var lease=builder.object(newTerms.get("lease"));
        return lease.get("endDateText")!=null?FreeFormAmendmentTerms.date(lease.get("endDateText")).plusDays(1):end(c);
    }
    private void validateBusinessTerms(Contract c,ContractRevision base,Map<String,Object> next,Map<String,Object> changes,LocalDate effectiveOn) {
        validateBusinessTerms(c,base,next,changes,effectiveOn,null);
    }
    private void validateBusinessTerms(Contract c,ContractRevision base,Map<String,Object> next,Map<String,Object> changes,LocalDate effectiveOn,String agreementId) {
        validateParking(c,next,changes,effectiveOn,agreementId);
        if(leaseChanged(changes)) {
            if(effectiveOn==null)throw invalid("Đổi thời hạn cần đầu kỳ tương lai.");
            var oldLease=builder.map(base.getLeaseSnapshot());var lease=builder.object(next.get("lease"));
            LocalDate start=FreeFormAmendmentTerms.date(lease.get("startDateText"));
            if(!Objects.equals(oldLease.get("startDateText"),lease.get("startDateText")) && !start.equals(effectiveOn))
                throw invalid("Ngày bắt đầu mới phải là ngày hiệu lực của phụ lục; không dời kỳ thuê đã thanh toán.");
            var rental=rentals.findById(c.getRentalRequestId()).orElseThrow(this::forbidden);LocalDate origin=rental.getMoveInDate();
            LocalDate end=proposedEnd(c,next,changes);
            int months=(end.getYear()-origin.getYear())*12+end.getMonthValue()-origin.getMonthValue();
            if(months<1||months>120||!origin.plusMonths(months).equals(end)||!end.isAfter(effectiveOn))throw invalid("Thời hạn mới phải khép đủ kỳ thuê, sau ngày hiệu lực và tối đa 120 tháng từ kỳ đầu.");
            if(invoices.findByContractIdOrderByPeriodIndexDesc(c.getId()).stream().anyMatch(b->b.getStatus()!=MonthlyInvoiceStatus.DRAFT
                    && (b.getPeriodEndExclusive().isAfter(end)||b.getRentPeriodEndExclusive()!=null&&b.getRentPeriodEndExclusive().isAfter(end))))
                throw invalid("Thời hạn mới cắt vào kỳ chi phí hoặc tiền thuê đã phát hành; không được sửa lịch sử.");
        }
        var financial=FreeFormAmendmentTerms.map(FreeFormAmendmentTerms.map(changes.get("fields")).get("financial"));
        if(financial.containsKey("depositAmountValue") && deposits!=null) {
            var deposit=deposits.findByRentalRequestIdForUpdate(c.getRentalRequestId()).orElse(null);
            var expected=FreeFormAmendmentTerms.money(builder.map(base.getFinancialSnapshot()).getOrDefault("depositAmountValue","0"),true);
            if(deposit==null && expected.signum()>0)throw invalid("Không tìm thấy sổ cọc đã thu; cần đối soát trước khi điều chỉnh cọc.");
            if(deposit!=null && (deposit.getStatus()!=com.hs.payment.model.constant.DepositStatus.HELD || deposit.getHeldAmount().compareTo(expected)!=0))
                throw invalid("Cần hoàn tất đối soát cọc hiện tại trước khi thay đổi số tiền cọc.");
            var delta=FreeFormAmendmentTerms.money(builder.object(next.get("financial")).get("depositAmountValue"),true).subtract(expected);
            if(delta.signum()!=0 && payments!=null)payments.validateDepositAdjustmentRecipient(delta.signum()>0?c.getLandlordId():c.getTenantId(),delta.signum()<0);
        }
    }
    private void validateParking(Contract c,Map<String,Object> next,Map<String,Object> changes,LocalDate effectiveOn,String agreementId) {
        if (!vehiclesChanged(changes) && !leaseChanged(changes)) return;
        var tenant=builder.object(next.get("tenant"));
        parking.validateAmendmentCapacity(c.getId(),agreementId,c.getListingId(),effectiveOn,proposedEnd(c,next,changes),
                count(tenant.get("motorbikeCount"),0,100),count(tenant.get("carCount"),0,100));
    }
    private void createDepositDelta(Contract c,ContractAmendment a) {
        var changes=builder.map(a.getChangesSnapshot());
        if(!FreeFormAmendmentTerms.map(FreeFormAmendmentTerms.map(changes.get("fields")).get("financial")).containsKey("depositAmountValue"))return;
        var old=builder.map(terms.require(a.getBaseRevisionId()).getFinancialSnapshot());var next=builder.map(terms.require(a.getTermsRevisionId()).getFinancialSnapshot());
        var delta=FreeFormAmendmentTerms.money(next.get("depositAmountValue"),true).subtract(FreeFormAmendmentTerms.money(old.getOrDefault("depositAmountValue","0"),true));
        if(delta.signum()==0)return;
        if(deposits.findByRentalRequestIdForUpdate(c.getRentalRequestId()).isEmpty())deposits.save(com.hs.payment.model.DepositRecord.builder()
                .rentalRequestId(c.getRentalRequestId()).contractId(c.getId()).initialPaymentRequestId(c.getRentalPaymentId()!=null?c.getRentalPaymentId():payments.findByRentalRequestIdAndType(c.getRentalRequestId(),com.hs.payment.model.constant.PaymentType.INITIAL).orElseThrow(()->invalid("Không tìm thấy khoản thanh toán ban đầu.")).getId()).landlordId(c.getLandlordId()).tenantId(c.getTenantId())
                .originalAmount(java.math.BigDecimal.ZERO).heldAmount(java.math.BigDecimal.ZERO).refundableAmount(java.math.BigDecimal.ZERO).status(com.hs.payment.model.constant.DepositStatus.HELD).build());
        payments.createDepositAdjustment(a.getId(),c.getId(),c.getRentalRequestId(),c.getListingId(),c.getLandlordId(),c.getTenantId(),delta,
                a.getEffectiveOn().plusDays(4).atTime(23,59,59).atZone(BillingTime.ZONE).toInstant());
    }
    private void cancelAgreement(ContractAmendment a) {
        if (a.getAgreementContractId() == null) return;
        contracts.findByIdForUpdate(a.getAgreementContractId()).ifPresent(child -> { child.setStatus(ContractStatus.CANCELLED); contracts.save(child); });
        for (var request : signatures.findByContractIdOrderByCreatedAtDesc(a.getAgreementContractId())) {
            if (Set.of(SignatureRequestStatus.CREATED, SignatureRequestStatus.PENDING_USER_CONFIRMATION,
                    SignatureRequestStatus.PROVIDER_SIGNED, SignatureRequestStatus.EMBEDDING).contains(request.getStatus())) {
                request.setStatus(SignatureRequestStatus.CANCELLED); request.setFailureCode("AMENDMENT_CLOSED");
                request.setFailureMessage("Phụ lục đã rút, hết hạn hoặc yêu cầu chỉnh sửa. Chữ ký chưa hoàn tất không được áp dụng."); signatures.save(request);
            }
        }
        parking.releaseRenewalHold(a.getAgreementContractId());
    }
    private AmendmentResponse response(Contract c) {
        ContractRevision current = terms.at(c, time.today());
        String reason = blocked(c, true);
        return new AmendmentResponse(time.now(), reason == null, reason, current.getId(), builder.snapshot(current), dates(c),
                history(c).stream().map(a -> new AmendmentResponse.Proposal(a.getId(), a.getPreviousProposalId(), a.getProposerId(), a.getRecipientId(),
                        a.getStatus(), a.getEffectiveOn(), a.isImmediateEffect(), a.getReason(), a.getResponseNote(), a.getSubmittedAt(), a.getRespondBy(), a.getSignBy(),
                        a.getSignedAt(), a.getAppliedAt(), a.getAgreementContractId(), comparison(a), builder.map(a.getChangesSnapshot()), readIds(a.getEvidenceSnapshot()),
                        a.getTenantRequestId(), a.getAgreementContractId() == null ? null : contracts.findById(a.getAgreementContractId()).map(child -> child.getStatus().name()).orElse(null), a.getDecidedAt(),
                        replacementBlocked(c,a)==null,replacementBlocked(c,a))).toList(), null);
    }
    public List<AmendmentComparison> comparison(ContractAmendment a) { try { return mapper.readValue(a.getComparisonSnapshot(), new TypeReference<>() {}); } catch (Exception e) { throw invalid("Không đọc được bảng thay đổi."); } }
    private List<LocalDate> dates(Contract c) {
        var rental = rentals.findById(c.getRentalRequestId()).orElseThrow(this::forbidden);
        List<LocalDate> result = new ArrayList<>();
        int months = c.getEffectiveLeaseMonths() == null ? rental.getLeaseMonths() : c.getEffectiveLeaseMonths();
        List<MonthlyInvoice> bills = invoices.findByContractIdOrderByPeriodIndexDesc(c.getId());
        for (int n = 1; n < months; n++) {
            LocalDate date = rental.getMoveInDate().plusMonths(n);
            if (time.now().isBefore(cutoff(date)) && bills.stream().noneMatch(b -> b.getStatus() != MonthlyInvoiceStatus.DRAFT
                    && (b.getPeriodStart().compareTo(date) >= 0 || b.getPeriodEndExclusive().compareTo(date) >= 0
                        || b.getRentPeriodEndExclusive()!=null && b.getRentPeriodEndExclusive().isAfter(date)))) result.add(date);
        }
        return result;
    }
    private String blocked(Contract c, boolean includePending) {
        if (c.getStatus() != ContractStatus.ACTIVE || !time.today().isBefore(end(c))) return "Chỉ hợp đồng đang có hiệu lực mới được điều chỉnh.";
        if (!"SMARTCA".equalsIgnoreCase(signatureMode)) return "Phụ lục cần cấu hình ký số SmartCA.";
        if (dates(c).isEmpty()) return "Không còn đầu kỳ thuê tương lai chưa chốt hóa đơn để áp dụng phụ lục.";
        if(paymentRecords!=null && paymentRecords.findAllByContractIdAndType(c.getId(),com.hs.payment.model.constant.PaymentType.DEPOSIT_ADJUSTMENT).stream()
                .anyMatch(p->p.getStatus()!=com.hs.payment.model.constant.PaymentStatus.CONFIRMED))return "Hãy hoàn tất khoản chênh lệch cọc trước khi lập phụ lục tiếp theo.";
        if (c.getTerminationProposedAt() != null && c.getTerminationCancelledAt() == null && c.getTerminationDeclinedAt() == null)
            return "Hợp đồng đang xử lý chấm dứt.";
        if (renewals.findByContractIdOrderByRequestedAtDesc(c.getId()).stream().anyMatch(r -> Set.of("REQUESTED", "APPROVED").contains(r.getStatus()) && time.now().isBefore(r.getSignBy())))
            return "Hãy hoàn tất yêu cầu gia hạn đang xử lý trước.";
        if (includePending && history(c).stream().anyMatch(a -> PENDING.contains(a.getStatus()))) return "Hãy xử lý phụ lục hiện tại trước khi tạo yêu cầu mới.";
        return null;
    }
    private LocalDate end(Contract c) { var r = rentals.findById(c.getRentalRequestId()).orElseThrow(this::forbidden); return r.getMoveInDate().plusMonths(c.getEffectiveLeaseMonths() == null ? r.getLeaseMonths() : c.getEffectiveLeaseMonths()); }
    private Instant cutoff(LocalDate d) { return d.atStartOfDay(BillingTime.ZONE).toInstant(); }
    private Instant min(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private String other(Contract c, String actor) { return actor.equals(c.getLandlordId()) ? c.getTenantId() : c.getLandlordId(); }
    private boolean vehiclesChanged(Map<String,Object> changes) { return changes.containsKey("motorbikeCount") || changes.containsKey("carCount"); }
    private boolean immediate(Map<String,Object> changes) { return changes != null && Boolean.TRUE.equals(changes.get("applyImmediately")); }
    private List<ContractAmendment> history(Contract c) { return amendments.findByContractIdOrderByCreatedAtDesc(c.getId()); }
    private ContractAmendment proposal(Contract c, String id) { return amendments.findById(id).filter(a -> c.getId().equals(a.getContractId())).orElseThrow(this::forbidden); }
    private Contract root(String id, String actor) { Contract c = contracts.findByIdForUpdate(id).orElseThrow(this::forbidden); if (com.hs.contract.deposit.DepositAgreementService.isDeposit(c) || c.getParentContractId() != null || actor == null || !actor.equals(c.getLandlordId()) && !actor.equals(c.getTenantId())) throw forbidden(); return c; }
    private AppException forbidden() { return new AppException(ContractErrorCode.CONTRACT_FORBIDDEN); }
    private List<String> readIds(String json) { try { return json == null ? List.of() : mapper.readValue(json, new TypeReference<>() {}); } catch (Exception e) { throw invalid("Không đọc được minh chứng."); } }
    private List<String> validateEvidence(Contract c, String actor, List<String> ids) {
        if (ids == null) return List.of();
        if (ids.size() > 5 || new HashSet<>(ids).size() != ids.size()) throw invalid("Tối đa 5 minh chứng.");
        for (String id : ids) {
            var f = files.findById(id).orElseThrow(this::forbidden);
            if (!actor.equals(f.getOwnerId()) || !c.getId().equals(f.getReferenceId()) || !"CONTRACT_AMENDMENT_EVIDENCE".equals(f.getReferenceType())
                    || f.getPurpose() != StoragePurpose.GENERAL || f.getVisibility() != StorageVisibility.PRIVATE || f.getStatus() != StorageStatus.READY
                    || f.getSizeBytes() == null || f.getSizeBytes() <= 0 || f.getSizeBytes() > 15 * 1024 * 1024
                    || !Set.of("image/jpeg", "image/png", "image/webp", "application/pdf").contains(Objects.toString(f.getContentType(), ""))) throw forbidden();
        }
        return List.copyOf(ids);
    }
}
