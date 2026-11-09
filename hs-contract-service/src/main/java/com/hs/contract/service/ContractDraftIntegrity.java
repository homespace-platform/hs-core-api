package com.hs.contract.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.dto.request.UpdateContractRevisionRequest;
import com.hs.contract.model.Contract;
import com.hs.contract.model.ContractRevision;
import com.hs.contract.model.constant.SignatureRequestStatus;
import com.hs.contract.repository.ContractRevisionRepository;
import com.hs.contract.repository.SignatureRequestRepository;
import com.hs.contract.service.engine.ContractDataBuilder;
import com.hs.contract.service.engine.ContractRenderService;
import com.hs.contract.service.engine.VietnameseCurrencyTextConverter;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.model.constant.PaymentType;
import com.hs.payment.repository.PaymentRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/** Root lease drafts only. Linked agreements and deposit documents have their own immutable terms. */
@Component
@RequiredArgsConstructor
public class ContractDraftIntegrity {
    private final ObjectMapper mapper;
    private final RentalRequestRepository rentals;
    private final ContractRevisionRepository revisions;
    private final SignatureRequestRepository signatures;
    private final PaymentRequestRepository payments;
    private final ContractDataBuilder builder;
    private static final TypeReference<Map<String,Object>> MAP = new TypeReference<>() {};
    private static final TypeReference<List<Map<String,Object>>> ROWS = new TypeReference<>() {};
    private static final Set<String> FEE_KEYS = Set.of("latePaymentFeeMode", "latePaymentFeeAmount",
            "latePaymentFeeGraceDays", "latePaymentFeeCap", "billingRules", "paymentDueDay",
            "overdueLandlordTerminationAfterFiveDays", "overdueUnilateralTermination24h");
    private static final Set<String> NUMBERS = Set.of("durationMonths", "occupantCount", "motorbikeCount", "carCount",
            "amountValue", "amountNumber", "depositAmountValue", "depositAmountNumber", "paymentDueOffsetDays",
            "maxOccupants", "maxVehicles", "maxMotorbikeCount", "maxCarCount", "areaM2", "quantity", "index",
            "unitAmount", "estimatedMonthlyAmount", "latePaymentFeeAmount", "latePaymentFeeGraceDays", "latePaymentFeeCap");

    public static boolean applies(Contract c) {
        return c.getParentContractId() == null && !"DEPOSIT".equals(c.getAgreementKind());
    }

