package com.hs.contract.service;

import com.hs.contract.dto.amendment.AmendmentComparison;
import com.hs.contract.model.ContractRevision;
import com.hs.contract.service.engine.ContractRenderService;
import com.hs.contract.service.engine.VietnameseCurrencyTextConverter;
import java.math.*;
import java.util.*;
import java.time.*;
import java.time.format.*;

/** Hand-entered contractual terms. Never changes participant IDs or settled ledger records. */
final class FreeFormAmendmentTerms {
    private FreeFormAmendmentTerms() {}
    static final Set<String> KEYS = Set.of("freeForm", "fields", "rentAmount", "occupantCount", "motorbikeCount", "carCount",
            "charges", "equipments", "amenities", "featureRows", "policyRows", "specialTerms", "applyImmediately");
    private static final Map<String,Set<String>> FIELDS = Map.of(
            "landlord", Set.of("fullName", "idNumber", "bankName", "bankAccountNumber", "bankAccountHolder", "permanentAddress", "phone", "email"),
            "tenant", Set.of("fullName", "idNumber", "bankName", "bankAccountNumber", "bankAccountHolder", "permanentAddress", "phone", "email"),
            "property", Set.of("fullAddress", "areaText", "propertyType", "unitNumber", "floor", "rentalScope", "maxOccupants", "maxVehicles", "listingCode"),
            "lease", Set.of("startDateText", "endDateText", "durationMonths", "durationText", "handoverDateText"),
            "financial", Set.of("depositDescription", "depositAmountValue", "paymentCycle", "paymentMethod"),
            "policies", Set.of("latePaymentFeeMode", "latePaymentFeeAmount"));

