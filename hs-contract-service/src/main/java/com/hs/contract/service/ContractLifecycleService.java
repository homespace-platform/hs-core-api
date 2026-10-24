package com.hs.contract.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.dto.billing.*;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.*;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.listing.service.ListingStatusService;
import com.hs.listing.service.ParkingReservationService;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.*;
import com.hs.payment.service.PaymentRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.domain.PageRequest;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import com.hs.storage.repository.StorageObjectRepository;
import com.hs.storage.model.constant.*;
import org.springframework.beans.factory.annotation.Value;

/** Root lock serializes renewal signatures, expiration, settlement and creation of transfer obligations. */
@Service @RequiredArgsConstructor @Slf4j
public class ContractLifecycleService {
    @org.springframework.beans.factory.annotation.Autowired
    private ContractAmendmentService amendments;
    @org.springframework.beans.factory.annotation.Autowired
    private ContractTermsResolver terms;
    private final ContractRepository contracts;
    private final ContractRevisionRepository revisions;
    private final ContractRenewalRepository renewals;
    private final ContractClosureRepository closures;
    private final RentalRequestRepository rentals;
    private final MonthlyInvoiceRepository invoices;
    private final DepositRecordRepository deposits;
    private final PaymentRequestRepository paymentRepository;
    private final PaymentRequestService payments;
    private final SignatureRequestRepository signatures;
    private final ContractDocumentRepository documents;
    private final ListingStatusService listings;
    private final ParkingReservationService parking;
    private final ObjectMapper mapper;
    private final BillingTime time;
    private final PlatformTransactionManager transactionManager;
    private final StorageObjectRepository storageObjects;
    @Value("${homespace.closure.response-hours:48}")
    private long closureResponseHours = 48;
    private static final Set<String> PENDING_RENEWALS=Set.of("REQUESTED","APPROVED");
    private static final BigDecimal ZERO=BigDecimal.ZERO;

    @Transactional public ContractLifecycleResponse get(String id,String actor) {
        Contract c=root(id,actor); syncLocked(c); return response(c,actor);
    }
    @Transactional public ContractLifecycleResponse requestRenewal(String id,String actor,RenewalPayload payload) {
        Contract c=root(id,actor); tenant(c,actor); syncLocked(c);
        if(payload==null || payload.months()<1 || payload.months()>36 || payload.note()==null
                || payload.note().trim().length()<10 || payload.note().length()>1000) throw invalid();
        if(!canRequest(c) || months(c)+payload.months()>120) throw notAllowed();
        LocalDate end=end(c); Instant signBy=signBy(c);
        renewals.save(ContractRenewal.builder().contractId(id).months(payload.months()).status("REQUESTED")
                .startsOn(end).endExclusive(rental(c).getMoveInDate().plusMonths(months(c)+payload.months())).requestedAt(time.now())
                .respondBy(min(time.now().plus(Duration.ofHours(48)),signBy)).signBy(signBy).note(payload.note().trim()).build());
        return response(c,actor);
    }
    @Transactional public ContractLifecycleResponse decideRenewal(String id,String renewalId,String actor,boolean approve) {
        Contract c=root(id,actor); owner(c,actor); syncLocked(c);
        ContractRenewal r=renewals.findById(renewalId).filter(x->id.equals(x.getContractId())).orElseThrow(this::notAllowed);
        if(!"REQUESTED".equals(r.getStatus()) || c.getStatus()!=ContractStatus.ACTIVE || terminationActive(c)
                || !end(c).equals(r.getStartsOn()) || !time.now().isBefore(r.getRespondBy())) throw notAllowed();
        r.setDecidedAt(time.now());
        if(!approve) r.setStatus("REJECTED");
        else {
            ContractRevision src=revision(c);
            Contract agreement=contracts.save(Contract.builder().contractNumber(c.getContractNumber()+"-GH-"+UUID.randomUUID().toString().substring(0,8))
                    .rentalRequestId(c.getRentalRequestId()).listingId(c.getListingId()).branchId(c.getBranchId())
                    .landlordId(c.getLandlordId()).tenantId(c.getTenantId()).templateId(c.getTemplateId())
                    .templateVersionId(c.getTemplateVersionId()).parentContractId(id)
                    .paymentStatus(c.getPaymentStatus()).rentalPaymentId(c.getRentalPaymentId()).paidAt(c.getPaidAt())
                    .status(ContractStatus.DRAFT).build());
            Map<String,Object> lease=new LinkedHashMap<>(read(src.getLeaseSnapshot()));
            DateTimeFormatter fmt=DateTimeFormatter.ofPattern("dd/MM/yyyy");
            lease.put("startDateText",r.getStartsOn().format(fmt)); lease.put("endDateText",r.getEndExclusive().minusDays(1).format(fmt));
            lease.put("durationMonths",r.getMonths()); lease.put("durationText",r.getMonths()+" tháng gia hạn");
            lease.put("handoverDateText",r.getStartsOn().format(fmt));
            String terms="VĂN BẢN GIA HẠN liên kết hợp đồng "+c.getContractNumber()+". Hai bên gia hạn từ "
                    +r.getStartsOn().format(fmt)+" đến hết "+r.getEndExclusive().minusDays(1).format(fmt)
                    +"; giữ nguyên giá thuê và chính sách đã ký. Tiền cọc đã nhận theo hợp đồng gốc được chuyển tiếp, không thu lại. "
                    +"Tiền thuê kỳ gia hạn được thu qua hóa đơn của hợp đồng gốc; không coi khoản thanh toán ban đầu đã ghi nhận là tiền thuê mới.\n"
                    +Objects.toString(src.getSpecialTerms(),"");
            ContractRevision rev=revisions.save(ContractRevision.builder().contract(agreement).revisionNumber(1)
                    .templateVersionId(src.getTemplateVersionId()).landlordSnapshot(src.getLandlordSnapshot()).tenantSnapshot(src.getTenantSnapshot())
                    .propertySnapshot(src.getPropertySnapshot()).leaseSnapshot(write(lease)).financialSnapshot(src.getFinancialSnapshot())
                    .chargesSnapshot(src.getChargesSnapshot()).equipmentSnapshot(src.getEquipmentSnapshot()).initialMetersSnapshot(src.getInitialMetersSnapshot())
                    .initialPaymentSnapshot(src.getInitialPaymentSnapshot()).amenitiesSnapshot(src.getAmenitiesSnapshot()).policiesSnapshot(src.getPoliciesSnapshot())
                    .specialTerms(terms).schemaVersion(src.getSchemaVersion()).revisionNote("Gia hạn HĐ "+id+"; không sửa bản ký gốc").build());
            agreement.setCurrentRevisionId(rev.getId()); contracts.save(agreement);
            parking.holdForRenewal(c.getId(),agreement.getId(),r.getStartsOn(),r.getEndExclusive(),r.getSignBy());
            r.setAgreementContractId(agreement.getId()); r.setStatus("APPROVED");
        }
        renewals.save(r); return response(c,actor);
    }

