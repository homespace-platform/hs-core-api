package com.hs.contract.deposit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.payment.ReservationPolicy;
import com.hs.common.time.BillingTime;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.*;
import com.hs.contract.service.engine.*;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Separate deposit domain; reuses the existing versioned PDF/SmartCA envelope, never lease activation. */
@Service @RequiredArgsConstructor
public class DepositAgreementService {
    private final DepositAgreementRepository agreements;
    private final ContractRepository contracts;
    private final ContractRevisionRepository revisions;
    private final ContractTemplateVersionRepository templates;
    private final RentalRequestRepository rentals;
    private final com.hs.listing.repository.RentalOnboardingEventRepository events;
    private final DepositRecordRepository ledgers;
    private final PaymentRequestRepository payments;
    private final PaymentLineItemRepository lines;
    private final ContractDataBuilder builder;
    private final ContractFieldCatalog catalog;
    private final ObjectMapper mapper;
    private final BillingTime time;
    @Value("${rental.deposit-signing-hours:48}") private long signingHours=48;
    @Value("${homespace.payment.contract-due-hours:48}") private long leaseSigningHours=48;
    private static final TypeReference<Map<String,Object>> MAP=new TypeReference<>(){};
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter STAMP=DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(BillingTime.ZONE);
    public static boolean isDeposit(Contract c){return c!=null && DepositFieldCatalog.KIND.equals(c.getAgreementKind());}
    private AppException invalid(String text){return new AppException(ContractErrorCode.CONTRACT_SIGNING_NOT_ALLOWED,text);}

    @Transactional(readOnly=true)
    public RentalRequest participant(String rentalId,String actor) {
        var r=rentals.findById(rentalId).orElseThrow(()->invalid("Không tìm thấy yêu cầu thuê."));
        if(actor==null||!actor.equals(r.getOwnerId())&&!actor.equals(r.getRenterId()))throw invalid("Không có quyền đọc hồ sơ cọc.");
        return r;
    }
    @Transactional(readOnly=true)
    public Optional<Contract> find(String rentalId) {
        return agreements.findByRentalRequestId(rentalId).flatMap(d->contracts.findById(d.getSigningContractId()));
    }
    /** Zero-deposit exemption; no synthetic receipt or deposit signing document. */
    @Transactional(readOnly=true)
    public boolean signedOrExempt(RentalRequest r) {
        if(r.getDepositAmount()!=null && r.getDepositAmount().signum()==0)return true;
        return find(r.getId()).filter(c->c.getStatus()==ContractStatus.ACTIVE && c.getSignedAt()!=null).isPresent();
    }

