package com.hs.contract.service;

import com.hs.common.advice.entity.AppException;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.dto.request.UpdateContractRevisionRequest;

import java.math.BigDecimal;
import java.util.*;

import static com.hs.user.validation.UserValidationPatterns.VIETNAMESE_PHONE;

/** Editable document descriptions. IDs, allocations and previously collected money stay canonical. */
final class ContractDraftContent {
    private ContractDraftContent() {}

    static final Set<String> PARTY_FIELDS = Set.of("fullName", "idNumber", "permanentAddress", "phone", "email",
            "bankName", "bankAccountNumber", "bankAccountHolder");
    static final Set<String> RESIDENT_FIELDS = Set.of("occupantCount", "motorbikeCount", "carCount");
    static final Set<String> PROPERTY_FIELDS = Set.of("fullAddress", "areaText", "rentalScope", "features");
    private static final Set<String> AREA_FEATURES = Set.of("Diện tích sử dụng", "Diện tích phòng");
    private static final Set<String> DERIVED_FEATURES = Set.of("Mã / Số phòng", "Tầng số", "Số người tối đa",
            "Số người ở tối đa", "Số xe tối đa", "Số phương tiện tối đa", "Đồng hồ điện", "Đồng hồ nước",
            "Phạm vi cho thuê", "Chính sách gửi xe", "Khoảng tầng cho thuê");

    static void apply(UpdateContractRevisionRequest expected, UpdateContractRevisionRequest.DraftContent input) {
        if (input == null) return;
        applyParty(expected.getLandlord(), input.landlord());
        applyParty(expected.getTenant(), input.tenant());
        applyProperty(expected, input.property());
        if (input.equipments() != null) expected.setEquipments(assets(input.equipments(), true));
        if (input.amenities() != null) expected.setAmenities(assets(input.amenities(), false));
    }