    /** Called under the same rental/contract row locks as signing. Never changes receipts or inventory. */
    public UpdateContractRevisionRequest prepareUpdate(Contract c, ContractRevision current, UpdateContractRevisionRequest input) {
        if (input == null) throw invalid("Thiếu nội dung cập nhật bản nháp.");
        if (input.getBaseRevisionId() != null && !Objects.equals(input.getBaseRevisionId(), current.getId()))
            throw new AppException(ContractErrorCode.CONTRACT_DRAFT_STALE);
        assertNoSignature(c);
        var expected = canonical(c);
        restoreListingSource(expected, current);
        restoreDraftContent(expected, current);
        // Full-form echoes remain supported, but only the explicit draft fields below can change.
        protectedMap("landlord", input.getLandlord(), expected.getLandlord());
        var contact = input.getLandlordContact();
        if (contact != null) {
            if (contact.phone() != null) ContractDraftContent.applyParty(expected.getLandlord(), Map.of("phone", contact.phone()));
            if (contact.email() != null) ContractDraftContent.applyParty(expected.getLandlord(), Map.of("email", contact.email()));
        }
        protectedMap("tenant", input.getTenant(), expected.getTenant());
        protectedMap("property", input.getProperty(), expected.getProperty());
        protectedMap("lease", input.getLease(), expected.getLease());
        var financial = input.getFinancial() == null ? null : new LinkedHashMap<>(input.getFinancial());
        if (financial != null) {
            // Existing unsigned drafts may still echo the old calendar-based billing description.
            for (String key : List.of("paymentDueDay", "paymentDueOffsetDays")) {
                if (financial.containsKey(key) && same(financial.get(key), map(current.getFinancialSnapshot()).get(key), key))
                    financial.put(key, expected.getFinancial().get(key));
            }
        }
        protectedMap("financial", financial, expected.getFinancial());
        protectedRows("charges", input.getCharges(), expected.getCharges());
        protectedRows("equipments", input.getEquipments(), expected.getEquipments());
        protectedRows("amenities", input.getAmenities(), expected.getAmenities());
        var content = input.getDraftContent();
        if (content != null) {
            if (content.property() != null || content.equipments() != null || content.amenities() != null)
                throw readonly("bài đăng");
            ContractDraftContent.applyParty(expected.getLandlord(), content.landlord());
            var tenant = content.tenant() == null ? null : new LinkedHashMap<>(content.tenant());
            if (tenant != null) {
                for (String key : ContractDraftContent.RESIDENT_FIELDS) if (tenant.containsKey(key))
                    expected.getTenant().put(key, AmendmentTermsBuilder.count(tenant.remove(key), "occupantCount".equals(key) ? 1 : 0, 100));
            }
            ContractDraftContent.applyParty(expected.getTenant(), tenant);
        }
        if (input.getInitialPayment() != null
                && !same(input.getInitialPayment(), map(current.getInitialPaymentSnapshot()), "initialPayment"))
            protectedMap("initialPayment", input.getInitialPayment(), expected.getInitialPayment());

        var policies = map(current.getPoliciesSnapshot());
        if (input.getPolicies() != null) {
            var protectedPolicies = new LinkedHashMap<>(input.getPolicies());
            FEE_KEYS.forEach(protectedPolicies::remove);
            protectedMap("policies", protectedPolicies, expected.getPolicies());
            policies.putAll(input.getPolicies());
        }
        // Preserve editable fee terms; restore every other policy from the captured agreement.
        var savedPolicies = new LinkedHashMap<>(expected.getPolicies());
        FEE_KEYS.forEach(k -> { if (policies.containsKey(k)) savedPolicies.put(k, policies.get(k)); });
        expected.setPolicies(savedPolicies);
        var originalMeters = map(current.getListingSourceVersion() == null ? first(c).getInitialMetersSnapshot() : current.getInitialMetersSnapshot());
        normalizeNonMeterWater(originalMeters, expected.getCharges(), expected.getTenant());
        var meters = new LinkedHashMap<>(originalMeters);
        var currentMeters = map(current.getInitialMetersSnapshot());
        for (String key : List.of("electricityInitial", "waterInitial"))
            if (currentMeters.containsKey(key)) meters.put(key, currentMeters.get(key));
        normalizeNonMeterWater(meters, expected.getCharges(), expected.getTenant());
        if (input.getMeters() != null) {
            for (var e : input.getMeters().entrySet()) {
                if (!Set.of("electricityInitial", "waterInitial").contains(e.getKey()))
                    protectedMap("meters", Collections.singletonMap(e.getKey(), e.getValue()), originalMeters);
                meters.put(e.getKey(), e.getValue());
            }
        }
        expected.setMeters(meters);
        validateMeters(meters, originalMeters);
        if (input.getAdditionalTerms() != null) {
            if (input.getSpecialTerms() != null) throw invalid("Chỉ gửi phần thỏa thuận bổ sung cần sửa.");
            var terms = splitTerms(current.getSpecialTerms(), map(current.getPoliciesSnapshot()));
            if (terms.additional() == null) throw invalid("Bản nháp cũ chưa tách điều khoản hệ thống khỏi nội dung bổ sung.");
            String additional = input.getAdditionalTerms().replace(ContractDataBuilder.DEFAULT_OVERDUE_TERMS, "").trim();
            expected.setSpecialTerms(java.util.stream.Stream.of(terms.system(), additional)
                    .filter(part -> !part.isBlank()).collect(java.util.stream.Collectors.joining("\n\n")));
        } else {
            expected.setSpecialTerms(input.getSpecialTerms() == null ? current.getSpecialTerms() : input.getSpecialTerms().trim());
        }
        expected.setRevisionNote(input.getRevisionNote());
        bounded(expected.getSpecialTerms(), 20000, "Thỏa thuận riêng");
        bounded(expected.getRevisionNote(), 500, "Ghi chú phiên bản");
        return expected;
    }

