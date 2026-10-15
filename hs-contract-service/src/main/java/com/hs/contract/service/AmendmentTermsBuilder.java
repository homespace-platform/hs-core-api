package com.hs.contract.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.dto.amendment.AmendmentComparison;
import com.hs.contract.model.ContractRevision;
import com.hs.contract.service.engine.*;
import com.hs.listing.model.constant.ChargeMethodRules;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.math.*;
import java.util.*;

/** Server-owned calculations and a strict allowlist protect signed identity/payment history. */
@Component @RequiredArgsConstructor
public class AmendmentTermsBuilder {
    private final ObjectMapper mapper;
    private static final Set<String> KEYS = Set.of("rentAmount", "occupantCount", "motorbikeCount", "carCount",
            "charges", "equipments", "amenities", "policies", "additionalTerms", "applyImmediately");
    private static final Set<String> POLICY_KEYS = Set.of("subleasePolicy", "petPolicy", "smokingPolicy",
            "overnightGuestPolicy", "propertyInspectionNotice", "sublettingAllowed", "petsAllowed", "smokingAllowed", "listingPolicies");
    public record Result(Map<String, Object> terms, List<AmendmentComparison> comparison) {}

    public Map<String, Object> snapshot(ContractRevision r) {
        Map<String,Object> t = new LinkedHashMap<>();
        t.put("financial", map(r.getFinancialSnapshot())); t.put("tenant", map(r.getTenantSnapshot()));
        t.put("property", map(r.getPropertySnapshot())); t.put("charges", rows(r.getChargesSnapshot()));
        t.put("equipments", rows(r.getEquipmentSnapshot())); t.put("amenities", rows(r.getAmenitiesSnapshot()));
        String rawTerms=Objects.toString(r.getSpecialTerms(), "");
        String systemTerms=rawTerms.contains(ContractDataBuilder.DEFAULT_OVERDUE_TERMS)?ContractDataBuilder.DEFAULT_OVERDUE_TERMS:"";
        t.put("policies", map(r.getPoliciesSnapshot())); t.put("specialTerms",systemTerms.isEmpty()?rawTerms.trim():rawTerms.replace(systemTerms,"").trim());
        t.put("systemTerms",systemTerms);
        t.put("landlord", map(r.getLandlordSnapshot())); t.put("lease", map(r.getLeaseSnapshot()));
        t.put("meters", map(r.getInitialMetersSnapshot())); t.put("initialPayment", map(r.getInitialPaymentSnapshot()));
        t.put("billingRules",MonthlyBillingSchedule.rules());
        return t;
    }

    public String documentSpecialTerms(Map<String,Object> terms) {
        return java.util.stream.Stream.of(Objects.toString(terms.get("systemTerms"),""),Objects.toString(terms.get("specialTerms"),""))
                .filter(s->!s.isBlank()).collect(java.util.stream.Collectors.joining("\n"));
    }