    /** Called only by verified SmartCA embedding, not a public 'mark signed' endpoint. */
    @Transactional public void applyRenewalAfterSignatures(Contract agreement) {
        if ("AMENDMENT".equals(agreement.getAgreementKind())) { amendments.signed(agreement); return; }
        Contract parent=contracts.findByIdForUpdate(agreement.getParentContractId()).orElseThrow(this::notAllowed);
        ContractRenewal r=renewals.findByAgreementContractId(agreement.getId()).orElseThrow(this::notAllowed);
        if("APPLIED".equals(r.getStatus())) return;
        if(parent.getStatus()!=ContractStatus.ACTIVE || terminationActive(parent) || !"APPROVED".equals(r.getStatus())
                || !time.now().isBefore(r.getSignBy()) || !end(parent).equals(r.getStartsOn())) throw notAllowed();
        for(SignerRole role:SignerRole.values()) {
            if(signatures.findFirstByContractIdAndSignerRoleOrderByCreatedAtDesc(agreement.getId(),role)
                    .filter(s->s.getStatus()==SignatureRequestStatus.SIGNED && agreement.getCurrentRevisionId().equals(s.getRevisionId())).isEmpty()) throw notAllowed();
        }
        if(documents.findByContractIdAndRevisionId(agreement.getId(),agreement.getCurrentRevisionId()).stream()
                .noneMatch(d->d.getPurpose()==DocumentPurpose.SIGNED_FINAL && d.getStatus()==DocumentGenerationStatus.READY)) throw notAllowed();
        parking.activateRenewalHold(agreement.getId(),parent.getId());
        parent.setEffectiveLeaseMonths(months(parent)+r.getMonths()); contracts.save(parent);
        r.setStatus("APPLIED"); r.setAppliedAt(time.now()); renewals.save(r);
        log.info("LEASE_RENEWAL_APPLIED contract={} agreement={} months={}",parent.getId(),agreement.getId(),r.getMonths());
    }
    public void validateAgreementSigning(Contract agreement) {
        if(agreement.getParentContractId()==null) return;
        if ("AMENDMENT".equals(agreement.getAgreementKind())) { amendments.validateSigning(agreement); return; }
        ContractRenewal r=renewals.findByAgreementContractId(agreement.getId()).orElseThrow(this::notAllowed);
        Contract parent=contracts.findById(agreement.getParentContractId()).orElseThrow(this::notAllowed);
        if(!"APPROVED".equals(r.getStatus()) || parent.getStatus()!=ContractStatus.ACTIVE || terminationActive(parent) || !time.now().isBefore(r.getSignBy())) throw notAllowed();
    }