    public record DraftTerms(String system, String additional) {}

    public static DraftTerms splitTerms(String text, Map<String, Object> policies) {
        String raw = Objects.toString(text, "").trim();
        String system = ContractDataBuilder.DEFAULT_OVERDUE_TERMS;
        if (raw.contains(system)) return new DraftTerms(system, raw.replace(system, "").trim());
        // Legacy policy text cannot be safely split when its exact wording differs.
        if (policies != null && (Boolean.TRUE.equals(policies.get("overdueUnilateralTermination24h"))
                || Boolean.TRUE.equals(policies.get("overdueLandlordTerminationAfterFiveDays"))))
            return new DraftTerms(raw, null);
        return new DraftTerms("", raw);
    }

    /** Recheck even for revisions created by older clients, before rendering or either signature. */
    public void validateForDocument(Contract c) {
        if (!applies(c)) return;
        validateForDocument(c, revisions.findById(c.getCurrentRevisionId())
                .filter(r -> r.getContract() != null && Objects.equals(r.getContract().getId(), c.getId()))
                .orElseThrow(() -> invalid("Không tìm thấy phiên bản hiện tại của hợp đồng.")));
    }

    public void validateForDocument(Contract c, ContractRevision current) {
        if (!applies(c)) return;
        if (current == null || current.getContract() == null || !Objects.equals(current.getContract().getId(), c.getId()))
            throw invalid("Phiên bản không thuộc hợp đồng cần xử lý.");
        var expected = canonical(c);
        restoreListingSource(expected, current);
        restoreDraftContent(expected, current);
        protectedMapExact("landlord", map(current.getLandlordSnapshot()), expected.getLandlord());
        protectedMapExact("tenant", map(current.getTenantSnapshot()), expected.getTenant());
        protectedMapExact("property", map(current.getPropertySnapshot()), expected.getProperty());
        protectedMapExact("lease", map(current.getLeaseSnapshot()), expected.getLease());
        protectedMapExact("financial", map(current.getFinancialSnapshot()), expected.getFinancial());
        protectedRows("charges", rows(current.getChargesSnapshot()), expected.getCharges());
        protectedRows("equipments", rows(current.getEquipmentSnapshot()), expected.getEquipments());
        protectedRows("amenities", rows(current.getAmenitiesSnapshot()), expected.getAmenities());
        protectedMapExact("initialPayment", map(current.getInitialPaymentSnapshot()), expected.getInitialPayment());
        var policy = map(current.getPoliciesSnapshot());
        FEE_KEYS.forEach(policy::remove);
        var frozen = new LinkedHashMap<>(expected.getPolicies());
        FEE_KEYS.forEach(frozen::remove);
        protectedMapExact("policies", policy, frozen);
        var baseline = first(c);
        var meters = map(current.getInitialMetersSnapshot());
        var fixed = new LinkedHashMap<>(meters);
        fixed.remove("electricityInitial"); fixed.remove("waterInitial");
        var original = map(current.getListingSourceVersion() == null ? baseline.getInitialMetersSnapshot() : current.getInitialMetersSnapshot());
        original.remove("electricityInitial"); original.remove("waterInitial");
        protectedMapExact("meters", fixed, original);
        var originalMeterValues=map(current.getListingSourceVersion() == null ? baseline.getInitialMetersSnapshot() : current.getInitialMetersSnapshot());
        normalizeNonMeterWater(originalMeterValues, expected.getCharges(), expected.getTenant());
        validateMeters(meters, originalMeterValues);
        LatePaymentPolicy.from(map(current.getPoliciesSnapshot()));
        bounded(current.getSpecialTerms(), 20000, "Thỏa thuận riêng");
    }