    public Result build(ContractRevision base, Map<String,Object> changes) {
        return build(base,changes,false);
    }
    Result build(ContractRevision base, Map<String,Object> changes, boolean allowUnchanged) {
        if (changes != null && Boolean.TRUE.equals(changes.get("freeForm"))) return FreeFormAmendmentTerms.build(this,base,changes,allowUnchanged);
        if (changes == null || changes.isEmpty() || !KEYS.containsAll(changes.keySet())) throw invalid("Nội dung thay đổi không được hỗ trợ.");
        if (changes.containsKey("applyImmediately") && !(changes.get("applyImmediately") instanceof Boolean)) throw invalid("Cách chọn thời điểm áp dụng không hợp lệ.");
        if (Boolean.TRUE.equals(changes.get("applyImmediately")) && changes.keySet().stream().anyMatch(Set.of("rentAmount", "charges", "occupantCount", "motorbikeCount", "carCount")::contains))
            throw invalid("Thay đổi giá, phí hoặc số người/xe phải áp dụng từ đầu một kỳ tương lai.");
        Map<String,Object> before = snapshot(base), after = snapshot(base);
        List<AmendmentComparison> diff = new ArrayList<>();
        Map<String,Object> financial = object(after.get("financial")), tenant = object(after.get("tenant"));
        after.put("financial", financial); after.put("tenant", tenant);
        if (changes.containsKey("rentAmount")) {
            BigDecimal amount = money(changes.get("rentAmount"), false);
            add(diff, "rentAmount", "Giá thuê / tháng", financial.get("amountValue"), amount);
            financial.put("amountValue", amount.toPlainString());
            financial.put("amountNumber", ContractRenderService.formatVND(amount) + " VNĐ/tháng");
            financial.put("amountWords", VietnameseCurrencyTextConverter.toWords(amount));
        }
        for (String key : List.of("occupantCount", "motorbikeCount", "carCount")) if (changes.containsKey(key)) {
            int value = count(changes.get(key), "occupantCount".equals(key) ? 1 : 0, 100);
            add(diff, key, switch(key) { case "occupantCount" -> "Số người cư trú"; case "motorbikeCount" -> "Xe máy đăng ký"; default -> "Ô tô đăng ký"; }, tenant.get(key), value);
            tenant.put(key, value);
        }
        Map<String,Object> property = object(after.get("property"));
        validateLimit(property.get("maxOccupants"),tenant.get("occupantCount"),"Số người vượt giới hạn hợp đồng.");
        // Vehicle admission for amendments is checked against the shared branch pool,
        // never the per-Listing limits copied into the original snapshot.
        if (changes.containsKey("charges") || changes.containsKey("occupantCount") || changes.containsKey("motorbikeCount") || changes.containsKey("carCount")) {
            List<Map<String,Object>> original = list(before.get("charges"));
            List<Map<String,Object>> requested = changes.containsKey("charges") ? list(changes.get("charges")) : original;
            List<Map<String,Object>> normalized = charges(requested, original, tenant, property,
                    changes.containsKey("occupantCount"), changes.containsKey("motorbikeCount") || changes.containsKey("carCount"));
            compareCharges(diff, original, normalized);
            after.put("charges", normalized);
        }
        for (String key : List.of("equipments", "amenities")) if (changes.containsKey(key)) {
            List<Map<String,Object>> values = list(changes.get(key));
            if (values.size() > 50) throw invalid("Tối đa 50 dòng mỗi nhóm.");
            Set<String> names = new HashSet<>();
            int index = 1;
            for (Map<String,Object> row : values) {
                text(row.get("name"), 2, 150);
                if (!names.add(String.valueOf(row.get("name")).trim().toLowerCase(Locale.ROOT))) throw invalid("Tên bị trùng.");
                Set<String> allowed = key.equals("equipments") ? Set.of("index", "name", "quantity", "condition", "status", "note")
                        : Set.of("index", "code", "name", "sourceType");
                if (!allowed.containsAll(row.keySet())) throw invalid("Trường bảng không được hỗ trợ.");
                row.put("index",index++);
                if (key.equals("equipments")) { count(row.get("quantity"), 1, 1000); text(row.get("condition"), 1, 500); row.put("status",row.get("condition")); }
                for (Object v : row.values()) if (v != null && v.toString().length() > 1000) throw invalid("Nội dung quá dài.");
            }
            add(diff, key, key.equals("equipments") ? "Tài sản và bàn giao bổ sung" : "Tiện ích và quyền sử dụng", describe(list(before.get(key)), key), describe(values, key));
            after.put(key, values);
        }
        if (changes.containsKey("policies")) {
            Map<String,Object> requested = object(changes.get("policies")), policy = object(after.get("policies"));
            after.put("policies", policy);
            if (!POLICY_KEYS.containsAll(requested.keySet())) throw invalid("Chưa hỗ trợ đổi chính sách thanh toán, phạt hoặc tiền cọc.");
            if (policy.get("listingPolicies") instanceof List<?> source && !source.isEmpty()) {
                if (!requested.keySet().equals(Set.of("listingPolicies"))) throw invalid("Chính sách lấy từ bài đăng phải chỉnh tại từng trường chính sách.");
                var oldRows = list(policy.get("listingPolicies"));
                var proposed = list(requested.get("listingPolicies"));
                if (oldRows.size() != proposed.size()) throw invalid("Không được thêm hoặc xóa danh mục chính sách đã chốt.");
                var seen = new HashSet<String>();
                var canonical = new ArrayList<Map<String,Object>>();
                for (var row : proposed) {
                    String code = Objects.toString(row.get("code"),"");
                    if (!seen.add(code)) throw invalid("Chính sách bị trùng.");
                    var old = oldRows.stream().filter(r -> code.equals(r.get("code"))).findFirst().orElseThrow(() -> invalid("Chính sách không thuộc hợp đồng."));
                    for (var entry : row.entrySet()) if (!Set.of("value","note","displayValue").contains(entry.getKey()) && !Objects.equals(old.get(entry.getKey()),entry.getValue())) throw invalid("Không được đổi cấu trúc chính sách đã chốt.");
                    String note = row.get("note") == null ? "" : text(row.get("note"),0,1000);
                    String value = com.hs.listing.service.ListingPolicyService.validateValue(Objects.toString(old.get("valueType"),""),Objects.toString(row.get("value"),""),
                            old.get("minimumValue") == null ? null : count(old.get("minimumValue"),0,10000),old.get("maximumValue") == null ? null : count(old.get("maximumValue"),0,10000));
                    if (Set.of("depositRefundDays","noticeDaysBeforeTermination").contains(code) && (!Objects.equals(value,old.get("value")) || !Objects.equals(note,Objects.toString(old.get("note"),"")))) throw invalid("Phụ lục này không đổi thời hạn hoàn cọc hoặc báo trước.");
                    var normalized = new LinkedHashMap<>(old); normalized.put("value",value); normalized.put("note",note);
                    normalized.put("displayValue",com.hs.listing.service.ListingPolicyService.display(Objects.toString(old.get("valueType"),""),value,Objects.toString(old.get("unitLabel"),""),note));
                    add(diff,"policies." + code,Objects.toString(old.get("label"),code),old.get("displayValue"),normalized.get("displayValue")); canonical.add(normalized);
                }
                ContractListingPolicies.apply(policy,canonical);
            } else {
            for (var item : requested.entrySet()) {
                if (item.getKey().equals("listingPolicies")) throw invalid("Hợp đồng cũ chưa có danh mục chính sách cấu trúc.");
                if (item.getKey().endsWith("Allowed")) { if (!(item.getValue() instanceof Boolean)) throw invalid("Giá trị lựa chọn không hợp lệ."); }
                else text(item.getValue(), 1, 1000);
                add(diff, "policies." + item.getKey(), policyLabel(item.getKey()), policy.get(item.getKey()), item.getValue());
                policy.put(item.getKey(), item.getValue());
            }
            }
        }
        if (changes.containsKey("additionalTerms")) {
            String terms = text(changes.get("additionalTerms"), 10, 4000);
            diff.add(new AmendmentComparison("additionalTerms", "Thỏa thuận bổ sung", "Chưa có nội dung bổ sung này", terms));
            after.put("specialTerms", before.get("specialTerms") + "\nTHỎA THUẬN BỔ SUNG:\n" + terms);
        }
        if (diff.isEmpty() && !allowUnchanged) throw invalid("Chưa có thay đổi so với điều khoản đang áp dụng.");
        return new Result(after, diff);
    }