    @Transactional public ContractLifecycleResponse proposeClosure(String id,String actor,ClosurePayload p) {
        Contract c=root(id,actor); owner(c,actor); syncLocked(c);
        if(c.getStatus()!=ContractStatus.EXPIRED || p==null || !p.vacantPossessionConfirmed()
                || p.note()==null || p.note().trim().length()<10 || p.note().length()>2000 || !moneyValid(p.otherDeduction())) throw notAllowed();
        ContractClosure close=closures.findById(id).orElse(ContractClosure.builder().contractId(id).status("WAITING_HANDOVER").build());
        if(!Set.of("WAITING_HANDOVER","PROPOSED","DISPUTED").contains(close.getStatus())) throw notAllowed();
        if(p.expectedProposalVersion()!=null && p.expectedProposalVersion()!=close.getProposalVersion()) throw invalid();
        requirePaidBills(c);
        DepositRecord deposit=deposit(c);
        if(!Set.of(DepositStatus.HELD,DepositStatus.SETTLEMENT_PENDING,DepositStatus.DISPUTED).contains(deposit.getStatus())) throw notAllowed();
        MonthlyInvoice last=lastCostsBill(c);
        if(p.electricityFinal()!=null && !sameReading(p.electricityFinal(),last.getElectricityEnd())
                || p.waterFinal()!=null && !sameReading(p.waterFinal(),last.getWaterEnd()))
            throw new AppException(ContractErrorCode.LIFECYCLE_INVALID,"Điện/nước bổ sung phải được phát hành và thanh toán trong mục Hóa đơn, không trừ vào cọc.");
        List<ClosureDeduction> deductions=validateDeductions(c,actor,p);
        BigDecimal deducted=deductions.stream().map(ClosureDeduction::amount).reduce(ZERO,BigDecimal::add);
        preserveLegacyProposal(close);
        close.setElectricityFinal(last.getElectricityEnd()); close.setWaterFinal(last.getWaterEnd());
        close.setUtilityAdjustment(ZERO); close.setOtherDeduction(deducted);
        close.setDeductionsSnapshot(write(deductions));
        close.setRefundAmount(deposit.getHeldAmount().subtract(deducted).max(ZERO));
        close.setBalanceAmount(deducted.subtract(deposit.getHeldAmount()).max(ZERO));
        close.setNote(p.note().trim()); close.setProposedAt(time.now()); close.setProposalVersion(close.getProposalVersion()+1); close.setStatus("PROPOSED");
        responseDeadline(close);
        appendClosureEvent(close,"PROPOSED",actor,close.getNote(),List.of());
        closures.save(close); deposit.setStatus(DepositStatus.SETTLEMENT_PENDING); deposits.save(deposit);
        return response(c,actor);
    }
    @Transactional public ContractLifecycleResponse acceptClosure(String id,String actor,AcceptClosurePayload p) {
        Contract c=root(id,actor); tenant(c,actor); syncLocked(c);
        ContractClosure close=closures.findById(id).orElseThrow(this::notAllowed);
        if(p==null || !p.handoverConfirmed() || !p.deductionsAccepted() || p.proposalVersion()!=close.getProposalVersion()) throw invalid();
        if(!"PROPOSED".equals(close.getStatus()) || c.getStatus()!=ContractStatus.EXPIRED) throw notAllowed();
        requirePaidBills(c);
        if(close.getUtilityAdjustment()!=null && close.getUtilityAdjustment().signum()!=0)
            throw new AppException(ContractErrorCode.LIFECYCLE_INVALID,"Đề xuất cũ có điện/nước trừ vào cọc; cần đối soát và lập lại sau khi thanh toán hóa đơn.");
        DepositRecord deposit=deposit(c); if(deposit.getStatus()!=DepositStatus.SETTLEMENT_PENDING) throw notAllowed();
        preserveLegacyProposal(close);
        Object configured=read(revision(c).getPoliciesSnapshot()).get("depositRefundDays");
        int days=configured==null?15:Integer.parseInt(String.valueOf(configured));
        if(days<1 || days>90) throw invalid();
        close.setAcceptedAt(time.now()); close.setRefundDueAt(time.now().plus(Duration.ofDays(days)));
        close.setRespondBy(null); close.setResponseOverdueAt(null);
        appendClosureEvent(close,"ACCEPTED",actor,"Người thuê đồng ý đề xuất; chưa xác nhận nhận tiền hoàn.",List.of());
        if(close.getRefundAmount().signum()>0) {
            PaymentRequest refund=payments.createContractSettlement(id,c.getRentalRequestId(),c.getListingId(),c.getLandlordId(),c.getTenantId(),
                    close.getRefundAmount(),close.getRefundDueAt(),true,"Hoàn cọc sau quyết toán hợp đồng "+c.getContractNumber());
            close.setRefundPaymentId(refund.getId()); deposit.setRefundPaymentRequestId(refund.getId()); deposit.setStatus(DepositStatus.REFUND_DUE);
            deposit.setHeldAmount(close.getRefundAmount()); deposit.setRefundableAmount(close.getRefundAmount()); close.setStatus("REFUND_PENDING");
        } else {
            deposit.setHeldAmount(ZERO); deposit.setRefundableAmount(ZERO); deposit.setStatus(DepositStatus.RETAINED_BY_LANDLORD);
            deposit.setRetainedAt(time.now()); deposit.setRetainedReason(close.getNote());
            if(close.getBalanceAmount().signum()>0) {
                PaymentRequest balance=payments.createContractSettlement(id,c.getRentalRequestId(),c.getListingId(),c.getTenantId(),c.getLandlordId(),
                        close.getBalanceAmount(),close.getRefundDueAt(),false,"Khoản quyết toán còn thiếu của hợp đồng "+c.getContractNumber());
                close.setBalancePaymentId(balance.getId()); close.setStatus("BALANCE_PENDING");
            } else close(c,close);
        }
        deposits.save(deposit); closures.save(close);
        // Both parties explicitly confirmed vacant possession; financial obligation remains independently visible.
        listings.releaseRentedAfterTermination(c.getListingId(),c.getLandlordId()); parking.releaseReservationsForContract(id);
        return response(c,actor);
    }
    @Transactional public ContractLifecycleResponse disputeClosure(String id,String actor,DisputeClosurePayload p) {
        Contract c=root(id,actor); tenant(c,actor); ContractClosure close=closures.findById(id).orElseThrow(this::notAllowed);
        if(c.getStatus()!=ContractStatus.EXPIRED || p==null || !"PROPOSED".equals(close.getStatus()) || p.proposalVersion()!=close.getProposalVersion()
            || p.reason()==null || p.reason().trim().length()<10 || p.reason().length()>2000) throw invalid();
        List<String> evidence=validateEvidence(c,actor,p.evidenceIds());
        preserveLegacyProposal(close);
        close.setStatus("DISPUTED"); responseDeadline(close);
        appendClosureEvent(close,"DISPUTED",actor,p.reason().trim(),evidence);
        closures.save(close); DepositRecord d=deposit(c); d.setStatus(DepositStatus.DISPUTED); deposits.save(d);
        return response(c,actor);
    }