    private void restoreListingSource(UpdateContractRevisionRequest expected, ContractRevision current) {
        if (current.getListingSourceVersion() == null) return;
        expected.setProperty(map(current.getPropertySnapshot()));
        expected.setEquipments(rows(current.getEquipmentSnapshot()));
        expected.setAmenities(rows(current.getAmenitiesSnapshot()));
        expected.setCharges(rows(current.getChargesSnapshot()));
        expected.setPolicies(map(current.getPoliciesSnapshot()));
    }

    private void normalizeNonMeterWater(Map<String,Object> meters,List<Map<String,Object>> charges,Map<String,Object> tenant) {
        if (Objects.toString(meters.get("waterInitial"),"").startsWith("Không áp dụng")
                && charges.stream().anyMatch(r->"WATER".equals(r.get("chargeType")) && "PER_PERSON_MONTH".equals(r.get("billingMethod"))))
            meters.put("waterInitial","Không áp dụng - nước tính theo "+tenant.get("occupantCount")+" người");
    }

    private void restoreDraftContent(UpdateContractRevisionRequest expected, ContractRevision current) {
        var equipments = rows(current.getEquipmentSnapshot());
        var amenities = rows(current.getAmenitiesSnapshot());
        ContractDraftContent.apply(expected, new UpdateContractRevisionRequest.DraftContent(
                draftFields(map(current.getLandlordSnapshot()), expected.getLandlord(), ContractDraftContent.PARTY_FIELDS),
                draftFields(map(current.getTenantSnapshot()), expected.getTenant(), ContractDraftContent.PARTY_FIELDS),
                draftFields(map(current.getPropertySnapshot()), expected.getProperty(), ContractDraftContent.PROPERTY_FIELDS),
                same(equipments, expected.getEquipments(), "equipments") ? null : equipments,
                same(amenities, expected.getAmenities(), "amenities") ? null : amenities));
    }

    private Map<String, Object> draftFields(Map<String, Object> current, Map<String, Object> baseline, Set<String> keys) {
        var changed = new LinkedHashMap<String, Object>();
        for (String key : keys)
            if (current.containsKey(key) && !same(current.get(key), baseline.get(key), key)) changed.put(key, current.get(key));
        return changed;
    }