    private List<Map<String,Object>> charges(List<Map<String,Object>> requested, List<Map<String,Object>> original,
            Map<String,Object> tenant, Map<String,Object> property, boolean peopleChanged, boolean vehiclesChanged) {
        if (requested.size() > 50) throw invalid("Tối đa 50 khoản phí.");
        List<Map<String,Object>> result = new ArrayList<>(); Set<String> names = new HashSet<>();
        for (Map<String,Object> input : requested) {
            Map<String,Object> row = new LinkedHashMap<>(input);
            text(row.get("name"), 2, 150);
            if (!names.add(String.valueOf(row.get("name")).trim().toLowerCase(Locale.ROOT))) throw invalid("Khoản phí bị trùng.");
            if (!Set.of("name", "chargeType", "billingMethod", "billingMethodText", "unitAmount", "includedInRent", "note", "amountAndMethod", "estimatedMonthlyAmount").containsAll(row.keySet())) throw invalid("Trường khoản phí không hợp lệ.");
            String method = Objects.toString(row.get("billingMethod"), "");
            if (row.get("note") != null && (!(row.get("note") instanceof String) || row.get("note").toString().length() > 1000)) throw invalid("Ghi chú khoản phí quá dài.");
            boolean editable = Set.of("PER_MONTH", "PER_PERSON_MONTH", "PER_VEHICLE_MONTH", "PER_KWH", "PER_M3", "PER_M2_MONTH", "FREE", "INCLUDED", "NOT_APPLICABLE").contains(method);
            Optional<Map<String,Object>> old = original.stream().filter(r -> Objects.equals(r.get("name"), input.get("name"))).findFirst();
            String type = Objects.toString(row.get("chargeType"),"");
            if (old.isPresent() && !Objects.equals(old.get().get("chargeType"),row.get("chargeType")))
                throw invalid("Không được đổi loại khoản phí đã có trong hợp đồng.");
            if (old.isPresent() && old.get().equals(input) && (Boolean.TRUE.equals(row.get("includedInRent"))
                    || !(peopleChanged && "PER_PERSON_MONTH".equals(method)) && !(vehiclesChanged && "PER_VEHICLE_MONTH".equals(method)))) { result.add(row); continue; }
            if (!ChargeMethodRules.methods(type).contains(method)) throw invalid("Cách tính không phù hợp loại khoản phí: " + type);
            if (old.isEmpty() && !"OTHER".equals(type)) throw invalid("Khoản phí bổ sung phải là phí khác theo thỏa thuận, không giả mạo khoản phí từ bài đăng.");
            if (Set.of("ELECTRICITY", "WATER").contains(Objects.toString(row.get("chargeType"), ""))
                    && (old.isEmpty() || !Objects.equals(method, old.get().get("billingMethod"))
                        || !Objects.equals(row.get("includedInRent"), old.get().get("includedInRent"))))
                throw invalid("Điện/nước chỉ được đổi đơn giá, giữ nguyên phương pháp tính.");
            if (!editable) { if (old.isEmpty() || !old.get().equals(row)) throw invalid("Chưa hỗ trợ thay đổi phương pháp tính " + method); result.add(row); continue; }
            if (!(row.get("includedInRent") instanceof Boolean)) throw invalid("Cần chọn khoản phí đã bao gồm trong giá thuê.");
            BigDecimal rate = Set.of("FREE", "INCLUDED", "NOT_APPLICABLE").contains(method) ? BigDecimal.ZERO : money(row.get("unitAmount"), true);
            int quantity = switch (method) {
                case "PER_PERSON_MONTH" -> count(tenant.get("occupantCount"), 1, 100);
                case "PER_VEHICLE_MONTH" -> count(tenant.get("MOTORBIKE_PARKING".equals(row.get("chargeType")) ? "motorbikeCount" : "carCount"), 0, 100);
                default -> 1;
            };
            boolean included = Boolean.TRUE.equals(row.get("includedInRent")) || Set.of("FREE", "INCLUDED", "NOT_APPLICABLE").contains(method);
            BigDecimal total = included ? BigDecimal.ZERO : rate.multiply(BigDecimal.valueOf(quantity));
            if ("PER_M2_MONTH".equals(method) && !included) {
                Object area = property.get("areaM2");
                if (area == null && property.get("areaText") != null) area = property.get("areaText").toString().replace("m²","").trim();
                try {
                    BigDecimal value = new BigDecimal(Objects.toString(area,""));
                    if (value.signum() <= 0) throw new NumberFormatException();
                    total = rate.multiply(value).setScale(0,RoundingMode.HALF_UP);
                } catch (NumberFormatException e) { throw invalid("Chưa có diện tích hợp đồng để tính phí theo m²."); }
            }
            row.put("unitAmount", rate.toPlainString());
            row.put("includedInRent",included);
            row.put("estimatedMonthlyAmount", Set.of("PER_KWH", "PER_M3").contains(method) ? null : total.toPlainString());
            String unit = switch (method) { case "PER_KWH" -> "kWh"; case "PER_M3" -> "m³"; case "PER_PERSON_MONTH" -> "người / tháng"; case "PER_VEHICLE_MONTH" -> "xe / tháng"; case "PER_M2_MONTH" -> "m² / tháng"; default -> "tháng"; };
            row.put("billingMethodText",switch(method) { case "FREE" -> "Miễn phí"; case "INCLUDED" -> "Bao gồm trong tiền thuê"; case "NOT_APPLICABLE" -> "Không áp dụng"; default -> unit; });
            row.put("amountAndMethod", "FREE".equals(method) ? "Miễn phí" : "NOT_APPLICABLE".equals(method) ? "Không áp dụng" : included ? "Đã bao gồm trong tiền thuê" : ContractRenderService.formatVND(rate) + " đ / " + unit
                    + (Set.of("PER_PERSON_MONTH", "PER_VEHICLE_MONTH").contains(method) ? " × " + quantity + " = " + ContractRenderService.formatVND(total) + " đ / tháng" : "PER_M2_MONTH".equals(method) ? " = " + ContractRenderService.formatVND(total) + " đ / tháng" : ""));
            result.add(row);
        }
        // Removing a meter service would break the chain of readings.
        for (var row : original) if (Set.of("ELECTRICITY", "WATER").contains(Objects.toString(row.get("chargeType"), ""))
                && result.stream().noneMatch(r -> Objects.equals(r.get("name"), row.get("name")))) throw invalid("Không được xóa khoản điện/nước.");
        for (String method : List.of("PER_KWH", "PER_M3")) if (result.stream().filter(r -> method.equals(r.get("billingMethod"))).count() > 1) throw invalid("Chỉ được có một công tơ mỗi loại.");
        return result;
    }
    /** Compare actual fee data, never a regenerated sentence or an unrelated row in the table. */
    void compareCharges(List<AmendmentComparison> diff,List<Map<String,Object>> before,List<Map<String,Object>> after) {
        var names=new LinkedHashSet<String>();before.forEach(r->names.add(Objects.toString(r.get("name"),"")));after.forEach(r->names.add(Objects.toString(r.get("name"),"")));
        for(String name:names) {
            var a=before.stream().filter(r->name.equals(r.get("name"))).findFirst().orElse(null);
            var b=after.stream().filter(r->name.equals(r.get("name"))).findFirst().orElse(null);
            if(a!=null && b!=null && chargeValues(a).equals(chargeValues(b)))continue;
            String key="charges."+Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            diff.add(new AmendmentComparison(key,"Khoản phí · "+name,chargeDescription(a),chargeDescription(b)));
        }
    }
    private Map<String,String> chargeValues(Map<String,Object> row) {
        var values=new LinkedHashMap<String,String>();
        for(String key:List.of("chargeType","billingMethod","includedInRent","unitAmount","estimatedMonthlyAmount","note")) {
            String value=Objects.toString(row.get(key),"").trim();
            if(Set.of("unitAmount","estimatedMonthlyAmount").contains(key))try{value=new BigDecimal(value).stripTrailingZeros().toPlainString();}catch(NumberFormatException ignored){}
            values.put(key,value);
        }
        return values;
    }
    private String chargeDescription(Map<String,Object> row) {
        if(row==null)return "Không có";
        return Objects.toString(row.get("amountAndMethod"),"")+"\nĐơn giá: "+Objects.toString(row.get("unitAmount"),"Chưa có")
                +"\nCách tính: "+Objects.toString(row.get("billingMethodText"),Objects.toString(row.get("billingMethod"),""))
                +"\nTổng phí dự kiến: "+Objects.toString(row.get("estimatedMonthlyAmount"),"Theo sử dụng thực tế")
                +"\nGhi chú: "+Objects.toString(row.get("note"),"");
    }
    private void validateLimit(Object limit, Object value, String message) {
        if (limit == null || limit.toString().isBlank()) return;
        if (count(value,0,10000) > count(limit,0,10000)) throw invalid(message);
    }
    private void add(List<AmendmentComparison> diff, String field, String label, Object before, Object after) {
        if (Objects.equals(before, after)) return;
        if (before instanceof Boolean b) before = b ? "Được phép" : "Không được phép";
        if (after instanceof Boolean b) after = b ? "Được phép" : "Không được phép";
        String a = Objects.toString(before, "Chưa có"), b = Objects.toString(after, "Chưa có");
        try { if (new BigDecimal(a).compareTo(new BigDecimal(b)) == 0) return; } catch (NumberFormatException ignored) {}
        if (!a.equals(b)) diff.add(new AmendmentComparison(field, label, a, b));
    }
    private String describe(List<Map<String,Object>> rows, String kind) {
        if (rows.isEmpty()) return "Không có";
        return String.join("\n", rows.stream().map(r -> Objects.toString(r.get("name"), "") + ": " + switch(kind) {
            case "charges" -> Objects.toString(r.get("amountAndMethod"), "") + "; " + Objects.toString(r.get("note"), "");
            case "equipments" -> "SL " + r.get("quantity") + "; " + Objects.toString(r.get("condition"), "") + "; " + Objects.toString(r.get("note"), "");
            default -> "";
        }).toList());
    }
    public static String policyLabel(String k) { return switch(k) { case "subleasePolicy", "sublettingAllowed" -> "Cho thuê lại"; case "petPolicy", "petsAllowed" -> "Vật nuôi"; case "smokingPolicy", "smokingAllowed" -> "Hút thuốc"; case "overnightGuestPolicy" -> "Khách qua đêm"; default -> "Thông báo kiểm tra tài sản"; }; }
    public String write(Object v) { try { return mapper.writeValueAsString(v); } catch (Exception e) { throw invalid("Dữ liệu không hợp lệ."); } }
    public Map<String,Object> map(String json) { try { return json == null ? new LinkedHashMap<>() : mapper.readValue(json, new TypeReference<LinkedHashMap<String,Object>>() {}); } catch (Exception e) { throw invalid("Snapshot không hợp lệ."); } }
    public List<Map<String,Object>> rows(String json) { try { return json == null ? new ArrayList<>() : mapper.readValue(json, new TypeReference<ArrayList<Map<String,Object>>>() {}); } catch (Exception e) { throw invalid("Snapshot bảng không hợp lệ."); } }
    public Map<String,Object> object(Object v) { if (!(v instanceof Map<?,?>)) throw invalid("Dữ liệu phải là đối tượng."); return mapper.convertValue(v, new TypeReference<LinkedHashMap<String,Object>>() {}); }
    private List<Map<String,Object>> list(Object v) { if (!(v instanceof List<?> source) || source.stream().anyMatch(row -> !(row instanceof Map<?,?>))) throw invalid("Dữ liệu phải là danh sách các dòng hợp lệ."); try { return mapper.convertValue(v, new TypeReference<ArrayList<Map<String,Object>>>() {}); } catch (Exception e) { throw invalid("Dòng dữ liệu không hợp lệ."); } }
    public static String text(Object v, int min, int max) { if (!(v instanceof String s) || s.trim().length() < min || s.length() > max) throw invalid("Nội dung cần từ " + min + " đến " + max + " ký tự."); return s.trim(); }
    public static BigDecimal money(Object v, boolean zero) { try { BigDecimal n = new BigDecimal(Objects.toString(v, "")); if (n.signum() < (zero ? 0 : 1) || n.compareTo(new BigDecimal("1000000000")) > 0 || n.stripTrailingZeros().scale() > 0) throw new NumberFormatException(); return n; } catch (Exception e) { throw invalid("Số tiền phải là số nguyên hợp lệ (tối đa 1 tỷ đồng)."); } }
    public static int count(Object v, int min, int max) { try { int n = new BigDecimal(Objects.toString(v, "")).intValueExact(); if (n < min || n > max) throw new NumberFormatException(); return n; } catch (Exception e) { throw invalid("Số lượng không hợp lệ."); } }
    public static AppException invalid(String message) { return new AppException(ContractErrorCode.LIFECYCLE_INVALID, message); }
}