    /** Immutable supplementary invoice; all utilities/extras go through normal proof and receipt confirmation. */
    @Transactional public ContractLifecycleResponse issueFinalCosts(String id,String actor,FinalCostsPayload p) {
        Contract c=root(id,actor); owner(c,actor); syncLocked(c);
        ContractClosure close=closures.findById(id).orElseThrow(this::notAllowed);
        if(c.getStatus()!=ContractStatus.EXPIRED || close.getAcceptedAt()!=null || p==null) throw notAllowed();
        try { UUID.fromString(p.requestId()); } catch(RuntimeException ex) { throw invalid(); }
        List<MonthlyInvoice> history=invoices.findByContractIdOrderByPeriodIndexDesc(id);
        Optional<MonthlyInvoice> duplicate=history.stream().filter(b->p.requestId().equals(b.getSupplementRequestId())).findFirst();
        if(duplicate.isPresent()) {
            if(!write(p).equals(duplicate.get().getSupplementRequestSnapshot())) throw invalid();
            return response(c,actor);
        }
        MonthlyInvoice original=invoices.findByContractIdAndPeriodIndex(id,months(c)-1).orElseThrow(this::notAllowed);
        if(original.getStatus()==MonthlyInvoiceStatus.DRAFT || history.stream().anyMatch(b->b.isFinalCostSupplement() && !paidBill(b))) throw notAllowed();
        MonthlyInvoice previous=lastCostsBill(c);
        List<InvoiceLine> lines=new ArrayList<>();
        BigDecimal electric=rate(c,"PER_KWH"),water=rate(c,"PER_M3");
        BigDecimal electricityEnd=p.electricityEnd()==null?previous.getElectricityEnd():p.electricityEnd();
        BigDecimal waterEnd=p.waterEnd()==null?previous.getWaterEnd():p.waterEnd();
        BigDecimal electricAmount=adjustment(electricityEnd,previous.getElectricityEnd(),electric);
        BigDecimal waterAmount=adjustment(waterEnd,previous.getWaterEnd(),water);
        if(electricAmount.signum()>0) lines.add(new InvoiceLine("ELECTRICITY","Điện bổ sung kỳ cuối",electricityEnd.subtract(previous.getElectricityEnd()),electric,electricAmount));
        if(waterAmount.signum()>0) lines.add(new InvoiceLine("WATER","Nước bổ sung kỳ cuối",waterEnd.subtract(previous.getWaterEnd()),water,waterAmount));
        if(p.extraCharges()!=null) {
            if(p.extraCharges().size()>50) throw invalid();
            for(var extra:p.extraCharges()) {
                if(extra==null || extra.description()==null || extra.description().trim().isEmpty() || extra.description().length()>150
                        || !moneyValid(extra.amount()) || extra.amount().signum()<=0) throw invalid();
                lines.add(new InvoiceLine("EXTRA",extra.description().trim(),BigDecimal.ONE,extra.amount(),extra.amount()));
            }
        }
        BigDecimal total=lines.stream().map(InvoiceLine::amount).reduce(ZERO,BigDecimal::add);
        if(total.signum()<=0) throw new AppException(ContractErrorCode.LIFECYCLE_INVALID,"Không có chi phí bổ sung để phát hành hóa đơn.");
        int index=history.stream().mapToInt(MonthlyInvoice::getPeriodIndex).max().orElse(months(c)-1)+1;
        Instant due=MonthlyBillingSchedule.dueAt(time.today());
        MonthlyInvoice bill=invoices.saveAndFlush(MonthlyInvoice.builder().contractId(id).periodIndex(index)
                .finalCostSupplement(true).sourceInvoiceId(original.getId()).supplementRequestId(p.requestId()).supplementRequestSnapshot(write(p))
                .periodStart(original.getPeriodStart()).periodEndExclusive(original.getPeriodEndExclusive())
                .electricityStart(previous.getElectricityEnd()).electricityEnd(electricityEnd).waterStart(previous.getWaterEnd()).waterEnd(waterEnd)
                .status(MonthlyInvoiceStatus.UNPAID).issuedAt(time.now()).dueAt(due).originalDueAt(due)
                .lineItemsSnapshot(write(lines)).totalAmount(total).baseAmount(total).lateFeeAmount(ZERO).build());
        PaymentRequest payment=payments.createMonthlyPayment(bill.getId(),id,c.getRentalRequestId(),c.getListingId(),c.getTenantId(),c.getLandlordId(),total,bill.getLineItemsSnapshot(),due);
        bill.setPaymentRequestId(payment.getId()); invoices.save(bill);
        // A changed cost ledger invalidates a proposal already read by the tenant.
        preserveLegacyProposal(close);
        appendClosureEvent(close,"COSTS_ADDED",actor,"Phát hành hóa đơn bổ sung; cần thanh toán và lập lại đề xuất cọc.",List.of());
        close.setStatus("WAITING_HANDOVER"); close.setRespondBy(null); close.setResponseOverdueAt(null);
        close.setProposalVersion(close.getProposalVersion()+1); closures.save(close);
        DepositRecord d=deposit(c); d.setStatus(DepositStatus.HELD); deposits.save(d);
        return response(c,actor);
    }

