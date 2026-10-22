package com.hs.contract.service.engine;

import com.deepoove.poi.XWPFTemplate;
import com.deepoove.poi.data.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.contract.service.MonthlyBillingSchedule;
import com.hs.contract.service.ContractPolicyTerms;
import com.hs.contract.service.LatePaymentPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContractRenderService {

    private final ObjectMapper objectMapper;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * Render dữ liệu vào template Word (.docx) và trả về mảng byte kết quả
     */
    public byte[] renderDocx(InputStream templateInputStream, Map<String, Object> dataModel) throws Exception {
        byte[] bytes = templateInputStream.readAllBytes();
        boolean policyTable = false;
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry=zip.getNextEntry()) != null) if ("word/document.xml".equals(entry.getName())) {
                policyTable = new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).contains("{{#policiesTable}}"); break;
            }
        }
        var resolved = new HashMap<>(dataModel);
        if (policyTable && dataModel.get("contract") instanceof Map<?,?> original) {
            var contract = new LinkedHashMap<String,Object>(); original.forEach((k,v) -> contract.put(k.toString(),v));
            String terms = Objects.toString(contract.get("specialTerms"),"");
            int index = terms.lastIndexOf("\nCHÍNH SÁCH ÁP DỤNG CHO TÀI SẢN THUÊ:\n");
            if (index >= 0) contract.put("specialTerms",terms.substring(0,index));
            resolved.put("contract",contract);
        }
        bytes = LeaseDurationRenderCleanup.normalize(bytes, resolved);
        try (XWPFTemplate template = XWPFTemplate.compile(new java.io.ByteArrayInputStream(bytes)).render(resolved)) {
            // These are document-generation facts; actual signature timestamps live in the PDF signatures.
            for(var paragraph:template.getXWPFDocument().getParagraphs()) {
                String text=paragraph.getText();
                String adjusted=text.replace("Ngày ký:","Ngày lập văn bản:");
                if(adjusted.contains("Bảng ghi nhận thanh toán ban đầu"))
                    adjusted=adjusted.replace("đã được hai bên xác nhận","theo trạng thái từng khoản");
                if(!adjusted.equals(text)) {
                    var style=paragraph.getRuns().isEmpty()?null:paragraph.getRuns().getFirst().getCTR().getRPr();
                    var copy=style==null?null:(org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr)style.copy();
                    for(int n=paragraph.getRuns().size()-1;n>=0;n--)paragraph.removeRun(n);
                    var run=paragraph.createRun();if(copy!=null)run.getCTR().setRPr(copy);run.setText(adjusted);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            template.write(out);
            return out.toByteArray();
        }
    }

    /**
     * Chuẩn bị data model V1 (tương thích ngược) cho poi-tl từ các trường snapshot của ContractRevision
     */
    public Map<String, Object> buildDataModelFromSnapshots(
            Map<String, Object> landlord,
            Map<String, Object> tenant,
            Map<String, Object> property,
            Map<String, Object> lease,
            Map<String, Object> financial,
            List<Map<String, Object>> charges,
            List<Map<String, Object>> equipments,
            Map<String, Object> meters,
            String contractNumber,
            LocalDate signingDate,
            String signingCity
    ) {
        return buildDataModelFromSnapshots(
                landlord, tenant, property, lease, financial, charges, equipments, meters,
                null, null, null, null, 1, 1, contractNumber, signingDate, signingCity
        );
    }

    /**
     * Chuẩn bị data model V2 hoàn chỉnh cho poi-tl bao gồm cả các bảng động và trường mới V2.
     */
    public Map<String, Object> buildDataModelFromSnapshots(
            Map<String, Object> landlord,
            Map<String, Object> tenant,
            Map<String, Object> property,
            Map<String, Object> lease,
            Map<String, Object> financial,
            List<Map<String, Object>> charges,
            List<Map<String, Object>> equipments,
            Map<String, Object> meters,
            Map<String, Object> initialPayment,
            List<Map<String, Object>> amenities,
            Map<String, Object> policies,
            String specialTerms,
            Integer schemaVersion,
            Integer revisionNumber,
            String contractNumber,
            LocalDate signingDate,
            String signingCity
    ) {
        Map<String, Object> model = new HashMap<>();

        putLandlordFields(model, landlord);
        putTenantFields(model, tenant);
        putPropertyFields(model, property);
        putLeaseFields(model, lease);
        putFinancialFields(model, financial);
        putMeterFields(model, meters);
        putInitialPaymentFields(model, initialPayment, financial);
        model.put("policies", policies != null ? new LinkedHashMap<>(policies) : Map.of());

        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("number", contractNumber != null ? contractNumber : "HD-" + System.currentTimeMillis());
        contract.put("signingDate", signingDate != null ? signingDate.format(DATE_FORMATTER) : LocalDate.now().format(DATE_FORMATTER));
        contract.put("signingCity", signingCity != null ? signingCity : "Thành phố Hồ Chí Minh");
        contract.put("schemaVersion", schemaVersion != null ? String.valueOf(schemaVersion) : "2");
        contract.put("revisionNumber", revisionNumber != null ? String.valueOf(revisionNumber) : "1");
        String terms = specialTerms == null ? "" : specialTerms.trim();
        String deadline=financial==null?"":Objects.toString(financial.get("paymentDueDay"),"");
        if(!deadline.isBlank())terms+=(terms.isEmpty()?"":"\n")+"Hạn thanh toán theo quy trình hệ thống: "+deadline+".";
        String lateFeeClause=LatePaymentPolicy.from(policies).clause();
        if(!lateFeeClause.isBlank())terms+=(terms.isEmpty()?"":"\n")+lateFeeClause;
        String policyClause = ContractPolicyTerms.clause(policies);
        if (!policyClause.isBlank()) terms = terms.isEmpty() ? policyClause : terms + "\n" + policyClause;
        // Compatibility for old Word templates without #policiesTable. New templates should
        // place that dynamic table at the policy section; this text keeps every listing rule visible.
        String listingRules = policies == null ? "" : ContractListingPolicies.text(policies);
        if (!listingRules.isBlank()) terms += "\nCHÍNH SÁCH ÁP DỤNG CHO TÀI SẢN THUÊ:\n" + listingRules;
        contract.put("specialTerms", terms);
        model.put("contract", contract);

        // Bảng động
        model.put("chargesTable", buildChargesTable(charges));
        model.put("equipmentTable", buildEquipmentTable(equipments));
        model.put("propertyFeaturesTable", buildPropertyFeaturesTable(property));
        model.put("amenitiesTable", buildAmenitiesTable(amenities));
        model.put("initialPaymentTable", buildInitialPaymentTable(initialPayment, financial));
        model.put("policiesTable", buildPoliciesTable(policies));

        return model;
    }

    /**
     * Đọc giá trị theo đường dẫn chấm (vd. {@code landlord.fullName}) từ model nested của poi-tl.
     */
    @SuppressWarnings("unchecked")
    public static Object resolvePath(Map<String, Object> model, String dottedKey) {
        if (model == null || dottedKey == null || dottedKey.isBlank()) {
            return null;
        }
        Object current = model;
        for (String part : dottedKey.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /**
     * Sinh data model giả lập (Dummy Data) dùng cho Admin xem trước mẫu Word
     */
    public Map<String, Object> buildDummyDataModel() {
        Map<String, Object> landlord = Map.of(
                "fullName", "Nguyễn Văn An (Chủ nhà)",
                "idNumber", "079090001234",
                "permanentAddress", "123 Nguyễn Huệ, Phường Bến Nghé, Quận 1, TP.HCM",
                "phone", "0901234567",
                "email", "nguyenvanan.landlord@example.com"
        );

        Map<String, Object> tenant = new LinkedHashMap<>();
        tenant.put("fullName", "Trần Thị Bình (Người thuê)");
        tenant.put("idNumber", "079195009876");
        tenant.put("permanentAddress", "456 Lê Lợi, Phường Bến Thành, Quận 1, TP.HCM");
        tenant.put("phone", "0987654321");
        tenant.put("email", "tranthibinh.tenant@example.com");
        tenant.put("occupantCount", 2);
        tenant.put("motorbikeCount", 1);
        tenant.put("carCount", 0);

        Map<String, Object> property = new LinkedHashMap<>();
        property.put("listingCode", "HS-2026-DEMO");
        property.put("rentalScope", "Thuê toàn bộ căn hộ chung cư");
        property.put("fullAddress", "Tầng 12, Căn hộ A12-08, Tòa tháp Landmark, 720A Điện Biên Phủ, Phường 22, Quận Bình Thạnh, TP.HCM");
        property.put("areaText", "75 mét vuông");
        property.put("propertyType", "Căn hộ chung cư");
        property.put("unitNumber", "A12-08");
        property.put("floor", "Tầng 12");
        property.put("maxOccupants", 4);
        property.put("maxVehicles", 2);

        Map<String, Object> lease = Map.of(
                "startDateText", "15/09/2026",
                "endDateText", "14/09/2027",
                "durationMonths", 12,
                "durationText", "1 năm (12 tháng)",
                "handoverDateText", "15/09/2026"
        );

        Map<String, Object> financial = Map.of(
                "amountValue", "15000000",
                "amountNumber", "15.000.000 VNĐ/tháng",
                "amountWords", "Mười lăm triệu đồng chẵn",
                "paymentCycle", "Hàng tháng",
                "paymentDueDay", MonthlyBillingSchedule.paymentDueDescription(),
                "paymentMethod", "Chuyển khoản trực tiếp vào tài khoản ngân hàng của Bên A chỉ định trong Hợp đồng này",
                "depositAmountValue", "15000000",
                "depositAmountNumber", "15.000.000 VNĐ",
                "depositAmountWords", "Mười lăm triệu đồng chẵn",
                "depositDescription", "Khi hết hạn và hoàn tất bàn giao, Bên A hoàn cho Bên B phần cọc còn phải hoàn sau quyết toán, trừ trường hợp xử lý cọc theo điều khoản vi phạm hoặc chấm dứt đã ký."
        );

        Map<String, Object> initialPayment = Map.of(
                "status", "Đã thanh toán",
                "paidAt", "15/09/2026 10:30:00",
                "provider", "MOCK",
                "transactionCode", "TXN-20260915-DEMO",
                "monthlyRent", "15.000.000 VNĐ",
                "monthlyCharges", "120.000 VNĐ",
                "depositAmount", "15.000.000 VNĐ",
                "totalAmount", "30.120.000 VNĐ",
                "currency", "VND"
        );

        List<Map<String, Object>> amenities = List.of(
                Map.of("index", 1, "code", "WIFI", "name", "WiFi"),
                Map.of("index", 2, "code", "AIR_CONDITIONER", "name", "Máy lạnh"),
                Map.of("index", 3, "code", "CUSTOM", "name", "Ban công đón gió")
        );

        List<Map<String, Object>> charges = List.of(
                Map.of("name", "Điện sinh hoạt", "amountAndMethod", "3.500 VNĐ / kWh", "note", "Tính theo công tơ riêng của căn hộ"),
                Map.of("name", "Nước sinh hoạt", "amountAndMethod", "100.000 VNĐ / người / tháng", "note", "Theo số người đăng ký lưu trú thực tế"),
                Map.of("name", "Internet Wifi", "amountAndMethod", "Đã bao gồm trong giá thuê", "note", "Gói cước tốc độ cao 150 Mbps"),
                Map.of("name", "Phí gửi xe máy", "amountAndMethod", "120.000 VNĐ / xe / tháng", "note", "Theo số lượng xe đăng ký với BQL"),
                Map.of("name", "Phí quản lý tòa nhà", "amountAndMethod", "Đã bao gồm trong giá thuê", "note", "Bao gồm phí bảo vệ 24/7 và dọn vệ sinh hành lang")
        );

        List<Map<String, Object>> equipments = List.of(
                Map.of("index", 1, "name", "Máy lạnh Daikin Inverter 1.5 HP", "quantity", 2, "condition", "Hoạt động tốt, làm lạnh nhanh"),
                Map.of("index", 2, "name", "Tủ lạnh Panasonic 2 cánh 250L", "quantity", 1, "condition", "Mới 95%, nguyên bản"),
                Map.of("index", 3, "name", "Bộ sofa phòng khách + bàn trà", "quantity", 1, "condition", "Nguyên vẹn, không rách trầy"),
                Map.of("index", 4, "name", "Giường ngủ gỗ sồi 1m8 x 2m kèm nệm", "quantity", 2, "condition", "Mới, nệm cao su sạch sẽ")
        );

        Map<String, Object> meters = Map.of(
                "electricityInitial", "1.250 kWh",
                "waterInitial", "85 m³"
        );

        return buildDataModelFromSnapshots(
                landlord, tenant, property, lease, financial, charges, equipments, meters,
                initialPayment, amenities,
                Map.of("latePaymentFeeMode", "NONE", "overdueLandlordTerminationAfterFiveDays", true),
                ContractDataBuilder.DEFAULT_OVERDUE_TERMS, 3, 1,
                "HD-20260905-DEMO", LocalDate.now(), "Thành phố Hồ Chí Minh"
        );
    }

    private void putLandlordFields(Map<String, Object> model, Map<String, Object> l) {
        Map<String, Object> src = l != null ? l : Map.of();
        Map<String, Object> landlord = new LinkedHashMap<>();
        landlord.put("fullName", getStr(src, "fullName", ""));
        landlord.put("idNumber", getStr(src, "idNumber", ""));
        landlord.put("idIssueDate", getStr(src, "idIssueDate", ""));
        landlord.put("idIssuePlace", getStr(src, "idIssuePlace", ""));
        landlord.put("permanentAddress", getStr(src, "permanentAddress", ""));
        landlord.put("phone", getStr(src, "phone", ""));
        landlord.put("email", getStr(src, "email", ""));
        landlord.put("bankName", getStr(src, "bankName", ""));
        landlord.put("bankAccountNumber", getStr(src, "bankAccountNumber", ""));
        landlord.put("bankAccountHolder", getStr(src, "bankAccountHolder", ""));
        model.put("landlord", landlord);
    }

    private void putTenantFields(Map<String, Object> model, Map<String, Object> t) {
        Map<String, Object> src = t != null ? t : Map.of();
        Map<String, Object> tenant = new LinkedHashMap<>();
        tenant.put("fullName", getStr(src, "fullName", ""));
        tenant.put("idNumber", getStr(src, "idNumber", ""));
        tenant.put("idIssueDate", getStr(src, "idIssueDate", ""));
        tenant.put("idIssuePlace", getStr(src, "idIssuePlace", ""));
        tenant.put("permanentAddress", getStr(src, "permanentAddress", ""));
        tenant.put("phone", getStr(src, "phone", ""));
        tenant.put("email", getStr(src, "email", ""));
        tenant.put("occupantCount", String.valueOf(src.getOrDefault("occupantCount", "1")));
        tenant.put("motorbikeCount", String.valueOf(src.getOrDefault("motorbikeCount", "0")));
        tenant.put("carCount", String.valueOf(src.getOrDefault("carCount", "0")));
        tenant.put("bankName", getStr(src, "bankName", ""));
        tenant.put("bankAccountNumber", getStr(src, "bankAccountNumber", ""));
        tenant.put("bankAccountHolder", getStr(src, "bankAccountHolder", ""));
        model.put("tenant", tenant);
    }

    private void putPropertyFields(Map<String, Object> model, Map<String, Object> p) {
        Map<String, Object> src = p != null ? p : Map.of();
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("listingCode", getStr(src, "listingCode", ""));
        property.put("rentalScope", getStr(src, "rentalScope", ""));
        property.put("fullAddress", getStr(src, "fullAddress", ""));
        property.put("areaText", getStr(src, "areaText", "").replace("m²", "mét vuông"));
        property.put("propertyType", getStr(src, "propertyType", ""));
        property.put("unitNumber", getStr(src, "unitNumber", "").isBlank() ? "Toàn bộ căn" : getStr(src, "unitNumber", ""));
        property.put("floor", getStr(src, "floor", ""));
        property.put("buildingName", getStr(src, "buildingName", ""));
        property.put("maxOccupants", String.valueOf(src.getOrDefault("maxOccupants", "")));
        property.put("maxVehicles", String.valueOf(src.getOrDefault("maxVehicles", "")));
        model.put("property", property);
    }

    private void putLeaseFields(Map<String, Object> model, Map<String, Object> le) {
        Map<String, Object> src = le != null ? le : Map.of();
        Map<String, Object> lease = new LinkedHashMap<>();
        lease.put("startDateText", getStr(src, "startDateText", ""));
        lease.put("endDateText", getStr(src, "endDateText", ""));
        lease.put("durationMonths", String.valueOf(src.getOrDefault("durationMonths", "12")));
        lease.put("durationText", getStr(src, "durationText", ""));
        lease.put("handoverDateText", getStr(src, "handoverDateText", ""));
        model.put("lease", lease);
    }

    private void putFinancialFields(Map<String, Object> model, Map<String, Object> f) {
        Map<String, Object> src = f != null ? f : Map.of();
        Map<String, Object> rent = new LinkedHashMap<>();
        rent.put("amountNumber", getStr(src, "amountNumber", ""));
        rent.put("amountWords", getStr(src, "amountWords", ""));
        rent.put("paymentCycle", getStr(src, "paymentCycle", "Hàng tháng"));
        rent.put("paymentDueDay", getStr(src, "paymentDueDay", MonthlyBillingSchedule.paymentDueDescription()));
        rent.put("paymentMethod", getStr(src, "paymentMethod", "Chuyển khoản trực tiếp vào tài khoản ngân hàng của Bên A"));
        model.put("rent", rent);

        Map<String, Object> deposit = new LinkedHashMap<>();
        deposit.put("amountNumber", getStr(src, "depositAmountNumber", ""));
        deposit.put("amountWords", getStr(src, "depositAmountWords", ""));
        deposit.put("description", getStr(src, "depositDescription", ""));
        model.put("deposit", deposit);
    }

    private void putMeterFields(Map<String, Object> model, Map<String, Object> m) {
        Map<String, Object> src = m != null ? m : Map.of();
        Map<String, Object> meters = new LinkedHashMap<>();
        meters.put("electricityInitial", getStr(src, "electricityInitial", ""));
        meters.put("waterInitial", getStr(src, "waterInitial", ""));
        model.put("meters", meters);
    }

    private void putInitialPaymentFields(Map<String, Object> model, Map<String, Object> initialPayment, Map<String, Object> financial) {
        Map<String, Object> src = initialPayment != null ? initialPayment : Map.of();
        Map<String, Object> initial = new LinkedHashMap<>();
        initial.put("status", getStr(src, "status", "Khoản chuyển đã được hai bên xác nhận"));
        initial.put("paidAt", getStr(src, "paidAt", ""));
        initial.put("payerReportedAt", getStr(src, "payerReportedAt", ""));
        initial.put("payeeConfirmedAt", getStr(src, "payeeConfirmedAt", ""));
        initial.put("confirmedAt", getStr(src, "confirmedAt", ""));
        initial.put("transferReference", getStr(src, "transferReference", ""));
        initial.put("bankTransactionReference", getStr(src, "bankTransactionReference", ""));
        initial.put("transactionCode", getStr(src, "transactionCode", ""));
        initial.put("monthlyRent", getStr(src, "monthlyRent", getStr(financial, "amountNumber", "")));
        initial.put("monthlyCharges", getStr(src, "monthlyCharges", "0 VNĐ"));
        initial.put("depositAmount", getStr(src, "depositAmount", getStr(financial, "depositAmountNumber", "")));
        initial.put("totalAmount", getStr(src, "totalAmount", ""));
        initial.put("currency", getStr(src, "currency", "VND"));

        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("initial", initial);
        model.put("payment", payment);
    }

    /**
     * Bảng động đặc điểm bất động sản {{#propertyFeaturesTable}}
     */
    @SuppressWarnings("unchecked")
    private TableRenderData buildPropertyFeaturesTable(Map<String, Object> property) {
        RowRenderData header = Rows.of("Đặc điểm", "Giá trị")
                .bgColor("F2F4F7")
                .textColor("1D2939")
                .textBold()
                .create();

        List<RowRenderData> rows = new ArrayList<>();

        if (property != null && property.get("features") instanceof List<?> list && !list.isEmpty()) {
            for (Object obj : list) {
                if (obj instanceof Map<?, ?> map) {
                    String name = String.valueOf(map.get("featureName"));
                    String val = ContractPropertyFeatureLabels.value(name, map.get("featureValue"));
                    if (!val.isBlank() && !"null".equalsIgnoreCase(val)) {
                        rows.add(Rows.of(name, val).create());
                    }
                }
            }
        }

        if (rows.isEmpty() && property != null) {
            // Tự sinh từ các trường chuẩn nếu không có danh sách features chi tiết
            addFeatureRowIfPresent(rows, "Loại hình bất động sản", property.get("propertyType"));
            addFeatureRowIfPresent(rows, "Địa chỉ chi tiết", property.get("fullAddress"));
            addFeatureRowIfPresent(rows, "Diện tích sử dụng", property.get("areaText"));
            addFeatureRowIfPresent(rows, "Số căn / phòng", property.get("unitNumber"));
            addFeatureRowIfPresent(rows, "Tầng", property.get("floor"));
            addFeatureRowIfPresent(rows, "Phạm vi cho thuê", property.get("rentalScope"));
            addFeatureRowIfPresent(rows, "Số người ở tối đa", property.get("maxOccupants"));
            addFeatureRowIfPresent(rows, "Số phương tiện tối đa", property.get("maxVehicles"));
        }

        if (rows.isEmpty()) {
            rows.add(Rows.of("Thông số chi tiết", "Theo hiện trạng bàn giao thực tế").create());
        }

        List<RowRenderData> allRows = new ArrayList<>();
        allRows.add(header);
        allRows.addAll(rows);
        return Tables.create(allRows.toArray(new RowRenderData[0]));
    }

    private void addFeatureRowIfPresent(List<RowRenderData> rows, String label, Object val) {
        if (val != null && !String.valueOf(val).isBlank() && !"null".equalsIgnoreCase(String.valueOf(val))) {
            rows.add(Rows.of(label, String.valueOf(val)).create());
        }
    }

    /**
     * Bảng tiện ích đúng tên được lưu, không suy diễn điều kiện/phạm vi/chi phí.
     */
    private TableRenderData buildAmenitiesTable(List<Map<String, Object>> amenities) {
        RowRenderData header = Rows.of("STT", "Tên tiện ích")
                .bgColor("F2F4F7")
                .textColor("1D2939")
                .textBold()
                .create();

        List<RowRenderData> rows = new ArrayList<>();
        if (amenities != null && !amenities.isEmpty()) {
            int stt = 1;
            for (Map<String, Object> a : amenities) {
                String name = getStr(a, "name", "");
                rows.add(Rows.of(String.valueOf(stt++), name).create());
            }
        } else {
            rows.add(Rows.of("—", "Không có tiện ích được ghi nhận").create());
        }

        List<RowRenderData> allRows = new ArrayList<>();
        allRows.add(header);
        allRows.addAll(rows);
        return Tables.create(allRows.toArray(new RowRenderData[0]));
    }

    /**
     * Bảng động phụ lục thanh toán ban đầu {{#initialPaymentTable}}
     */
    @SuppressWarnings("unchecked")
    private TableRenderData buildInitialPaymentTable(Map<String, Object> initialPayment, Map<String, Object> financial) {
        RowRenderData header = Rows.of("Khoản thanh toán", "Số tiền", "Trạng thái / Ghi chú")
                .bgColor("F2F4F7")
                .textColor("1D2939")
                .textBold()
                .create();

        List<RowRenderData> rows = new ArrayList<>();

        if (initialPayment != null && initialPayment.get("rows") instanceof List<?> list && !list.isEmpty()) {
            for (Object obj : list) {
                if (obj instanceof Map<?, ?> m) {
                    String item = String.valueOf(m.get("itemName"));
                    String amt = String.valueOf(m.get("amountText"));
                    String note = String.valueOf(m.get("note"));
                    rows.add(Rows.of(item, amt, note).create());
                }
            }
        }

        if (rows.isEmpty()) {
            String rent = getStr(initialPayment, "monthlyRent", getStr(financial, "amountNumber", "—"));
            String deposit = getStr(initialPayment, "depositAmount", getStr(financial, "depositAmountNumber", "—"));
            String total = getStr(initialPayment, "totalAmount", "—");
            String paidAt = getStr(initialPayment, "paidAt", "Trước thời điểm lập hợp đồng");

            rows.add(Rows.of("Tiền thuê kỳ đầu", rent, "Đã thanh toán").create());
            rows.add(Rows.of("Tiền đặt cọc", deposit, "Đã thanh toán").create());
            rows.add(Rows.of("Tổng cộng thanh toán", total, "Hoàn tất lúc: " + paidAt).create());
        }

        List<RowRenderData> allRows = new ArrayList<>();
        allRows.add(header);
        allRows.addAll(rows);
        return Tables.create(allRows.toArray(new RowRenderData[0]));
    }

    /**
     * Xây dựng bảng động phí dịch vụ {{#chargesTable}}
     */
    private TableRenderData buildChargesTable(List<Map<String, Object>> charges) {
        RowRenderData header = Rows.of("Khoản phí dịch vụ", "Mức phí / Cách tính", "Ghi chú & Thỏa thuận")
                .bgColor("F2F4F7")
                .textColor("1D2939")
                .textBold()
                .create();

        List<RowRenderData> rows = new ArrayList<>();
        if (charges != null && !charges.isEmpty()) {
            for (Map<String, Object> c : charges) {
                String name = getStr(c, "name", "Phí dịch vụ");
                String method = getStr(c, "amountAndMethod", "-");
                String note = getStr(c, "note", "-");
                rows.add(Rows.of(name, method, note).create());
            }
        } else {
            rows.add(Rows.of("Chưa ghi nhận khoản phí dịch vụ", "—", "Theo các khoản đã thỏa thuận; không tự suy ra miễn phí").create());
        }

        List<RowRenderData> allRows = new ArrayList<>();
        allRows.add(header);
        allRows.addAll(rows);
        return Tables.create(allRows.toArray(new RowRenderData[0]));
    }

    /**
     * Xây dựng bảng động biên bản bàn giao trang thiết bị {{#equipmentTable}}
     */
    private TableRenderData buildEquipmentTable(List<Map<String, Object>> equipments) {
        RowRenderData header = Rows.of("STT", "Tên tài sản / Trang thiết bị", "Số lượng", "Hiện trạng bàn giao")
                .bgColor("F2F4F7")
                .textColor("1D2939")
                .textBold()
                .create();

        List<RowRenderData> rows = new ArrayList<>();
        if (equipments != null && !equipments.isEmpty()) {
            int stt = 1;
            for (Map<String, Object> eq : equipments) {
                String name = getStr(eq, "name", "");
                String quantity = String.valueOf(eq.getOrDefault("quantity", "1"));
                String condition = getStr(eq, "condition", "Tốt");
                String note = getStr(eq,"note","").trim();
                if (!note.isEmpty() && !condition.contains(note)) condition += " — " + note;
                rows.add(Rows.of(String.valueOf(stt++), name, quantity, condition).create());
            }
        } else {
            rows.add(Rows.of("—", "Chưa ghi nhận tài sản bàn giao", "—", "Đối chiếu biên bản bàn giao thực tế").create());
        }

        List<RowRenderData> allRows = new ArrayList<>();
        allRows.add(header);
        allRows.addAll(rows);
        return Tables.create(allRows.toArray(new RowRenderData[0]));
    }

    @SuppressWarnings("unchecked")
    private TableRenderData buildPoliciesTable(Map<String,Object> policies) {
        var rows = new ArrayList<RowRenderData>();
        rows.add(Rows.of("Chính sách","Quy định áp dụng").bgColor("F2F4F7").textColor("1D2939").textBold().create());
        if (policies != null && policies.get("listingPolicies") instanceof List<?> source && !source.isEmpty()) {
            for (var item : source) { var row = (Map<String,Object>)item; rows.add(Rows.of(getStr(row,"label",""),getStr(row,"displayValue","")).create()); }
        } else rows.add(Rows.of("Chưa có bản chụp chính sách bài đăng","Đối chiếu điều khoản đã ký; không tự bổ sung quyền hoặc hạn chế").create());
        return Tables.create(rows.toArray(new RowRenderData[0]));
    }

    private String getStr(Map<String, Object> map, String key, String defaultVal) {
        if (map == null) return defaultVal;
        Object val = map.get(key);
        return val != null ? String.valueOf(val) : defaultVal;
    }

    public static String formatVND(BigDecimal amount) {
        if (amount == null) return "0 VNĐ";
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.of("vi", "VN"));
        symbols.setGroupingSeparator('.');
        DecimalFormat df = new DecimalFormat("#,###", symbols);
        return df.format(amount) + " VNĐ";
    }
}
