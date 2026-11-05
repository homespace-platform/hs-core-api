package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.dto.amendment.AmendmentComparison;
import com.hs.contract.model.Contract;
import com.hs.contract.model.ContractRevision;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.MonthlyInvoiceRepository;
import com.hs.contract.service.engine.ContractDataBuilder;
import com.hs.contract.service.engine.ContractRenderService;
import com.hs.listing.model.Listing;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.listing.service.ParkingReservationService;
import com.hs.listing.service.RentalCostCalculator;
import com.hs.listing.dto.snapshot.ListingSnapshotDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ContractListingSync {
    private final ListingRepository listings;
    private final RentalRequestRepository rentals;
    private final ContractDataBuilder data;
    private final AmendmentTermsBuilder terms;
    private final RentalCostCalculator cost;
    private final ParkingReservationService parking;
    private final MonthlyInvoiceRepository invoices;
    private final ObjectMapper mapper;
    private final BillingTime time;
    @PersistenceContext private EntityManager entityManager;

    private static final Set<String> OPERATIONAL_FEATURES = Set.of("Đồng hồ điện", "Đồng hồ nước", "Chính sách gửi xe");
    private static final Set<String> AREA_FEATURES = Set.of("Diện tích sử dụng", "Diện tích phòng");
    private static final Set<String> LISTING_POLICIES = Set.of("listingPolicies", "noticeDaysBeforeMoveOut",
            "noticeDaysBeforeTermination", "depositRefundDays", "petPolicy", "petsAllowed", "smokingPolicy",
            "smokingAllowed", "subleasePolicy", "sublettingAllowed", "overnightGuestPolicy", "propertyInspectionNotice");

    public record Preview(String sourceVersion, String baseRevisionId, Map<String, Object> terms,
                          Integer proposedMonths, String leaseMessage, List<AmendmentComparison> differences,
                          List<AmendmentComparison> financialDifferences) {}

    public Preview preview(Contract contract, ContractRevision base, String expectedVersion) {
        boolean draft = contract.getStatus() == ContractStatus.DRAFT;
        Listing listing = listings.findByIdAndActiveTrue(contract.getListingId()).orElseThrow(() -> invalid("Không tìm thấy bài đăng gốc."));
        if (draft && listing.getStatus() != ListingStatus.RESERVED
                || !draft && (contract.getStatus() != ContractStatus.ACTIVE || listing.getStatus() != ListingStatus.RENTED))
            throw invalid("Bài đăng hoặc hợp đồng không còn ở trạng thái cho phép đồng bộ.");
        if (listing.getApprovedEditId() == null) throw invalid("Bài đăng chưa có bản sửa được quản trị viên duyệt.");
        if (expectedVersion != null) {
            if (!expectedVersion.equals(listing.getApprovedEditId())) throw new AppException(ContractErrorCode.CONTRACT_DRAFT_STALE);
            // Check the same approved listing at commit without reversing the rental/signing lock order.
            entityManager.lock(listing, LockModeType.OPTIMISTIC);
        }
        RentalRequest rental = rentals.findById(contract.getRentalRequestId()).orElseThrow(() -> invalid("Không tìm thấy hồ sơ thuê."));
        if (!Objects.equals(listing.getOwnerId(), contract.getLandlordId())
                || rental.getListing() == null || !listing.getId().equals(rental.getListing().getId()))
            throw invalid("Bài đăng không khớp tài sản trên hợp đồng.");
        if (draft && (rental.getStatus() != RentalRequestStatus.ACCEPTED
                || rental.getCancellationRequestedAt() != null || rental.getResolutionWorkflowId() != null))
            throw invalid("Hồ sơ giữ chỗ đang thay đổi hoặc chờ xử lý.");

        ListingSnapshotDto approved = approvedSnapshot(listing);
        var before = terms.snapshot(base);
        var originalProperty = terms.object(before.get("property"));
        var originalTenant = terms.object(before.get("tenant"));
        int occupants = number(originalTenant.get("occupantCount"), 1);
        int bikes = number(originalTenant.get("motorbikeCount"), 0);
        int cars = number(originalTenant.get("carCount"), 0);
        // Quote current signed occupancy, including effective appendices, without changing the accepted request.
        var quote = RentalRequest.builder().occupantCount(occupants).motorbikeCount(bikes).carCount(cars).build();
        var source = data.listingTerms(approved, quote);
        var property = new LinkedHashMap<>(source.property());
        for (String field : List.of("propertyType", "listingCode"))
            if (!Objects.equals(originalProperty.get(field), property.get(field)))
                throw invalid("Loại tài sản hoặc mã tin không khớp hồ sơ hợp đồng.");
        if (occupants > number(property.get("maxOccupants"), Integer.MAX_VALUE)
                || bikes + cars > number(property.get("maxVehicles"), Integer.MAX_VALUE)
                || bikes > number(property.get("maxMotorbikeCount"), Integer.MAX_VALUE)
                || cars > number(property.get("maxCarCount"), Integer.MAX_VALUE))
            throw invalid("Giới hạn mới thấp hơn số người hoặc xe đã đăng ký trong hợp đồng.");

        var oldCharges = terms.rows(base.getChargesSnapshot());
        var financial = terms.object(before.get("financial"));
        var money = new ArrayList<AmendmentComparison>();
        var quotedListing = Listing.builder().category(approved.getCategory()).priceAmount(approved.getPriceAmount())
                .priceUnit(com.hs.listing.model.constant.PriceUnit.valueOf(approved.getPriceUnit()))
                .depositType(com.hs.listing.model.constant.DepositType.valueOf(approved.getDepositType()))
                .depositAmount(approved.getDepositAmount()).depositMonths(approved.getDepositMonths()).build();
        BigDecimal rent = cost.calculateEffectiveMonthlyRent(quotedListing, occupants);
        BigDecimal deposit = cost.calculateDeposit(quotedListing, rent, rental.getDepositAmount());
        compareMoney(money, "amountValue", "Tiền thuê mỗi tháng", financial.get("amountValue"), rent);
        compareMoney(money, "depositAmountValue", "Tiền đặt cọc", financial.get("depositAmountValue"), deposit);
        terms.compareCharges(money, oldCharges, source.charges());
        if (source.policies().get("paymentCycle") != null)
            add(money, "financial.paymentCycle", "Chu kỳ thanh toán", financial.get("paymentCycle"), source.policies().get("paymentCycle"));

        var protectedFeatures = new HashSet<>(OPERATIONAL_FEATURES);
        if (oldCharges.stream().anyMatch(row -> "PER_M2_MONTH".equals(row.get("billingMethod")))) {
            for (String key : List.of("areaM2", "areaText")) {
                if (!Objects.equals(property.get(key), originalProperty.get(key)) && "areaText".equals(key))
                    add(money, "property.areaText", "Diện tích dùng để tính phí", originalProperty.get(key), property.get(key));
                if (originalProperty.containsKey(key)) property.put(key, originalProperty.get(key));
                else property.remove(key);
            }
            protectedFeatures.addAll(AREA_FEATURES);
        }
        preserveFeatures(originalProperty, property, protectedFeatures);
        for (String key : List.of("electricMeterType", "waterMeterType"))
            add(money, "meters." + key, "Cách tính theo công tơ", terms.object(before.get("meters")).get(key), source.meters().get(key));

        var policies = mergeListingPolicies(terms.object(before.get("policies")), Map.of("policies", source.policies()));
        var updated = new LinkedHashMap<String, Object>();
        updated.put("property", property);
        updated.put("equipments", source.equipments());
        updated.put("amenities", source.amenities());
        updated.put("policies", policies);
        // Association metadata is kept outside the legal Listing snapshot, which intentionally excludes branch IDs.
        updated.put("listingBranchId", listing.getBranchId());
        var changes = new ArrayList<AmendmentComparison>();
        for (var key : List.of("fullAddress", "unitNumber", "areaText", "floor", "rentalScope", "maxOccupants", "maxVehicles", "features"))
            add(changes, "property." + key, switch (key) {
                case "fullAddress" -> "Địa chỉ tài sản"; case "unitNumber" -> "Mã/tên phòng hoặc căn"; case "areaText" -> "Diện tích"; case "floor" -> "Tầng";
                case "rentalScope" -> "Phạm vi cho thuê"; case "maxOccupants" -> "Số người tối đa";
                case "maxVehicles" -> "Số xe tối đa"; default -> "Đặc điểm tài sản";
            }, originalProperty.get(key), property.get(key));
        if (!Objects.equals(contract.getBranchId(), listing.getBranchId()))
            changes.add(new AmendmentComparison("listingBranchId", "Liên kết chi nhánh và chỗ xe", "Theo hồ sơ hợp đồng hiện tại", "Theo bài đăng đã duyệt; kiểm tra lại sức chứa khi áp dụng"));
        add(changes, "equipments", "Nội thất bàn giao", before.get("equipments"), updated.get("equipments"));
        add(changes, "amenities", "Tiện ích", before.get("amenities"), updated.get("amenities"));
        add(changes, "policies", "Chính sách bài đăng", before.get("policies"), updated.get("policies"));

        int minimum = number(approved.getMinimumLeaseMonths(), 0);
        int selected = number(rental.getLeaseMonths(), 0);
        int previousMinimum = base.getListingTermMinimum() != null
                ? base.getListingTermMinimum() : acceptedMinimum(rental);
        Integer proposed = null;
        String leaseMessage = null;
        if (draft && selected > 0 && minimum > selected) {
            leaseMessage = "Mức tối thiểu mới cao hơn thời hạn đã chốt. Cần xử lý bằng thỏa thuận riêng.";
        } else if (draft && minimum > 0 && minimum < selected && selected == previousMinimum) {
            if (contract.getEffectiveLeaseMonths() != null || contract.getAgreedEarlyBillingMonths() != null
                    || rental.getMoveInDate() == null || !rental.getMoveInDate().plusMonths(minimum).isAfter(time.today())
                    || !invoices.findByContractIdOrderByPeriodIndexDesc(contract.getId()).isEmpty())
                throw invalid("Thời hạn mới đã qua hoặc hợp đồng đã có lịch hóa đơn.");
            proposed = minimum;
            var end = rental.getMoveInDate().plusMonths(minimum);
            updated.put("lease", ContractDataBuilder.leaseTerms(rental.getMoveInDate(), minimum));
            changes.add(new AmendmentComparison("lease.durationMonths", "Thời hạn thuê",
                    selected + " tháng", minimum + " tháng · ngày thuê cuối " + end.minusDays(1)));
        }
        return new Preview(listing.getApprovedEditId(), base.getId(), updated, proposed, leaseMessage, changes, money);
    }

    public void applyDraftTerm(Contract contract, Preview preview) {
        var rental = rentals.findById(contract.getRentalRequestId()).orElseThrow(() -> invalid("Không tìm thấy hồ sơ thuê."));
        if (preview.proposedMonths() != null) {
            var start = rental.getMoveInDate();
            parking.shortenHeldLease(rental.getId(), start.plusMonths(rental.getLeaseMonths()), start.plusMonths(preview.proposedMonths()));
            rental.setLeaseMonths(preview.proposedMonths());
            if (rental.getEstimatedMonthlyTotal() != null && rental.getDepositAmount() != null)
                rental.setEstimatedLeaseTotal(rental.getEstimatedMonthlyTotal().multiply(BigDecimal.valueOf(preview.proposedMonths()))
                        .add(rental.getDepositAmount()));
            rentals.save(rental);
        }
        if (preview.terms().containsKey("listingBranchId") && !Objects.equals(contract.getBranchId(), preview.terms().get("listingBranchId"))) {
            if (!invoices.findByContractIdOrderByPeriodIndexDesc(contract.getId()).isEmpty()) throw invalid("Hợp đồng đã có hóa đơn; không chuyển pool chỗ xe trên bản nháp.");
            parking.replaceDraftHold(rental, Objects.requireNonNullElse(rental.getMotorbikeCount(),0), Objects.requireNonNullElse(rental.getCarCount(),0));
            contract.setBranchId((String)preview.terms().get("listingBranchId"));
        }
    }

    private ListingSnapshotDto approvedSnapshot(Listing listing) {
        try {
            var snapshot = mapper.readValue(listing.getApprovedContractSnapshot(), ListingSnapshotDto.class);
            if (snapshot == null || !listing.getId().equals(snapshot.getListingId()) || snapshot.getCategory() != listing.getCategory()
                    || snapshot.getPriceUnit() == null || snapshot.getDepositType() == null)
                throw invalid("Bản bài đăng đã duyệt không khớp tài sản hiện tại.");
            com.hs.listing.model.constant.PriceUnit.valueOf(snapshot.getPriceUnit());
            com.hs.listing.model.constant.DepositType.valueOf(snapshot.getDepositType());
            return snapshot;
        } catch (AppException e) { throw e; }
        catch (Exception e) { throw invalid("Thiếu bản chụp nội dung đã duyệt. Hãy gửi lại bản sửa cho admin duyệt."); }
    }

    public Map<String, Object> mergeListingPolicies(Map<String, Object> current, Map<String, Object> source) {
        var merged = new LinkedHashMap<>(current);
        var policies = terms.object(source.get("policies"));
        var keys = new HashSet<>(LISTING_POLICIES);
        for (var policy : List.of(current, policies))
            for (var row : FreeFormAmendmentTerms.rows(policy.get("listingPolicies"))) {
                Object code = row.get("code");
                if (code instanceof String name && !name.isBlank()) keys.add(name);
            }
        for (String key : keys) {
            if (policies.containsKey(key)) merged.put(key, policies.get(key));
            else merged.remove(key);
        }
        return merged;
    }

    private int acceptedMinimum(RentalRequest rental) {
        if (rental.getListingSnapshot() == null) return 0;
        try { return mapper.readTree(rental.getListingSnapshot()).path("minimumLeaseMonths").asInt(0); }
        catch (Exception e) { throw invalid("Không đọc được thời hạn tối thiểu tại thời điểm giữ chỗ."); }
    }

    private void preserveFeatures(Map<String, Object> before, Map<String, Object> after, Set<String> keys) {
        if (!(after.get("features") instanceof List<?>)) return;
        var rows = new ArrayList<Map<String, Object>>();
        var old = new LinkedHashMap<Object, Map<String, Object>>();
        for (var row : FreeFormAmendmentTerms.rows(before.get("features")))
            if (keys.contains(row.get("featureName"))) old.put(row.get("featureName"), row);
        for (var row : FreeFormAmendmentTerms.rows(after.get("features"))) {
            Object name = row.get("featureName");
            if (!keys.contains(name)) rows.add(row);
            else if (old.containsKey(name)) rows.add(old.remove(name));
        }
        rows.addAll(old.values());
        after.put("features", rows);
    }

    private void compareMoney(List<AmendmentComparison> changes, String key, String label, Object before, BigDecimal after) {
        try {
            var value = new BigDecimal(Objects.toString(before, ""));
            if (value.compareTo(after) != 0) add(changes, key, label, ContractRenderService.formatVND(value), ContractRenderService.formatVND(after));
        } catch (NumberFormatException e) {
            add(changes, key, label, before, ContractRenderService.formatVND(after));
        }
    }

    private void add(List<AmendmentComparison> changes, String key, String label, Object before, Object after) {
        if (!Objects.equals(before, after))
            changes.add(new AmendmentComparison(key, label, display(before), display(after)));
    }

    private String display(Object value) {
        if (value instanceof List<?> rows) return String.join("\n", rows.stream().map(this::display).toList());
        if (value instanceof Map<?, ?> row) {
            if (row.containsKey("featureName")) return row.get("featureName") + " · " + row.get("featureValue");
            if (row.containsKey("name")) return Objects.toString(row.get("name"), "")
                    + (row.containsKey("quantity") ? " × " + row.get("quantity") + " · " + row.get("condition") : "");
            if (row.containsKey("label")) return row.get("label") + " · " + row.get("displayValue");
            if (row.containsKey("listingPolicies")) return display(row.get("listingPolicies"));
        }
        return Objects.toString(value, "—");
    }

    private int number(Object value, int fallback) {
        try { return new BigDecimal(Objects.toString(value, "")).intValueExact(); }
        catch (NumberFormatException | ArithmeticException e) { return fallback; }
    }

    private AppException invalid(String message) { return new AppException(ContractErrorCode.LIFECYCLE_INVALID, message); }
}