    @Scheduled(fixedDelayString="${homespace.billing.sync-delay-ms:60000}") public void scheduledSync() {
        // Use stable IDs: changing ACTIVE -> EXPIRED -> CLOSED must not shift paginated results.
        String afterId=null;
        while(true) {
            var ids=contracts.findLifecycleIdsAfter(Set.of(ContractStatus.ACTIVE,ContractStatus.EXPIRED),afterId,PageRequest.of(0,100));
            if(ids.isEmpty()) break;
            for(String id:ids) try { new TransactionTemplate(transactionManager).executeWithoutResult(tx-> {
                Contract c=contracts.findByIdForUpdate(id).orElse(null); if(c!=null) syncLocked(c);
            }); } catch(RuntimeException e) { log.error("LEASE_LIFECYCLE_SYNC_FAILED contract={}",id,e); }
            afterId=ids.get(ids.size()-1);
        }
    }
    private void syncLocked(Contract c) {
        if (amendments != null) amendments.syncLocked(c);
        Instant now=time.now();
        // Payment confirmation is authoritative; avoid waiting for the next billing scheduler tick.
        if(c.getStatus()==ContractStatus.ACTIVE || c.getStatus()==ContractStatus.EXPIRED)
            for(MonthlyInvoice b:invoices.findByContractIdOrderByPeriodIndexDesc(c.getId())) {
                if(b.getStatus()==MonthlyInvoiceStatus.DRAFT || b.getStatus()==MonthlyInvoiceStatus.PAID || b.getStatus()==MonthlyInvoiceStatus.ROLLED_OVER || b.getPaymentRequestId()==null) continue;
                paymentRepository.findById(b.getPaymentRequestId()).filter(p->p.getStatus()==PaymentStatus.CONFIRMED).ifPresent(p->{
                    b.setStatus(MonthlyInvoiceStatus.PAID); b.setPaidAt(p.getConfirmedAt()); b.setResolutionMode(null);
                    if(b.getExtensionRequestsSnapshot()!=null && !b.getExtensionRequestsSnapshot().isBlank()) {
                        try {
                            List<PaymentExtension> extensions=mapper.readValue(b.getExtensionRequestsSnapshot(),new TypeReference<>(){});
                            b.setExtensionRequestsSnapshot(write(extensions.stream().map(e->e.status()==PaymentExtension.Status.PENDING
                                    ?e.decide(PaymentExtension.Status.CLOSED,now,"SYSTEM",null):e).toList()));
                        } catch(Exception ex) { throw invalid(); }
                    }
                    invoices.save(b);
                    if(Objects.equals(b.getId(),c.getTerminationProposalInvoiceId()) && terminationActive(c)) {
                        c.setTerminationCancelledAt(now); contracts.save(c);
                    }
                });
            }
        for(ContractRenewal r:renewals.findByContractIdOrderByRequestedAtDesc(c.getId())) {
            if((PENDING_RENEWALS.contains(r.getStatus()) && c.getStatus()!=ContractStatus.ACTIVE)
                    || ("REQUESTED".equals(r.getStatus()) && !now.isBefore(r.getRespondBy()))
                    || ("APPROVED".equals(r.getStatus()) && !now.isBefore(r.getSignBy()))) {
                r.setStatus("EXPIRED"); renewals.save(r);
                if(r.getAgreementContractId()!=null) contracts.findByIdForUpdate(r.getAgreementContractId()).ifPresent(a->{
                    if(a.getStatus()!=ContractStatus.ACTIVE) { a.setStatus(ContractStatus.CANCELLED); contracts.save(a); }
                });
                if(r.getAgreementContractId()!=null) parking.releaseRenewalHold(r.getAgreementContractId());
            }
        }
        if(c.getStatus()==ContractStatus.ACTIVE && !time.today().isBefore(end(c))) {
            c.setStatus(ContractStatus.EXPIRED); contracts.save(c);
            if(closures.findById(c.getId()).isEmpty()) closures.save(ContractClosure.builder().contractId(c.getId()).status("WAITING_HANDOVER").build());
            log.info("LEASE_EXPIRED contract={} end={}",c.getId(),end(c));
        }
        closures.findById(c.getId()).ifPresent(s->{
            if(Set.of("PROPOSED","DISPUTED").contains(s.getStatus()) && s.getRespondBy()!=null
                    && !now.isBefore(s.getRespondBy()) && s.getResponseOverdueAt()==null) {
                preserveLegacyProposal(s); s.setResponseOverdueAt(now);
                appendClosureEvent(s,"RESPONSE_OVERDUE","SYSTEM","Quá hạn phản hồi; cần hai bên đối soát. Không tự đồng ý hoặc tạo QR.",List.of());
                closures.save(s);
            }
            String paymentId=s.getRefundPaymentId()!=null?s.getRefundPaymentId():s.getBalancePaymentId();
            if(paymentId==null || s.getClosedAt()!=null) return;
            payments.syncContractSettlementDeadline(paymentId,now);
            PaymentRequest payment=paymentRepository.findById(paymentId).orElseThrow(this::notAllowed);
            if(payment.getStatus()==PaymentStatus.CONFIRMED) { close(c,s); closures.save(s); }
            else if(s.getRefundDueAt()!=null && now.isAfter(s.getRefundDueAt()) && s.getOverdueLoggedAt()==null) {
                preserveLegacyProposal(s);
                appendClosureEvent(s,"PAYMENT_OVERDUE","SYSTEM","Khoản quyết toán quá hạn; nghĩa vụ thanh toán vẫn còn, cần đối soát.",List.of());
                s.setOverdueLoggedAt(now); closures.save(s); log.warn("LEASE_SETTLEMENT_OVERDUE contract={} payment={}",c.getId(),paymentId);
            }
        });
    }
    private void close(Contract c,ContractClosure s) {
        preserveLegacyProposal(s);
        appendClosureEvent(s,"CLOSED","SYSTEM","Đã hoàn tất nghĩa vụ quyết toán.",List.of());
        s.setStatus("CLOSED"); s.setClosedAt(time.now()); c.setStatus(ContractStatus.CLOSED); contracts.save(c);
    }
    private ContractLifecycleResponse response(Contract c,String actor) {
        ContractClosure close=closures.findById(c.getId()).orElse(null);
        if(close!=null) { close.setDeductions(closureDeductions(close)); close.setHistory(closureHistory(close)); }
        DepositRecord deposit=deposits.findByRentalRequestId(c.getRentalRequestId()).orElse(null);
        List<ContractRenewal> history=renewals.findByContractIdOrderByRequestedAtDesc(c.getId());
        history.forEach(r->{ if(r.getAgreementContractId()!=null) contracts.findById(r.getAgreementContractId()).ifPresent(a->r.setAgreementStatus(a.getStatus())); });
        return new ContractLifecycleResponse(time.now(),end(c),opens(c),signBy(c),actor.equals(c.getTenantId())&&canRequest(c),
                history,close,deposit==null?ZERO:deposit.getHeldAmount(),
                actor.equals(c.getLandlordId())&&c.getStatus()==ContractStatus.EXPIRED
                    && allBillsPaid(c) && (close==null || Set.of("WAITING_HANDOVER","PROPOSED","DISPUTED").contains(close.getStatus())),
                close==null?null:paymentView(close.getRefundPaymentId(),actor),close==null?null:paymentView(close.getBalancePaymentId(),actor),billingView(c,actor,close));
    }
    private com.hs.payment.dto.PaymentRequestResponse paymentView(String id,String actor) {
        return id==null?null:payments.toResponse(paymentRepository.findById(id).orElseThrow(this::notAllowed),actor);
    }
    private boolean paidBill(MonthlyInvoice b) {
        return b.getStatus()==MonthlyInvoiceStatus.PAID && (b.getTotalAmount()!=null && b.getTotalAmount().signum()==0
            || b.getPaymentRequestId()!=null && paymentRepository.findById(b.getPaymentRequestId()).filter(p->p.getStatus()==PaymentStatus.CONFIRMED).isPresent());
    }
    private boolean allBillsPaid(Contract c) {
        List<MonthlyInvoice> list=invoices.findByContractIdOrderByPeriodIndexDesc(c.getId());
        var regular=list.stream().filter(b->!b.isFinalCostSupplement()).toList();
        if(regular.size()!=months(c)) return false;
        for(int n=0;n<months(c);n++) { int index=n; if(regular.stream().noneMatch(b->b.getPeriodIndex()==index)) return false; }
        return list.stream().allMatch(b->paidBill(b) || b.getStatus()==MonthlyInvoiceStatus.ROLLED_OVER
                && b.getRolledToInvoiceId()!=null && list.stream().anyMatch(next->b.getRolledToInvoiceId().equals(next.getId()) && next.getPeriodIndex()>b.getPeriodIndex()));
    }
    private void requirePaidBills(Contract c) {
        if(paymentRepository.findAllByContractIdAndType(c.getId(),PaymentType.DEPOSIT_ADJUSTMENT).stream().anyMatch(p->p.getStatus()!=PaymentStatus.CONFIRMED))
            throw new AppException(ContractErrorCode.LIFECYCLE_NOT_ALLOWED,"Phải đối soát xong khoản chênh lệch cọc trước khi quyết toán/hoàn cọc.");
        if(!allBillsPaid(c)) throw new AppException(ContractErrorCode.LIFECYCLE_NOT_ALLOWED,"Phải thanh toán và chủ nhà xác nhận tất cả hóa đơn kỳ cuối/bổ sung trước khi quyết toán cọc.");
    }
    private MonthlyInvoice lastCostsBill(Contract c) {
        MonthlyInvoice original=invoices.findByContractIdAndPeriodIndex(c.getId(),months(c)-1).orElseThrow(this::notAllowed);
        return invoices.findByContractIdOrderByPeriodIndexDesc(c.getId()).stream()
            .filter(b->b.isFinalCostSupplement() && Objects.equals(original.getId(),b.getSourceInvoiceId()))
            .max(Comparator.comparingInt(MonthlyInvoice::getPeriodIndex)).orElse(original);
    }
    private boolean sameReading(BigDecimal a,BigDecimal b) { return a!=null && b!=null && a.compareTo(b)==0; }
    private ClosureBillingResponse billingView(Contract c,String actor,ContractClosure close) {
        var list=invoices.findByContractIdOrderByPeriodIndexDesc(c.getId());
        boolean canIssue=actor.equals(c.getLandlordId()) && c.getStatus()==ContractStatus.EXPIRED && close!=null && close.getAcceptedAt()==null
            && list.stream().anyMatch(b->!b.isFinalCostSupplement() && b.getPeriodIndex()==months(c)-1 && b.getStatus()!=MonthlyInvoiceStatus.DRAFT)
            && list.stream().noneMatch(b->b.isFinalCostSupplement() && !paidBill(b));
        return new ClosureBillingResponse(allBillsPaid(c),canIssue,list.stream().map(b->new ClosureBillingResponse.Bill(b.getId(),b.getPeriodIndex(),b.isFinalCostSupplement(),b.getStatus(),
            b.getPaymentRequestId()==null?null:paymentRepository.findById(b.getPaymentRequestId()).map(PaymentRequest::getStatus).orElse(null),b.getTotalAmount(),b.getElectricityEnd(),b.getWaterEnd(),billingPeriod(c,b))).toList());
    }
    private BillingPeriodView billingPeriod(Contract c, MonthlyInvoice bill) {
        try {
            List<InvoiceLine> lines = mapper.readValue(bill.getLineItemsSnapshot() == null ? "[]" : bill.getLineItemsSnapshot(), new TypeReference<>() {});
            return BillingPeriodView.from(bill, months(c), rental(c).getMoveInDate(),
                    lines.stream().anyMatch(line -> "RENT".equals(line.type())));
        } catch (java.io.IOException e) { throw invalid(); }
    }
    private boolean canRequest(Contract c) {
        return c.getStatus()==ContractStatus.ACTIVE && !time.now().isBefore(opens(c)) && time.now().isBefore(signBy(c))
                && (amendments == null || !amendments.hasPending(c))
                && (c.getTerminationProposedAt()==null || c.getTerminationCancelledAt()!=null || c.getTerminationDeclinedAt()!=null)
                && renewals.findByContractIdOrderByRequestedAtDesc(c.getId()).stream().noneMatch(r->end(c).equals(r.getStartsOn()));
    }
    @Transactional public void cancelPendingRenewalsForClosedParent(Contract c) { syncLocked(c); }
    private boolean terminationActive(Contract c) { return c.getTerminationProposedAt()!=null && c.getTerminationCancelledAt()==null && c.getTerminationDeclinedAt()==null && c.getTerminationCompletedAt()==null; }
    private LocalDate end(Contract c) { return ContractLeaseCalc.end(rental(c).getMoveInDate(), months(c)); }
    private Instant opens(Contract c) { return ContractLeaseCalc.renewalOpens(end(c)); }
    private Instant signBy(Contract c) { return ContractLeaseCalc.renewalSignBy(end(c)); }
    private int months(Contract c) { return ContractLeaseCalc.months(c, rental(c)); }
    private RentalRequest rental(Contract c) { return rentals.findById(c.getRentalRequestId()).orElseThrow(this::notAllowed); }
    private ContractRevision revision(Contract c) { return terms == null ? revisions.findById(c.getCurrentRevisionId()).orElseThrow(this::notAllowed) : terms.at(c,time.today()); }
    private DepositRecord deposit(Contract c) { return deposits.findByRentalRequestIdForUpdate(c.getRentalRequestId()).orElseThrow(this::notAllowed); }
    private Contract root(String id,String actor) {
        Contract c=contracts.findByIdForUpdate(id).orElseThrow(this::notAllowed);
        if(com.hs.contract.deposit.DepositAgreementService.isDeposit(c) || c.getParentContractId()!=null || (!actor.equals(c.getLandlordId())&&!actor.equals(c.getTenantId())))
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN); return c;
    }
    private void owner(Contract c,String actor) { if(!actor.equals(c.getLandlordId())) throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN); }
    private void tenant(Contract c,String actor) { if(!actor.equals(c.getTenantId())) throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN); }
    private BigDecimal rate(Contract c,String method) {
        try {
            String snapshot=revision(c).getChargesSnapshot();
            List<Map<String,Object>> rows=mapper.readValue(snapshot==null?"[]":snapshot,new TypeReference<>(){});
            return rows.stream().filter(r->method.equals(r.get("billingMethod"))&&!Boolean.TRUE.equals(r.get("includedInRent")))
                    .map(r->new BigDecimal(String.valueOf(r.get("unitAmount")))).findFirst().orElse(ZERO);
        } catch(Exception e) { throw invalid(); }
    }
    private BigDecimal adjustment(BigDecimal end,BigDecimal billedEnd,BigDecimal rate) {
        if(rate.signum()==0) return ZERO;
        if(end==null || billedEnd==null || end.compareTo(billedEnd)<0 || end.scale()>3 || end.compareTo(new BigDecimal("1000000000"))>0) throw invalid();
        return end.subtract(billedEnd).multiply(rate).setScale(0,java.math.RoundingMode.HALF_UP);
    }
    private List<ClosureDeduction> validateDeductions(Contract c,String actor,ClosurePayload p) {
        if(p.deductions()==null) return p.otherDeduction().signum()==0 ? List.of()
                : List.of(new ClosureDeduction(p.note().trim(),p.otherDeduction(),List.of()));
        if(p.deductions().size()>20) throw invalid();
        List<ClosureDeduction> result=new ArrayList<>(); Set<String> names=new HashSet<>();
        for(var row:p.deductions()) {
            if(row==null || row.description()==null || row.description().trim().length()<3 || row.description().length()>300
                    || !moneyValid(row.amount()) || row.amount().signum()<=0
                    || !names.add(row.description().trim().toLowerCase(Locale.ROOT))) throw invalid();
            result.add(new ClosureDeduction(row.description().trim(),row.amount(),validateEvidence(c,actor,row.evidenceIds())));
        }
        BigDecimal sum=result.stream().map(ClosureDeduction::amount).reduce(ZERO,BigDecimal::add);
        if(!moneyValid(sum) || sum.compareTo(p.otherDeduction())!=0) throw invalid();
        return result;
    }
    private List<String> validateEvidence(Contract c,String actor,List<String> ids) {
        if(ids==null) return List.of();
        if(ids.size()>5 || new HashSet<>(ids).size()!=ids.size()) throw invalid();
        for(String id:ids) {
            if(id==null || id.isBlank()) throw invalid();
            var file=storageObjects.findById(id).orElseThrow(this::invalid);
            if(!actor.equals(file.getOwnerId()) || !c.getId().equals(file.getReferenceId())
                    || !"CONTRACT_CLOSURE_EVIDENCE".equals(file.getReferenceType())
                    || file.getPurpose()!=StoragePurpose.GENERAL || file.getVisibility()!=StorageVisibility.PRIVATE
                    || file.getStatus()!=StorageStatus.READY || file.getSizeBytes()==null || file.getSizeBytes()<=0 || file.getSizeBytes()>15*1024*1024
                    || file.getContentType()==null || !Set.of("image/jpeg","image/png","image/webp","application/pdf").contains(file.getContentType())) throw invalid();
        }
        return List.copyOf(ids);
    }
    private List<ClosureDeduction> closureDeductions(ContractClosure close) {
        if(close.getDeductionsSnapshot()==null) return close.getOtherDeduction()==null || close.getOtherDeduction().signum()==0 ? List.of()
                : List.of(new ClosureDeduction(Objects.toString(close.getNote(),"Khoản khấu trừ từ đề xuất cũ"),close.getOtherDeduction(),List.of()));
        try { return mapper.readValue(close.getDeductionsSnapshot(),new TypeReference<>(){}); } catch(Exception e) { throw invalid(); }
    }
    private List<ClosureEvent> closureHistory(ContractClosure close) {
        if(close.getHistorySnapshot()==null) return new ArrayList<>();
        try { return new ArrayList<>(mapper.readValue(close.getHistorySnapshot(),new TypeReference<List<ClosureEvent>>(){})); } catch(Exception e) { throw invalid(); }
    }
    private void preserveLegacyProposal(ContractClosure close) {
        if(close.getHistorySnapshot()==null && close.getProposalVersion()>0 && close.getNote()!=null) {
            close.setHistorySnapshot(write(List.of(new ClosureEvent("LEGACY_PROPOSAL",close.getProposalVersion(),close.getProposedAt(),null,
                    close.getNote(),closureDeductions(close),close.getRefundAmount(),close.getBalanceAmount(),List.of()))));
        }
    }
    private void appendClosureEvent(ContractClosure close,String type,String actor,String note,List<String> evidence) {
        var history=closureHistory(close);
        history.add(new ClosureEvent(type,close.getProposalVersion(),time.now(),actor,note,closureDeductions(close),close.getRefundAmount(),close.getBalanceAmount(),evidence));
        close.setHistorySnapshot(write(history));
    }
    private void responseDeadline(ContractClosure close) {
        if(closureResponseHours<1 || closureResponseHours>720) throw invalid();
        close.setRespondBy(time.now().plus(Duration.ofHours(closureResponseHours))); close.setResponseOverdueAt(null);
    }
    private boolean moneyValid(BigDecimal value) { return value!=null && value.signum()>=0 && value.compareTo(new BigDecimal("1000000000"))<=0 && value.stripTrailingZeros().scale()<=2; }
    private Instant min(Instant a,Instant b) { return a.isBefore(b)?a:b; }
    private Map<String,Object> read(String json) {
        if(json==null || json.isBlank()) return new LinkedHashMap<>();
        try { return mapper.readValue(json,new TypeReference<>(){}); } catch(Exception e) { throw invalid(); }
    }
    private String write(Object value) { try { return mapper.writeValueAsString(value); } catch(Exception e) { throw invalid(); } }
    private AppException notAllowed() { return new AppException(ContractErrorCode.LIFECYCLE_NOT_ALLOWED); }
    private AppException invalid() { return new AppException(ContractErrorCode.LIFECYCLE_INVALID); }
}