    @Transactional
    public Contract createAutomatically(String rentalId) {
        var r=rentals.findByIdForUpdate(rentalId).orElseThrow();
        var existing=find(rentalId); if(existing.isPresent())return existing.get();
        if(r.getStatus()!=RentalRequestStatus.ACCEPTED || r.getCancellationRequestedAt()!=null
            || r.getListing()==null || r.getListing().getStatus()!=ListingStatus.RESERVED
            || r.getDepositAmount()==null || r.getDepositAmount().signum()<=0)return null;
        var ledger=ledgers.findByRentalRequestId(rentalId).orElse(null);
        if(ledger==null || ledger.getStatus()!=DepositStatus.HELD || ledger.getHeldAmount()==null || ledger.getHeldAmount().signum()<=0)return null;
        var payment=payments.findById(ledger.getInitialPaymentRequestId()).orElseThrow();
        if(payment.getStatus()!=PaymentStatus.CONFIRMED)return null;
        DepositPaymentAllocation allocation;
        try { allocation=DepositPaymentAllocation.verify(r,payment,ledger,lines.findByPaymentRequestIdOrderBySortOrderAsc(payment.getId())); }
        catch(IllegalStateException inconsistent) { throw invalid(inconsistent.getMessage()); }
        var published=templates.findPublishedDepositVersions(r.getListing().getCategory());
        if(published.size()!=1)throw invalid("Cần admin phát hành đúng một mẫu hợp đồng cọc cho loại nhà này. Không chuyển tiền thêm.");
        var template=published.getFirst();
        String number="DC-"+time.today().format(DateTimeFormatter.BASIC_ISO_DATE)+"-"+UUID.randomUUID().toString().substring(0,8).toUpperCase();
        if(r.getListingSnapshot()==null || r.getListingSnapshot().isBlank())throw invalid("Thiếu bản chụp tài sản tại thời điểm chấp nhận yêu cầu thuê; không dùng bài đăng hiện tại để thay lịch sử.");
        var snapshots=builder.build(r,r.getListing(),payment);
        var frozen=build(r,payment,allocation,snapshots,number);
        var missing=DepositDataModel.missing(frozen,new DepositFieldCatalog(catalog),r.getListing().getCategory());
        if(!missing.isEmpty())throw invalid("Thiếu dữ liệu hợp đồng cọc: "+String.join(", ",missing)+". Bổ sung hồ sơ trước khi kết xuất.");
        for(String group:List.of("landlord","tenant")) {
            var party=(Map<?,?>)frozen.get(group);
            if(Set.of("Chủ nhà (Bên A)","Người thuê (Bên B)").contains(Objects.toString(party.get("fullName"),"")))throw invalid("Thiếu họ tên thật của bên ký.");
        }
        if(r.getListing().getCategory()!=ListingCategory.HOUSE && "Toàn bộ căn".equals(snapshots.getProperty().get("unitNumber")))
            throw invalid("Thiếu số căn hộ/phòng thực tế.");
        var envelope=contracts.save(Contract.builder().contractNumber(number).rentalRequestId(rentalId).listingId(r.getListing().getId())
            .landlordId(r.getOwnerId()).tenantId(r.getRenterId()).agreementKind(DepositFieldCatalog.KIND)
            .templateId(template.getTemplate().getId()).templateVersionId(template.getId()).status(ContractStatus.DRAFT)
            .rentalPaymentId(payment.getId()).paidAt(payment.getConfirmedAt()).paymentStatus(ContractPaymentStatus.PAID).build());
        String json=json(frozen);
        var revision=revisions.save(ContractRevision.builder().contract(envelope).revisionNumber(1).schemaVersion(3).templateVersionId(template.getId())
            .landlordSnapshot(json(snapshots.getLandlord())).tenantSnapshot(json(snapshots.getTenant())).propertySnapshot(json(snapshots.getProperty()))
            .leaseSnapshot(json(snapshots.getLease())).financialSnapshot(json(snapshots.getFinancial()))
            .initialPaymentSnapshot(json).chargesSnapshot("[]").equipmentSnapshot("[]").amenitiesSnapshot("[]").initialMetersSnapshot("{}").policiesSnapshot("{}")
            .revisionNote("D2: bản chụp cọc bất biến; ký riêng, không kích hoạt thuê.").build());
        envelope.setCurrentRevisionId(revision.getId());contracts.save(envelope);
        var d=new DepositAgreement();d.setRentalRequestId(rentalId);d.setSourcePaymentId(payment.getId());d.setSigningContractId(envelope.getId());
        d.setSnapshotJson(json);d.setSnapshotHash(ReservationPolicy.hash(json));d.setCapturedAt(time.now());agreements.save(d);
        audit(rentalId,"SYSTEM","DEPOSIT_AGREEMENT_CREATED","Đã lập hồ sơ cọc "+number+" từ khoản đã xác nhận; chưa phải hợp đồng thuê có hiệu lực.");
        return envelope;
    }
    private Map<String,Object> build(RentalRequest r,PaymentRequest p,DepositPaymentAllocation a,ContractDataBuilder.ContractSnapshots s,String number) {
        if(r.getReservationPolicyAcceptedAt()==null || r.getAcceptedAt()==null)throw invalid("Thiếu xác nhận điều kiện giữ chỗ.");
        String terms=r.getReservationPolicyText();
        // Only the exact legacy V1 text is reconstructed. Never apply the newer policy retroactively.
        if((terms==null||terms.isBlank()) && "RESERVATION_DEFAULT_V1".equals(r.getReservationPolicyVersion()))terms=ReservationPolicy.V1_TEXT;
        if(terms==null||terms.isBlank())throw invalid("Thiếu toàn văn điều kiện giữ chỗ đã chấp thuận.");
        if(r.getReservationPolicyHash()!=null && !r.getReservationPolicyHash().equals(ReservationPolicy.hash(terms)))throw invalid("Điều kiện giữ chỗ không khớp hash.");
        var model=new LinkedHashMap<String,Object>();
        model.put("landlord",s.getLandlord());model.put("tenant",s.getTenant());model.put("property",s.getProperty());model.put("lease",s.getLease());
        var rent=new LinkedHashMap<String,Object>();rent.put("amountNumber",s.getFinancial().get("amountNumber"));rent.put("amountWords",s.getFinancial().get("amountWords"));model.put("rent",rent);
        model.put("deposit",Map.of("amountNumber",ContractRenderService.formatVND(a.deposit()),"amountWords",VietnameseCurrencyTextConverter.toWords(a.deposit())));
        String next=p.getType()==PaymentType.RESERVATION_DEPOSIT
            ?"Đã xác nhận cọc giữ chỗ. Tiền thuê tháng đầu chưa nằm trong khoản chuyển này; mở vào ngày bắt đầu thuê "+r.getMoveInDate().format(DATE)+". Xem hạn thực tế trên yêu cầu thanh toán được phát hành. Hai bên ký đủ hợp đồng cọc trước khi ký hợp đồng thuê."
            :"Đã xác nhận khoản gộp tiền thuê tháng đầu và cọc; không chuyển lại hai khoản này. Hai bên ký đủ hợp đồng cọc rồi tiếp tục ký hợp đồng thuê.";
        model.put("depositAgreement",Map.of("number",number,"issuedAtText",STAMP.format(time.now()),"reservationTerms",terms,"nextStepsText",next));
        model.put("rentalRequest",Map.of("reference",r.getId(),"acceptedAtText",STAMP.format(r.getAcceptedAt()),"policyVersion",r.getReservationPolicyVersion(),"policyAcceptedAtText",STAMP.format(r.getReservationPolicyAcceptedAt())));
        model.put("depositPayment",Map.of("typeText",p.getType()==PaymentType.RESERVATION_DEPOSIT?"Cọc giữ chỗ":"Khoản ban đầu gồm tiền thuê và cọc",
            "totalAmountNumber",ContractRenderService.formatVND(p.getTotalAmount()),"rentComponentAmountNumber",ContractRenderService.formatVND(a.rent()),
            "confirmedAmountNumber",ContractRenderService.formatVND(a.deposit()),"confirmedAmountWords",VietnameseCurrencyTextConverter.toWords(a.deposit()),
            "payerReportedAt",STAMP.format(p.getPayerReportedAt()),"payeeConfirmedAt",STAMP.format(p.getPayeeConfirmedAt()),"confirmedAt",STAMP.format(p.getConfirmedAt()),"transferReference",Objects.toString(p.getTransferReference(),"")));
        var optional=new ArrayList<Map<String,String>>();
        for(var party:Map.of("landlord",s.getLandlord(),"tenant",s.getTenant()).entrySet()) {
            String side=party.getKey().equals("landlord")?"Bên A":"Bên B";
            row(optional,side+" - CCCD",party.getValue().get("idNumber"));row(optional,side+" - Email",party.getValue().get("email"));
            row(optional,side+" - Địa chỉ liên hệ theo hồ sơ",party.getValue().get("permanentAddress"));
        }
        row(optional,"Tầng",s.getProperty().get("floor"));row(optional,"Phạm vi thuê",s.getProperty().get("rentalScope"));
        row(optional,"Mã ngân hàng do bên giao dịch cung cấp",p.getBankTransactionReference());model.put("depositOptionalRows",optional);
        return model;
    }
    private void row(List<Map<String,String>> rows,String label,Object value){if(value!=null&&!value.toString().isBlank())rows.add(Map.of("label",label,"value",value.toString()));}
    private String json(Object o){try{return mapper.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException("Không lưu được snapshot cọc",e);}}
    @Transactional(readOnly=true)
    public Map<String,Object> dataModel(Contract c) {
        var d=agreements.findBySigningContractId(c.getId()).orElseThrow(()->invalid("Thiếu snapshot cọc."));
        if(!d.getSnapshotHash().equals(ReservationPolicy.hash(d.getSnapshotJson())))throw invalid("Snapshot cọc không khớp hash.");
        try{return DepositDataModel.render(mapper.readValue(d.getSnapshotJson(),MAP));}catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    @Transactional
    public void validateSigning(Contract c) {
        var r=rentals.findByIdForUpdate(c.getRentalRequestId()).orElseThrow();
        if(r.getStatus()!=RentalRequestStatus.ACCEPTED || r.getCancellationRequestedAt()!=null || r.getListing().getStatus()!=ListingStatus.RESERVED)throw invalid("Hồ sơ không còn cho phép ký cọc.");
        var d=agreements.findBySigningContractId(c.getId()).orElseThrow();
        var p=payments.findById(d.getSourcePaymentId()).orElseThrow();
        var ledger=ledgers.findByRentalRequestId(r.getId()).orElseThrow();
        if(p.getStatus()!=PaymentStatus.CONFIRMED || ledger.getStatus()!=DepositStatus.HELD)throw invalid("Khoản cọc cần đối soát trước khi ký.");
        if(c.getStatus()==ContractStatus.TENANT_SIGNATURE_PENDING && c.getTenantSigningDueAt()!=null&&!time.now().isBefore(c.getTenantSigningDueAt()))
            throw invalid("Đã hết hạn ký cọc; cần chủ nhà mở lại để ký, không tự xử lý mất cọc.");
        dataModel(c);
    }
    public void landlordSigned(Contract c){
        c.setTenantSigningDueAt(time.now().plus(Duration.ofHours(signingHours)));
        audit(c.getRentalRequestId(),c.getLandlordId(),"DEPOSIT_LANDLORD_SIGNED","Đã xác minh chữ ký chủ nhà trên hợp đồng cọc; mở cửa sổ khách ký.");
    }
    /** Runs inside the verified-signature transaction; never marks the listing rented. */
    public void bothSigned(Contract c) {
        audit(c.getRentalRequestId(),c.getTenantId(),"DEPOSIT_BOTH_SIGNED","Đã xác minh đủ hai chữ ký trên hợp đồng cọc; chưa kích hoạt thuê.");
        var rent=payments.findByRentalRequestIdAndType(c.getRentalRequestId(),PaymentType.INITIAL).orElse(null);
        if(rent!=null && rent.getStatus()==PaymentStatus.CONFIRMED) {
            rent.setContractDueAt(time.now().plus(Duration.ofHours(leaseSigningHours)));payments.save(rent);
        }
    }
    @Transactional public void reopen(String rentalId,String actor) {
        var r=participant(rentalId,actor);if(!actor.equals(r.getOwnerId()))throw invalid("Chỉ chủ nhà được mở lại hạn ký cọc.");
        r=rentals.findByIdForUpdate(rentalId).orElseThrow();
        if(r.getStatus()!=RentalRequestStatus.ACCEPTED || r.getCancellationRequestedAt()!=null || r.getListing()==null || r.getListing().getStatus()!=ListingStatus.RESERVED)
            throw invalid("Hồ sơ không còn cho phép mở lại hạn ký cọc.");
        var c=find(rentalId).orElseThrow();c=contracts.findByIdForUpdate(c.getId()).orElseThrow();
        if(c.getStatus()!=ContractStatus.TENANT_SIGNATURE_PENDING)throw invalid("Không có bước ký cọc cần mở lại.");
        c.setTenantSigningDueAt(time.now().plus(Duration.ofHours(signingHours)));contracts.save(c);
        audit(rentalId,actor,"DEPOSIT_SIGNING_REOPENED","Chủ nhà mở lại hạn khách ký hợp đồng cọc; không thay đổi xác nhận tiền.");
    }
    private void audit(String rentalId,String actor,String action,String note) {
        events.save(RentalOnboardingEvent.builder().rentalRequestId(rentalId).actorId(actor).action(action).note(note).occurredAt(time.now()).build());
    }
}