    static void applyParty(Map<String, Object> target, Map<String, Object> fields) {
        if (fields == null) return;
        requireKeys(fields, PARTY_FIELDS, "Thông tin các bên");
        for (var entry : fields.entrySet()) {
            String key = entry.getKey();
            String value = text(entry.getValue(), !"email".equals(key), 1000, "Thông tin các bên");
            if ("phone".equals(key) && !value.matches(VIETNAMESE_PHONE))
                throw invalid("Nhập số điện thoại di động Việt Nam hợp lệ.");
            if ("email".equals(key) && !value.isEmpty()
                    && (value.length() > 100 || !value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")))
                throw invalid("Nhập địa chỉ email hợp lệ (tối đa 100 ký tự).");
            if ("idNumber".equals(key) && !value.matches("(?:[0-9]{9}|[0-9]{12})"))
                throw invalid("Số định danh phải gồm 9 hoặc 12 chữ số.");
            if ("bankAccountNumber".equals(key) && !value.matches("[0-9]{1,34}"))
                throw invalid("Số tài khoản phải gồm 1–34 chữ số; giữ nguyên các số 0 đầu.");
            target.put(key, value);
        }
    }

    private static void applyProperty(UpdateContractRevisionRequest expected, Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) return;
        requireKeys(fields, PROPERTY_FIELDS, "Thông tin bất động sản");
        var property = expected.getProperty();
        if (fields.containsKey("features")) {
            var original = FreeFormAmendmentTerms.rows(property.get("features"));
            var proposed = FreeFormAmendmentTerms.rows(fields.get("features"));
            if (original.size() != proposed.size()) throw invalid("Giữ nguyên danh sách đặc điểm tài sản.");
            for (int i = 0; i < original.size(); i++) {
                var before = original.get(i);
                var after = proposed.get(i);
                if (before.equals(after)) continue;
                requireKeys(after, Set.of("featureName", "featureValue"), "Đặc điểm tài sản");
                String name = Objects.toString(before.get("featureName"), "");
                if (!name.equals(after.get("featureName"))) throw invalid("Giữ nguyên tên và thứ tự đặc điểm tài sản.");
                if (DERIVED_FEATURES.contains(name) || AREA_FEATURES.contains(name)) {
                    // These rows mirror the main section or a frozen allocation. They are rebuilt below.
                    if ((AREA_FEATURES.contains(name) && fields.containsKey("areaText"))
                            || ("Phạm vi cho thuê".equals(name) && fields.containsKey("rentalScope"))) continue;
                    throw invalid("Giá trị " + name + " lấy từ thông tin tài sản đã xác nhận.");
                }
                String value = text(after.get("featureValue"), true, 1000, name);
                FreeFormAmendmentTerms.validateFeatureNumber(name, value);
                before.put("featureValue", value);
            }
            property.put("features", original);
        }
        if (fields.containsKey("fullAddress"))
            property.put("fullAddress", text(fields.get("fullAddress"), true, 1000, "Địa chỉ tài sản"));
        if (fields.containsKey("rentalScope")) {
            String scope = text(fields.get("rentalScope"), true, 1000, "Phạm vi cho thuê");
            property.put("rentalScope", scope);
            mirror(property, Set.of("Phạm vi cho thuê"), scope);
        }
        if (fields.containsKey("areaText")) {
            if (expected.getCharges().stream().anyMatch(row -> "PER_M2_MONTH".equals(row.get("billingMethod"))))
                throw invalid("Diện tích đang dùng để tính phí theo m².");
            String value = text(fields.get("areaText"), true, 100, "Diện tích sử dụng");
            try {
                var area = new BigDecimal(value.replaceFirst("(?i)\\s*(m²|m2|mét vuông)\\s*$", "").replace(',', '.').trim()).stripTrailingZeros();
                if (area.signum() <= 0 || area.scale() > 3 || area.compareTo(BigDecimal.valueOf(1_000_000_000)) > 0)
                    throw new NumberFormatException();
                property.put("areaM2", area.toPlainString());
                property.put("areaText", area.toPlainString() + " mét vuông");
                mirror(property, AREA_FEATURES, property.get("areaText"));
            } catch (NumberFormatException e) {
                throw invalid("Diện tích phải là số dương, tối đa 3 chữ số thập phân.");
            }
        }
    }

    private static void mirror(Map<String, Object> property, Set<String> names, Object value) {
        if (!property.containsKey("features")) return;
        var rows = FreeFormAmendmentTerms.rows(property.get("features"));
        rows.stream().filter(row -> names.contains(row.get("featureName"))).forEach(row -> row.put("featureValue", value));
        property.put("features", rows);
    }

    private static List<Map<String, Object>> assets(List<Map<String, Object>> rows, boolean equipment) {
        if (rows.size() > 50) throw invalid("Tối đa 50 dòng trong mỗi bảng.");
        var result = new ArrayList<Map<String, Object>>();
        var names = new HashSet<String>();
        var allowed = equipment ? Set.of("index", "name", "quantity", "condition", "status", "note")
                : Set.of("index", "code", "name", "sourceType");
        for (var row : rows) {
            requireKeys(row, allowed, "Tiện ích và nội thất");
            String name = text(row.get("name"), true, 150, "Tên tiện ích hoặc nội thất");
            if (!names.add(name.toLowerCase(Locale.ROOT))) throw invalid("Tên bị trùng: " + name);
            var item = new LinkedHashMap<String, Object>();
            item.put("index", result.size() + 1);
            item.put("name", name);
            if (equipment) {
                item.put("quantity", AmendmentTermsBuilder.count(row.get("quantity"), 1, 1000));
                String condition = text(row.get("condition"), true, 1000, "Tình trạng nội thất");
                item.put("condition", condition);
                item.put("status", condition);
                item.put("note", text(row.getOrDefault("note", ""), false, 1000, "Ghi chú nội thất"));
            } else {
                item.put("code", text(row.getOrDefault("code", "CUSTOM"), true, 100, "Mã tiện ích"));
                item.put("sourceType", text(row.getOrDefault("sourceType", "CUSTOM"), true, 100, "Nguồn tiện ích"));
            }
            result.add(item);
        }
        return result;
    }

    private static void requireKeys(Map<String, Object> fields, Set<String> allowed, String label) {
        if (fields == null || !allowed.containsAll(fields.keySet()))
            throw new AppException(ContractErrorCode.CONTRACT_DRAFT_READ_ONLY, label + " có trường được bảo vệ.");
    }

    private static String text(Object input, boolean required, int max, String label) {
        if (!(input instanceof String value) || value.length() > max || required && value.isBlank())
            throw invalid(label + " thiếu hoặc không hợp lệ.");
        return value.trim();
    }

    private static AppException invalid(String message) {
        return new AppException(ContractErrorCode.CONTRACT_DRAFT_INVALID, message);
    }
}