    static AmendmentTermsBuilder.Result build(AmendmentTermsBuilder b, ContractRevision base, Map<String,Object> changes, boolean allowUnchanged) {
        if (!KEYS.containsAll(changes.keySet()) || !Boolean.TRUE.equals(changes.get("freeForm"))) throw bad("Nội dung phụ lục không hợp lệ.");
        if (changes.containsKey("applyImmediately") && !(changes.get("applyImmediately") instanceof Boolean)) throw bad("Thời điểm áp dụng không hợp lệ.");
        var before=b.snapshot(base); var after=b.snapshot(base);
        var diff=new ArrayList<AmendmentComparison>();
        var fields=map(changes.getOrDefault("fields",Map.of()));
        for (var group:fields.entrySet()) {
            if (!FIELDS.containsKey(group.getKey())) throw bad("Không được sửa giao dịch lịch sử hoặc mã tài khoản hệ thống.");
            var target=map(after.get(group.getKey())); var requested=map(group.getValue());
            if (!FIELDS.get(group.getKey()).containsAll(requested.keySet())) throw bad("Trường được bảo vệ: " + group.getKey());
            for(var entry:requested.entrySet()) {
                String k=entry.getKey(); Object value=entry.getValue();
                if (Set.of("maxOccupants","maxVehicles","durationMonths").contains(k))
                    value=integer(value,k.equals("durationMonths")?1:0,k.equals("durationMonths")?120:100);
                else if(Set.of("latePaymentFeeAmount","depositAmountValue").contains(k)) value=money(value,true).toPlainString();
                else value=string(value,Set.of("fullAddress","areaText","propertyType","phone").contains(k)?1:0,2000);
                if(k.endsWith("DateText")) value=date(value).format(DateTimeFormatter.ofPattern("dd/MM/uuuu"));
                if(k.equals("latePaymentFeeMode")) value=feeMode(value);
                target.put(k,value);
                add(diff,group.getKey()+"."+k,label(group.getKey(),k),map(before.get(group.getKey())).get(k),value);
            }
            after.put(group.getKey(),target);
        }
        var financial=map(after.get("financial")); var tenant=map(after.get("tenant")); var property=map(after.get("property"));
        if(fields.containsKey("property") && map(fields.get("property")).containsKey("areaText")) {
            try {
                var area=new BigDecimal(property.get("areaText").toString().replaceAll("(?i)\\s*(m²|m2|mét vuông)\\s*$","").replace(",",".").trim());
                if(area.signum()<=0 || area.compareTo(new BigDecimal("1000000000"))>0 || area.stripTrailingZeros().scale()>3)throw new NumberFormatException();
                property.put("areaM2",area.toPlainString());property.put("areaText",area.stripTrailingZeros().toPlainString()+" mét vuông");after.put("property",property);
            } catch(RuntimeException ex) { throw bad("Diện tích phải là số dương, tối đa 3 chữ số thập phân."); }
        }
        if(fields.containsKey("lease")) {
            var lease=map(after.get("lease"));var requested=map(fields.get("lease"));
            if(requested.keySet().stream().anyMatch(Set.of("startDateText","endDateText","durationMonths")::contains)) {
                LocalDate start=date(lease.get("startDateText"));
                if(requested.containsKey("endDateText") && !requested.containsKey("durationMonths")) {
                    LocalDate exclusive=date(lease.get("endDateText")).plusDays(1);
                    int months=Math.toIntExact(java.time.temporal.ChronoUnit.MONTHS.between(start,exclusive));
                    if(months<1 || months>120 || !start.plusMonths(months).equals(exclusive))throw bad("Ngày kết thúc phải khép đủ kỳ thuê tháng (tối đa 120 tháng).");
                    lease.put("durationMonths",months);
                } else {
                    int months=integer(lease.get("durationMonths"),1,120);
                    LocalDate end=start.plusMonths(months).minusDays(1);
                    if(requested.containsKey("endDateText")&&!end.equals(date(lease.get("endDateText"))))throw bad("Ngày kết thúc không khớp ngày bắt đầu và số tháng thuê.");
                    lease.put("endDateText",end.format(DateTimeFormatter.ofPattern("dd/MM/uuuu")));
                }
                if(!requested.containsKey("durationText"))lease.put("durationText",lease.get("durationMonths")+" tháng");
            }
            after.put("lease",lease);
            for(String key:List.of("startDateText","endDateText","durationMonths","durationText")) {
                diff.removeIf(d->d.field().equals("lease."+key));
                add(diff,"lease."+key,label("lease",key),map(before.get("lease")).get(key),lease.get(key));
            }
        }
        if(fields.containsKey("financial") && map(fields.get("financial")).containsKey("paymentCycle")) {
            int cycle=cycleMonths(financial.get("paymentCycle"));financial.put("paymentCycleMonths",cycle);
        }
        if(fields.containsKey("financial") && map(fields.get("financial")).containsKey("depositAmountValue")) {
            var deposit=money(financial.get("depositAmountValue"),true);
            financial.put("depositAmountNumber",ContractRenderService.formatVND(deposit));
            financial.put("depositAmountWords",VietnameseCurrencyTextConverter.toWords(deposit));
        }
        if (changes.containsKey("rentAmount")) {
            var amount=money(changes.get("rentAmount"),false);
            add(diff,"rentAmount","Giá thuê / tháng",financial.get("amountValue"),amount.toPlainString());
            financial.put("amountValue",amount.toPlainString()); financial.put("amountNumber",ContractRenderService.formatVND(amount)+"/tháng");
            financial.put("amountWords",VietnameseCurrencyTextConverter.toWords(amount));
        }
        for(String k:List.of("occupantCount","motorbikeCount","carCount")) if(changes.containsKey(k)) {
            int count=integer(changes.get(k),k.equals("occupantCount")?1:0,100);
            add(diff,k,label("tenant",k),tenant.get(k),count); tenant.put(k,count);
        }
        if(property.get("maxOccupants")!=null && integer(tenant.getOrDefault("occupantCount",1),1,100)>integer(property.get("maxOccupants"),0,100)) throw bad("Số người vượt giới hạn đang đề xuất.");
        after.put("financial",financial); after.put("tenant",tenant);
        if (changes.containsKey("featureRows")) {
            var requested=rows(changes.get("featureRows"));if(requested.size()>50)throw bad("Tối đa 50 đặc điểm tài sản.");
            var original=rows(map(before.get("property")).get("features"));
            if(requested.size()!=original.size())throw bad("Đặc điểm tài sản cố định: không thêm hoặc xóa hàng.");
            var names=new HashSet<String>();var normalized=new ArrayList<Map<String,Object>>();
            for(var row:requested) {
                if(!Set.of("featureName","featureValue").containsAll(row.keySet()))throw bad("Dòng đặc điểm không hợp lệ.");
                String name=string(row.get("featureName"),1,150),value=string(row.get("featureValue"),1,1000);
                if(!Objects.equals(name,original.get(normalized.size()).get("featureName")))throw bad("Không đổi tên hoặc thứ tự đặc điểm tài sản; chỉ sửa giá trị.");
                validateFeatureNumber(name,value);
                if(!names.add(name.toLowerCase(Locale.ROOT)))throw bad("Tên đặc điểm bị trùng.");
                normalized.add(new LinkedHashMap<>(Map.of("featureName",name,"featureValue",value)));
            }
            add(diff,"featureRows","Đặc điểm tài sản",b.write(property.getOrDefault("features",List.of())),b.write(normalized));
            property.put("features",normalized);after.put("property",property);
        }
        // The capacity rows mirror canonical fields; they must not contradict the main section.
        var features=rows(property.get("features"));
        for(var feature:features) {
            String name=Objects.toString(feature.get("featureName"),"");
            String key=Set.of("Số người tối đa","Số người ở tối đa").contains(name)?"maxOccupants":Set.of("Số xe tối đa","Số phương tiện tối đa").contains(name)?"maxVehicles":null;
            if(key==null||property.get(key)==null)continue;
            int canonical=integer(property.get(key),0,100);
            if(changes.containsKey("featureRows") && integer(Objects.toString(feature.get("featureValue"),"").replaceAll("\\s*(người|xe)$",""),0,100)!=canonical)
                throw bad("Giá trị bảng đặc điểm phải khớp trường "+label("property",key)+".");
            feature.put("featureValue",canonical+(key.equals("maxOccupants")?" người":" xe"));
        }
        if(property.containsKey("features")) {
            property.put("features",features);after.put("property",property);
            diff.removeIf(d->d.field().equals("featureRows"));
            add(diff,"featureRows","Đặc điểm tài sản",b.write(map(before.get("property")).getOrDefault("features",List.of())),b.write(features));
        }
        // Inherited Listing vehicle limits are informational, not amendment admission rules.
        // A new limit explicitly negotiated in this proposal must still be self-consistent.
        if(map(fields.get("property")).containsKey("maxVehicles") && integer(tenant.getOrDefault("motorbikeCount",0),0,100)+integer(tenant.getOrDefault("carCount",0),0,100)>integer(property.get("maxVehicles"),0,100))throw bad("Số xe vượt giới hạn đang đề xuất.");
        for(String key:List.of("charges","equipments","amenities")) {
            boolean areaChanged=fields.containsKey("property") && map(fields.get("property")).containsKey("areaText");
            boolean recompute=key.equals("charges") && (changes.containsKey("occupantCount") || changes.containsKey("motorbikeCount") || changes.containsKey("carCount") || areaChanged);
            if(!changes.containsKey(key) && !recompute) continue;
            var requested=rows(changes.getOrDefault(key,before.get(key))); var normalized=new ArrayList<Map<String,Object>>();
            if(requested.size()>50) throw bad("Tối đa 50 dòng trong mỗi bảng.");
            var names=new HashSet<String>();
            for(var input:requested) {
                var row=new LinkedHashMap<>(input); String name=string(row.get("name"),1,150);
                if(!names.add(name.toLowerCase(Locale.ROOT))) throw bad("Tên dòng bị trùng: "+name);
                row.put("name",name);
                Set<String> allowed=key.equals("charges") ? Set.of("name","chargeType","billingMethod","billingMethodText","unitAmount","includedInRent","note","amountAndMethod","estimatedMonthlyAmount")
                        : key.equals("equipments") ? Set.of("index","name","quantity","condition","status","note") : Set.of("index","code","name","sourceType");
                if(!allowed.containsAll(row.keySet())) throw bad("Bảng có trường không hỗ trợ.");
                if(key.equals("charges")) {
                    // Preserve untouched legacy rows; never silently rewrite a signed source snapshot.
                    var old=rows(before.get(key)).stream().filter(r->Objects.equals(r.get("name"),name)).findFirst();
                    String originalMethod=Objects.toString(row.get("billingMethod"),"");
                    boolean recalculate=!Boolean.TRUE.equals(row.get("includedInRent")) && ((changes.containsKey("occupantCount") && originalMethod.equals("PER_PERSON_MONTH"))
                            || (changes.containsKey("MOTORBIKE_PARKING".equals(row.get("chargeType"))?"motorbikeCount":"carCount") && originalMethod.equals("PER_VEHICLE_MONTH"))
                            || (areaChanged && originalMethod.equals("PER_M2_MONTH")));
                    if(!recalculate && old.filter(r->r.equals(input)).isPresent()) { normalized.add(row); continue; }
                    normalizeCharge(row,tenant,property,map(after.get("meters")));
                } else {
                    row.put("index",normalized.size()+1);
                    if(key.equals("equipments")) { row.put("quantity",integer(row.get("quantity"),1,1000)); row.put("condition",string(row.get("condition"),1,1000)); row.put("status",row.get("condition")); row.put("note",string(row.get("note"),0,1000)); }
                    else { row.put("sourceType","AGREED_AMENDMENT"); }
                }
                normalized.add(row);
            }
            if(key.equals("charges")) for(String method:List.of("PER_KWH","PER_M3")) {
                if(normalized.stream().filter(r->method.equals(r.get("billingMethod"))).count()>1) throw bad("Chỉ được có một công tơ mỗi loại.");
                boolean had=rows(before.get("charges")).stream().anyMatch(r->method.equals(r.get("billingMethod"))&&!Boolean.TRUE.equals(r.get("includedInRent")));
                boolean has=normalized.stream().anyMatch(r->method.equals(r.get("billingMethod"))&&!Boolean.TRUE.equals(r.get("includedInRent")));
                if(had!=has)throw bad("Phụ lục không được ngắt hoặc tạo mới chuỗi công tơ đã chốt; giữ cách tính công tơ và chỉnh đơn giá/ghi chú.");
            }
            if(key.equals("charges"))b.compareCharges(diff,rows(before.get(key)),normalized);
            else add(diff,key,key.equals("equipments")?"Nội thất và tài sản bàn giao":"Tiện ích và quyền sử dụng",describe(rows(before.get(key))),describe(normalized));
            after.put(key,normalized);
        }
        var policy=map(after.get("policies"));
        if(changes.containsKey("policyRows")) {
            var requested=rows(changes.get("policyRows")); if(requested.size()>50) throw bad("Tối đa 50 chính sách.");
            var names=new HashSet<String>(); var codes=new HashSet<String>(); var normalized=new ArrayList<Map<String,Object>>();
            for(var input:requested) {
                if(!Set.of("code","label","text").containsAll(input.keySet())) throw bad("Dòng chính sách không hợp lệ.");
                String code=string(input.get("code"),1,80),name=string(input.get("label"),1,150),text=string(input.get("text"),1,2000);
                if(!code.matches("[A-Za-z][A-Za-z0-9_]*") || !codes.add(code) || !names.add(name.toLowerCase(Locale.ROOT))) throw bad("Mã hoặc tên chính sách bị trùng/sai.");
                if(code.startsWith("latePayment") || Set.of("listingPolicies","paymentDueDay","paymentDueOffsetDays","paymentCycle","billingRules","paymentWindowDays","lateFeeGraceDays","overdueActionDays","maximumPenaltyDays").contains(code)) throw bad("Chính sách dùng mã được bảo vệ của quy trình hệ thống.");
                var row=new LinkedHashMap<String,Object>();row.put("code",code);row.put("label",name);row.put("valueType","TEXT");row.put("value",text);row.put("note","");row.put("displayValue",text);row.put("sortOrder",normalized.size());
                if(Set.of("depositRefundDays","noticeDaysBeforeTermination","propertyInspectionNotice").contains(code)) {
                    int days=integer(text,code.equals("depositRefundDays")?1:0,code.equals("propertyInspectionNotice")?168:180);String unit=code.equals("propertyInspectionNotice")?"giờ":"ngày";row.put("valueType","INTEGER");row.put("value",Integer.toString(days));row.put("unitLabel",unit);row.put("displayValue",days+" "+unit);
                    policy.put(code,days);if(code.equals("noticeDaysBeforeTermination"))policy.put("noticeDaysBeforeMoveOut",days);
                } else policy.put(code,text);
                normalized.add(row);
            }
            for(String mandatory:List.of("depositRefundDays","noticeDaysBeforeTermination"))
                if(policy.get(mandatory)!=null && !codes.contains(mandatory))throw bad("Giữ thời hạn hoàn cọc và báo trước; có thể nhập số ngày mới.");
            for(String legacy:List.of("petPolicy","smokingPolicy","subleasePolicy","overnightGuestPolicy","propertyInspectionNotice"))
                if(!codes.contains(legacy))policy.remove(legacy);
            // Clearing a displayed rule must not leave its old canonical value active in lifecycle.
            for(var old:rows(policy.get("listingPolicies"))) if(!codes.contains(Objects.toString(old.get("code")))) {
                String code=Objects.toString(old.get("code"));
                if(Set.of("depositRefundDays","noticeDaysBeforeTermination").contains(code)) throw bad("Giữ thời hạn hoàn cọc và báo trước; có thể nhập số ngày mới.");
                policy.remove(code);
            }
            for(String flag:List.of("petsAllowed","smokingAllowed","sublettingAllowed"))policy.put(flag,null);
            policy.put("listingPolicies",normalized);
            add(diff,"policyRows","Chính sách vận hành",describe(rows(map(before.get("policies")).get("listingPolicies"))),describe(normalized));
        }
        if(fields.containsKey("policies")) {
            policy=LatePaymentPolicy.forNewAgreement(policy,map(before.get("policies")));
            diff.removeIf(d->d.field().startsWith("policies.latePayment"));
            for(String key:List.of("latePaymentFeeMode","latePaymentFeeAmount","latePaymentFeeGraceDays","latePaymentFeeCap"))
                add(diff,"policies."+key,label("policies",key),map(before.get("policies")).get(key),policy.get(key));
        }
        LatePaymentPolicy.from(policy);
        after.put("policies",policy);
        if(changes.containsKey("specialTerms")) { String value=string(changes.get("specialTerms"),0,8000).replace(com.hs.contract.service.engine.ContractDataBuilder.DEFAULT_OVERDUE_TERMS,"").trim();add(diff,"specialTerms","Điều khoản thỏa thuận khác",before.get("specialTerms"),value);after.put("specialTerms",value); }
        boolean financialChange=changes.keySet().stream().anyMatch(Set.of("rentAmount","charges","occupantCount","motorbikeCount","carCount")::contains)
                || fields.containsKey("financial") || fields.containsKey("lease") || fields.containsKey("policies") || (fields.containsKey("property") && map(fields.get("property")).containsKey("areaText"));
        if(Boolean.TRUE.equals(changes.get("applyImmediately")) && financialChange) throw bad("Thay đổi ảnh hưởng tiền phải áp dụng từ đầu kỳ tương lai.");
        if(diff.isEmpty()&&!allowUnchanged)throw bad("Chưa có thay đổi so với điều khoản đang áp dụng.");
        return new AmendmentTermsBuilder.Result(after,diff);
    }
    private static void normalizeCharge(Map<String,Object> r,Map<String,Object> tenant,Map<String,Object> property,Map<String,Object> meters) {
        String entered=string(r.get("billingMethod"),1,100).toLowerCase(Locale.ROOT).replaceAll("\\s*/\\s*"," / ");
        String method=method(entered); String type=Objects.toString(r.get("chargeType"),"OTHER");
        if(entered.equals("ô tô / tháng"))type="CAR_PARKING";
        if(entered.equals("xe máy / tháng"))type="MOTORBIKE_PARKING";
        if(!Set.of("ELECTRICITY","WATER","INTERNET","SERVICE_OR_GARBAGE","MOTORBIKE_PARKING","CAR_PARKING","MANAGEMENT","OTHER").contains(type))type="OTHER";
        if(method.equals("PER_VEHICLE_MONTH")&&!Set.of("CAR_PARKING","MOTORBIKE_PARKING").contains(type))throw bad("Phí theo xe phải nhập cách tính Ô tô / tháng hoặc Xe máy / tháng, không suy loại xe từ tên phí.");
        if(r.get("includedInRent")!=null && !(r.get("includedInRent") instanceof Boolean))throw bad("Trạng thái bao gồm trong giá thuê không hợp lệ.");
        boolean included=Set.of("FREE","INCLUDED","NOT_APPLICABLE").contains(method)||Boolean.TRUE.equals(r.get("includedInRent"));
        if(Set.of("FREE","INCLUDED","NOT_APPLICABLE").contains(method) && r.get("unitAmount")!=null && !string(r.get("unitAmount"),0,40).isBlank() && money(r.get("unitAmount"),true).signum()>0)
            throw bad("Khoản miễn phí/bao gồm/không áp dụng phải có đơn giá 0; chỉnh cách tính trước khi thu tiền.");
        BigDecimal rate=included?BigDecimal.ZERO:money(r.get("unitAmount"),true),quantity=BigDecimal.ONE;
        if(method.equals("PER_PERSON_MONTH"))quantity=BigDecimal.valueOf(integer(tenant.getOrDefault("occupantCount",1),1,100));
        if(method.equals("PER_VEHICLE_MONTH"))quantity=BigDecimal.valueOf(integer(tenant.getOrDefault(type.equals("CAR_PARKING")?"carCount":"motorbikeCount",0),0,100));
        if(method.equals("PER_M2_MONTH")) {
            String area=Objects.toString(property.get("areaText"),"").replace("m²","").replace("m2","").replace("mét vuông","").trim().replace(",",".");
            try{quantity=new BigDecimal(area);if(quantity.signum()<=0)throw new NumberFormatException();}catch(NumberFormatException e){throw bad("Nhập diện tích hợp lệ để tính phí theo m².");}
        }
        if(Set.of("PER_KWH","PER_M3").contains(method)) {
            String key=method.equals("PER_KWH")?"electricityInitial":"waterInitial";
            if(meters.get(key)==null || Objects.toString(meters.get(key)).isBlank())throw bad("Chưa có chỉ số ban đầu cho công tơ mới.");
            type=method.equals("PER_KWH")?"ELECTRICITY":"WATER";
        }
        BigDecimal total=rate.multiply(quantity).setScale(0,RoundingMode.HALF_UP);if(total.compareTo(new BigDecimal("1000000000"))>0)throw bad("Tổng phí quá lớn.");
        String unit=switch(method){case "PER_KWH"->"kWh";case "PER_M3"->"m³";case "PER_PERSON_MONTH"->"người / tháng";case "PER_VEHICLE_MONTH"->"xe / tháng";case "PER_M2_MONTH"->"mét vuông / tháng";default->"tháng";};
        r.put("billingMethod",method);r.put("chargeType",type);r.put("unitAmount",rate.toPlainString());r.put("includedInRent",included);
        r.put("estimatedMonthlyAmount",Set.of("PER_KWH","PER_M3").contains(method)?null:total.toPlainString());r.put("billingMethodText",unit);
        r.put("amountAndMethod",included?(method.equals("FREE")?"Miễn phí":method.equals("NOT_APPLICABLE")?"Không áp dụng":"Đã bao gồm trong tiền thuê")
                :ContractRenderService.formatVND(rate)+" / "+unit+(quantity.compareTo(BigDecimal.ONE)!=0?" = "+ContractRenderService.formatVND(total)+" / tháng":""));
        r.put("note",string(r.get("note"),0,1000));
    }
    static LocalDate date(Object value) {
        try { return LocalDate.parse(string(value,10,10),DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)); }
        catch(RuntimeException ex) { throw bad("Ngày không hợp lệ; cần ngày/tháng/năm có thực."); }
    }
    static int cycleMonths(Object value) {
        String s=Objects.toString(value,"").trim().toLowerCase(Locale.ROOT);
        if(Set.of("hàng tháng","tháng","monthly").contains(s))return 1;
        if(Set.of("hàng quý","quý","quarterly").contains(s))return 3;
        if(Set.of("hàng năm","năm","yearly").contains(s))return 12;
        var m=java.util.regex.Pattern.compile("(?:mỗi\\s+)?(\\d{1,2})\\s*(?:tháng(?:\\s*/\\s*lần)?)?").matcher(s);
        if(m.matches())return integer(m.group(1),1,12);
        throw bad("Chu kỳ thanh toán: nhập Hàng tháng, Hàng quý, Hàng năm hoặc Mỗi N tháng (1–12).");
    }
    private static void validateFeatureNumber(String name,String value) {
        boolean count=Set.of("Số mặt tiền","Tổng số tầng","Số người tối đa","Số người ở tối đa","Số xe tối đa","Số phương tiện tối đa","Số phòng ngủ","Số phòng tắm / WC","Phòng khách","Phòng bếp").contains(name);
        boolean dimension=Set.of("Diện tích đất","Diện tích sử dụng","Diện tích phòng","Mặt tiền","Chiều dài","Độ rộng đường vào","Độ rộng đường/hẻm").contains(name);
        if(!count&&!dimension)return;
        String number=value.replaceAll("\\s*(mét vuông|m²|m2|mặt tiền|tầng|người|xe|phòng|m)$","").trim().replace(",",".");
        if(count)integer(number,0,1000);
        else try { var n=new BigDecimal(number);if(n.signum()<0||n.compareTo(new BigDecimal("1000000000"))>0||n.stripTrailingZeros().scale()>3)throw new NumberFormatException(); }
        catch(RuntimeException ex){throw bad("Giá trị đặc điểm "+name+" phải là số không âm, tối đa 3 số lẻ.");}
    }
    private static String method(String s){return switch(s.trim().toLowerCase(Locale.ROOT)){
        case "tháng","theo tháng","per_month"->"PER_MONTH";case "người / tháng","theo người / tháng","per_person_month"->"PER_PERSON_MONTH";
        case "xe / tháng","xe máy / tháng","ô tô / tháng","theo xe / tháng","per_vehicle_month"->"PER_VEHICLE_MONTH";case "kwh","per_kwh"->"PER_KWH";case "m³","m3","per_m3"->"PER_M3";
        case "mét vuông / tháng","m² / tháng","m2 / tháng","per_m2_month"->"PER_M2_MONTH";case "miễn phí","free"->"FREE";
        case "bao gồm","đã bao gồm","bao gồm trong tiền thuê","included"->"INCLUDED";case "không áp dụng","not_applicable"->"NOT_APPLICABLE";
        default->throw bad("Cách tính phí chưa được hệ thống tính tự động. Nhập Tháng, Người / tháng, Xe / tháng, kWh, m³, m² / tháng, Miễn phí, Bao gồm hoặc Không áp dụng.");};}
    private static String feeMode(Object v){return switch(Objects.toString(v).trim().toLowerCase(Locale.ROOT)){case "none","không phạt"->"NONE";case "fixed_once","một lần"->"FIXED_ONCE";case "fixed_per_day","mỗi ngày"->"FIXED_PER_DAY";default->throw bad("Nhập Không phạt, Một lần hoặc Mỗi ngày.");};}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object v){if(v==null)return new LinkedHashMap<>();if(!(v instanceof Map<?,?>))throw bad("Nhóm dữ liệu không hợp lệ.");return new LinkedHashMap<>((Map<String,Object>)v);}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(Object v){if(v==null)return new ArrayList<>();if(!(v instanceof List<?> l)||l.stream().anyMatch(r->!(r instanceof Map<?,?>)))throw bad("Bảng dữ liệu không hợp lệ.");return ((List<Map<String,Object>>)v).stream().map(LinkedHashMap::new).map(r->(Map<String,Object>)r).toList();}
    static String string(Object v,int min,int max){if(v!=null && !(v instanceof String) && !(v instanceof Number))throw bad("Cần nhập văn bản hoặc số.");String s=Objects.toString(v,"").trim();if(s.length()<min||s.length()>max)throw bad("Nội dung thiếu hoặc quá dài.");return s;}
    static int integer(Object v,int min,int max){try{int n=new BigDecimal(string(v,1,30)).intValueExact();if(n<min||n>max)throw new ArithmeticException();return n;}catch(RuntimeException e){throw bad("Nhập số nguyên trong khoảng "+min+"–"+max+".");}}
    static BigDecimal money(Object v,boolean zero){try{var n=new BigDecimal(string(v,1,40));if(n.signum()<(zero?0:1)||n.stripTrailingZeros().scale()>0||n.compareTo(new BigDecimal("1000000000"))>0)throw new ArithmeticException();return n;}catch(RuntimeException e){throw bad("Số tiền phải là số nguyên không âm, tối đa 1 tỷ đồng.");}}
    static void add(List<AmendmentComparison> d,String k,String label,Object a,Object b){if(!Objects.equals(Objects.toString(a,""),Objects.toString(b,"")))d.add(new AmendmentComparison(k,label,Objects.toString(a,"Chưa có"),Objects.toString(b,"Chưa có")));}
    static String describe(List<Map<String,Object>> rows){return rows.isEmpty()?"Không có":String.join("\n",rows.stream().map(r->r.containsKey("label")?r.get("label")+": "+Objects.toString(r.get("displayValue"),Objects.toString(r.get("text"),"")):r.get("name")+": "+(r.containsKey("quantity")?"SL "+r.get("quantity")+"; "+r.get("condition")+"; "+r.get("note"):r.containsKey("amountAndMethod")?r.get("amountAndMethod")+"; "+r.get("note"):"")).toList());}
    static String label(String g,String k){return switch(k){case "permanentAddress"->(g.equals("landlord")?"Bên A":"Bên B")+" · Nơi thường trú";case "phone"->(g.equals("landlord")?"Bên A":"Bên B")+" · Điện thoại";case "email"->(g.equals("landlord")?"Bên A":"Bên B")+" · Email";case "fullAddress"->"Địa chỉ tài sản";case "propertyType"->"Loại hình";case "areaText"->"Diện tích";case "unitNumber"->"Số căn / phòng";case "floor"->"Tầng";case "rentalScope"->"Phạm vi thuê";case "maxOccupants"->"Số người tối đa";case "maxVehicles"->"Số xe tối đa";case "fullName"->(g.equals("landlord")?"Bên A":"Bên B")+" · Họ và tên";case "idNumber"->(g.equals("landlord")?"Bên A":"Bên B")+" · CCCD / Định danh";case "bankName"->(g.equals("landlord")?"Bên A":"Bên B")+" · Ngân hàng";case "bankAccountNumber"->(g.equals("landlord")?"Bên A":"Bên B")+" · Số tài khoản";case "bankAccountHolder"->(g.equals("landlord")?"Bên A":"Bên B")+" · Chủ tài khoản";case "listingCode"->"Mã tin trên văn bản";case "startDateText"->"Ngày bắt đầu thuê";case "endDateText"->"Ngày kết thúc thuê";case "durationMonths"->"Số tháng thuê";case "durationText"->"Thời hạn thuê";case "depositAmountValue"->"Số tiền cọc thỏa thuận";case "paymentCycle"->"Kỳ hạn thanh toán";case "paymentMethod"->"Phương thức thanh toán";case "handoverDateText"->"Ngày bàn giao";case "depositDescription"->"Điều kiện hoàn cọc";case "paymentDueOffsetDays"->"Số ngày đến hạn thanh toán";case "occupantCount"->"Số người cư trú";case "motorbikeCount"->"Xe máy đăng ký";case "carCount"->"Ô tô đăng ký";case "latePaymentFeeMode"->"Cách tính phí chậm trả";case "latePaymentFeeAmount"->"Mức phí chậm trả";case "latePaymentFeeGraceDays"->"Ngày ân hạn";case "latePaymentFeeCap"->"Trần phí chậm trả";default->k;};}
    static RuntimeException bad(String s){return AmendmentTermsBuilder.invalid(s);}
}
