package com.hs.contract.service.engine;

import com.deepoove.poi.XWPFTemplate;
import com.deepoove.poi.data.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        try (XWPFTemplate template = XWPFTemplate.compile(templateInputStream).render(dataModel)) {
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

        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("number", contractNumber != null ? contractNumber : "HD-" + System.currentTimeMillis());
        contract.put("signingDate", signingDate != null ? signingDate.format(DATE_FORMATTER) : LocalDate.now().format(DATE_FORMATTER));
        contract.put("signingCity", signingCity != null ? signingCity : "Thành phố Hồ Chí Minh");
        contract.put("schemaVersion", schemaVersion != null ? String.valueOf(schemaVersion) : "2");
        contract.put("revisionNumber", revisionNumber != null ? String.valueOf(revisionNumber) : "1");
        contract.put("specialTerms", specialTerms != null ? specialTerms : "");
        model.put("contract", contract);

        // Bảng động
        model.put("chargesTable", buildChargesTable(charges));
        model.put("equipmentTable", buildEquipmentTable(equipments));
        model.put("propertyFeaturesTable", buildPropertyFeaturesTable(property));
        model.put("amenitiesTable", buildAmenitiesTable(amenities));
        model.put("initialPaymentTable", buildInitialPaymentTable(initialPayment, financial));

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
        property.put("areaText", "75 m²");
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
                "paymentDueDay", "Từ ngày 01 đến ngày 05 hàng tháng",
                "paymentMethod", "Thanh toán trực tuyến qua hệ thống HomeSpace",
                "depositAmountValue", "15000000",
                "depositAmountNumber", "15.000.000 VNĐ",
                "depositAmountWords", "Mười lăm triệu đồng chẵn",
                "depositDescription", "Tiền đặt cọc tương đương 01 tháng tiền thuê nhà. Khoản tiền cọc này được bên A hoàn trả đầy đủ cho bên B ngay sau khi chấm dứt hợp đồng sau khi đã khấu trừ các chi phí sinh hoạt phát sinh chưa thanh toán (nếu có)."
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
                Map.of("index", 1, "code", "WIFI", "name", "Internet Wifi tốc độ cao", "scope", "Riêng trong căn/phòng/nhà", "costText", "Đã bao gồm trong giá thuê", "conditionText", "Gói cước 150 Mbps"),
                Map.of("index", 2, "code", "PARKING", "name", "Chỗ để xe máy", "scope", "Dùng chung", "costText", "120.000 VNĐ / xe / tháng", "conditionText", "Đăng ký 01 xe máy với ban quản lý"),
                Map.of("index", 3, "code", "ELEVATOR", "name", "Thang máy thẻ từ", "scope", "Dùng chung", "costText", "Đã bao gồm trong giá thuê", "conditionText", "Theo nội quy tòa nhà"),
                Map.of("index", 4, "code", "PETS_ALLOWED", "name", "Được phép nuôi thú cưng", "scope", "Riêng trong căn/phòng/nhà", "costText", "Miễn phí", "conditionText", "Bên B chịu trách nhiệm giữ gìn vệ sinh, tiếng ồn và bồi thường thiệt hại nếu có")
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
                initialPayment, amenities, Map.of(), "Bên B giữ gìn an ninh trật tự sau 23h.", 2, 1,
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
        property.put("areaText", getStr(src, "areaText", ""));
        property.put("propertyType", getStr(src, "propertyType", ""));
        property.put("unitNumber", getStr(src, "unitNumber", ""));
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
        rent.put("paymentDueDay", getStr(src, "paymentDueDay", "Từ ngày 01 đến ngày 05 hàng tháng"));
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
                    String val = String.valueOf(map.get("featureValue"));
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
     * Bảng động tiện ích & quyền sử dụng {{#amenitiesTable}}
     */
    private TableRenderData buildAmenitiesTable(List<Map<String, Object>> amenities) {
        RowRenderData header = Rows.of("STT", "Tiện ích / Quyền sử dụng", "Phạm vi", "Chi phí", "Điều kiện / Ghi chú")
                .bgColor("F2F4F7")
                .textColor("1D2939")
                .textBold()
                .create();

        List<RowRenderData> rows = new ArrayList<>();
        if (amenities != null && !amenities.isEmpty()) {
            int stt = 1;
            for (Map<String, Object> a : amenities) {
                String name = getStr(a, "name", "");
                String scope = getStr(a, "scope", "Riêng trong căn/phòng/nhà");
                String cost = getStr(a, "costText", "Đã bao gồm trong giá thuê");
                String condition = getStr(a, "conditionText", "Theo nội quy sử dụng");
                rows.add(Rows.of(String.valueOf(stt++), name, scope, cost, condition).create());
            }
        } else {
            rows.add(Rows.of("1", "Tiện ích cơ bản theo tài sản thuê", "Theo phạm vi bàn giao", "Đã bao gồm trong giá thuê", "Sử dụng đúng mục đích").create());
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
            rows.add(Rows.of("Không phát sinh phí dịch vụ khác", "Đã bao gồm trong giá thuê", "Không thu riêng").create());
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
                rows.add(Rows.of(String.valueOf(stt++), name, quantity, condition).create());
            }
        } else {
            rows.add(Rows.of("1", "Nhà trống bàn giao cơ bản", "1", "Nguyên trạng lúc nhận nhà").create());
        }

        List<RowRenderData> allRows = new ArrayList<>();
        allRows.add(header);
        allRows.addAll(rows);
        return Tables.create(allRows.toArray(new RowRenderData[0]));
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