    private UpdateContractRevisionRequest canonical(Contract c) {
        var r = rentals.findById(c.getRentalRequestId()).orElseThrow(() -> invalid("Không tìm thấy hồ sơ thuê nguồn."));
        if (r.getListing() == null || !Objects.equals(c.getListingId(), r.getListing().getId())
                || !Objects.equals(c.getLandlordId(), r.getOwnerId()) || !Objects.equals(c.getTenantId(), r.getRenterId()))
            throw invalid("Chủ thể hoặc tài sản không khớp hồ sơ thuê.");
        if (r.getMoveInDate() == null || r.getLeaseMonths() == null || r.getLeaseMonths() <= 0)
            throw invalid("Hồ sơ thuê thiếu ngày bắt đầu hoặc thời hạn hợp lệ.");
        var first = first(c);
        var result = UpdateContractRevisionRequest.builder()
                .landlord(map(first.getLandlordSnapshot())).tenant(map(first.getTenantSnapshot()))
                .property(map(first.getPropertySnapshot())).lease(map(first.getLeaseSnapshot()))
                .financial(map(first.getFinancialSnapshot())).charges(rows(first.getChargesSnapshot()))
                .equipments(rows(first.getEquipmentSnapshot())).amenities(rows(first.getAmenitiesSnapshot()))
                .policies(map(first.getPoliciesSnapshot())).initialPayment(map(first.getInitialPaymentSnapshot())).build();
        result.getTenant().put("occupantCount", r.getOccupantCount() == null || r.getOccupantCount() <= 0 ? 1 : r.getOccupantCount());
        result.getTenant().put("motorbikeCount", r.getMotorbikeCount() == null ? 0 : r.getMotorbikeCount());
        result.getTenant().put("carCount", r.getCarCount() == null ? 0 : r.getCarCount());
        var originalTenant = map(first.getTenantSnapshot());
        if (ContractDraftContent.RESIDENT_FIELDS.stream().anyMatch(k -> !same(result.getTenant().get(k), originalTenant.get(k), k)))
            result.setCharges(new AmendmentTermsBuilder(mapper).draftCharges(result.getCharges(), result.getTenant(), result.getProperty()));
        result.setLease(ContractDataBuilder.leaseTerms(r));
        BigDecimal rent = r.getEffectiveMonthlyRent() != null ? r.getEffectiveMonthlyRent() : r.getMonthlyRentPrice();
        BigDecimal deposit = r.getDepositAmount();
        if (rent == null || rent.signum() <= 0 || deposit == null || deposit.signum() < 0)
            throw invalid("Hồ sơ thuê thiếu tiền thuê hoặc tiền cọc hợp lệ.");
        result.getFinancial().put("amountValue", rent.toPlainString());
        result.getFinancial().put("amountNumber", ContractRenderService.formatVND(rent) + "/tháng");
        result.getFinancial().put("amountWords", VietnameseCurrencyTextConverter.toWords(rent));
        result.getFinancial().put("depositAmountValue", deposit.toPlainString());
        result.getFinancial().put("depositAmountNumber", ContractRenderService.formatVND(deposit));
        result.getFinancial().put("depositAmountWords", VietnameseCurrencyTextConverter.toWords(deposit));
        result.getFinancial().put("paymentDueDay", MonthlyBillingSchedule.paymentDueDescription());
        result.getFinancial().put("paymentDueOffsetDays", MonthlyBillingSchedule.PAYMENT_WINDOW_DAYS);
        var payment = payments.findByRentalRequestIdAndType(r.getId(), PaymentType.INITIAL).orElse(null);
        if (payment != null) result.setInitialPayment(builder.onboardingPayment(r, payment, result.getFinancial(), result.getCharges()));
        return result;
    }

    private ContractRevision first(Contract c) {
        return revisions.findByContractIdAndRevisionNumber(c.getId(), 1)
                .filter(r -> r.getContract() != null && Objects.equals(r.getContract().getId(), c.getId()))
                .orElseThrow(() -> invalid("Thiếu bản chụp khởi tạo để đối chiếu hợp đồng. Cần kiểm tra hồ sơ nguồn trước khi ký."));
    }

    private void assertNoSignature(Contract c) {
        if (c.getSignedAt() != null || c.getLandlordConfirmedAt() != null)
            throw new AppException(ContractErrorCode.SIGNATURE_NOT_ALLOWED, "Văn bản đã được ký/xác nhận; không thể sửa bản nháp này.");
        boolean pending = signatures.findByContractIdOrderByCreatedAtDesc(c.getId()).stream().anyMatch(r ->
                r.getStatus() == null || !r.getStatus().isTerminal() || r.getStatus() == SignatureRequestStatus.SIGNED
                || "PROVIDER_STATE_UNKNOWN".equals(r.getFailureCode()) || "EMBEDDING_STUCK".equals(r.getFailureCode())
                || r.getProviderSignatureValue() != null && !r.getProviderSignatureValue().isBlank());
        if (pending) throw new AppException(ContractErrorCode.SIGNATURE_NOT_ALLOWED,
                "Yêu cầu ký đang xử lý hoặc cần đối soát. Không thể sửa bản nháp trước khi xác định kết quả ký.");
    }

    private void validateMeters(Map<String,Object> meters, Map<String,Object> original) {
        for (String key : List.of("electricityInitial", "waterInitial")) {
            Object value = meters.get(key);
            if (value == null || value.toString().isBlank()) continue; // Completeness decides whether required.
            if (same(value, original.get(key), key) && !value.toString().matches(".*[0-9].*")) continue;
            if (same(value, original.get(key), key) && "waterInitial".equals(key)
                    && value.toString().startsWith("Không áp dụng")) continue;
            try {
                BigDecimal n = new BigDecimal(value.toString().trim());
                if (n.signum() < 0 || n.compareTo(new BigDecimal("1000000000000")) > 0 || n.stripTrailingZeros().scale() > 3)
                    throw new IllegalArgumentException();
            } catch (RuntimeException ex) { throw invalid("Chỉ số " + key + " phải là số không âm, tối đa 3 chữ số thập phân."); }
        }
    }

    private void protectedMap(String path, Map<String,Object> requested, Map<String,Object> expected) {
        if (requested == null) return;
        for (var e : requested.entrySet())
            if (!expected.containsKey(e.getKey()) || !same(e.getValue(), expected.get(e.getKey()), e.getKey()))
                throw readonly(path + "." + e.getKey());
    }
    private void protectedMapExact(String path, Map<String,Object> actual, Map<String,Object> expected) {
        if (!actual.keySet().equals(expected.keySet())) throw readonly(path);
        protectedMap(path, actual, expected);
    }
    private void protectedRows(String path, List<Map<String,Object>> actual, List<Map<String,Object>> expected) {
        if (actual != null && !same(actual, expected, path)) throw readonly(path);
    }
    private boolean same(Object a, Object b, String key) {
        // Optional numeric source fields may be empty/null; unchanged source data is still an exact echo.
        if (Objects.equals(a,b)) return true;
        if (a == null || b == null) return a == b;
        if (a instanceof Map<?,?> x && b instanceof Map<?,?> y)
            return x.keySet().equals(y.keySet()) && x.entrySet().stream().allMatch(e -> same(e.getValue(), y.get(e.getKey()), String.valueOf(e.getKey())));
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!same(x.get(i), y.get(i), key)) return false;
            return true;
        }
        if (a instanceof Map || b instanceof Map || a instanceof List || b instanceof List) return false;
        if (a instanceof Boolean || b instanceof Boolean) return a.equals(b);
        if (NUMBERS.contains(key)) {
            try { return decimal(a, key).compareTo(decimal(b, key)) == 0; }
            catch (RuntimeException ex) { return false; }
        }
        return a.toString().trim().equals(b.toString().trim()); // Identity/account numbers remain strings.
    }
    private BigDecimal decimal(Object v, String key) {
        String text = v.toString().trim();
        if (key.endsWith("Number")) text = text.replaceFirst("(?i)\\s*(?:VNĐ|VND|đ)?(?:/tháng)?$", "").trim();
        if (text.matches("[0-9]{1,3}(?:\\.[0-9]{3})+")) text = text.replace(".", "");
        return new BigDecimal(text);
    }
    private Map<String,Object> map(String json) {
        try { var result = json == null || json.isBlank() ? null : mapper.readValue(json, MAP); return result == null ? new LinkedHashMap<>() : result; }
        catch (Exception ex) { throw invalid("Không đọc được bản chụp hợp đồng nguồn."); }
    }
    private List<Map<String,Object>> rows(String json) {
        try { var result = json == null || json.isBlank() ? null : mapper.readValue(json, ROWS); return result == null ? List.of() : result; }
        catch (Exception ex) { throw invalid("Không đọc được bảng điều khoản nguồn."); }
    }
    private void bounded(String value, int max, String label) {
        if (value != null && value.length() > max) throw invalid(label + " vượt quá " + max + " ký tự.");
    }
    private AppException readonly(String path) {
        return new AppException(ContractErrorCode.CONTRACT_DRAFT_READ_ONLY,
                "Trường " + path + " phải khớp hồ sơ thuê đã chấp thuận. Hãy lưu lại bản nháp từ hồ sơ nguồn; thay đổi ngày/tiền/người ở/xe phải qua luồng thỏa thuận tương ứng.");
    }
    private AppException invalid(String message) { return new AppException(ContractErrorCode.CONTRACT_DRAFT_INVALID, message); }
}
