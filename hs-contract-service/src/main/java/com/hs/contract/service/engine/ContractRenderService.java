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
     * Chuẩn bị data model hoàn chỉnh cho poi-tl từ các trường snapshot của ContractRevision
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
        Map<String, Object> model = new HashMap<>();

        // poi-tl resolves {{landlord.fullName}} as nested maps, not flat "landlord.fullName" keys.
        putLandlordFields(model, landlord);
        putTenantFields(model, tenant);
        putPropertyFields(model, property);
        putLeaseFields(model, lease);
        putFinancialFields(model, financial);
        putMeterFields(model, meters);

        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("number", contractNumber != null ? contractNumber : "HD-" + System.currentTimeMillis());
        contract.put("signingDate", signingDate != null ? signingDate.format(DATE_FORMATTER) : LocalDate.now().format(DATE_FORMATTER));
        contract.put("signingCity", signingCity != null ? signingCity : "Thành phố Hồ Chí Minh");
        model.put("contract", contract);

        model.put("chargesTable", buildChargesTable(charges));
        model.put("equipmentTable", buildEquipmentTable(equipments));

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
        tenant.put("organizationName", "Công ty TNHH Sáng Tạo Trẻ");
        tenant.put("representativeName", "Trần Thị Bình");
        tenant.put("representativePosition", "Giám Đốc");

        Map<String, Object> property = Map.of(
                "fullAddress", "Tầng 12, Căn hộ A12-08, Tòa tháp Landmark, 720A Điện Biên Phủ, Phường 22, Quận Bình Thạnh, TP.HCM",
                "areaText", "75 m²",
                "propertyType", "Căn hộ chung cư cao cấp",
                "unitNumber", "A12-08",
                "floor", "Tầng 12"
        );

        Map<String, Object> lease = Map.of(
                "rentalMode", "Thuê nguyên căn / toàn bộ",
                "startDateText", "15/09/2026",
                "endDateText", "14/09/2027",
                "durationMonths", 12,
                "durationText", "1 năm (12 tháng)",
                "handoverDateText", "15/09/2026"
        );

        Map<String, Object> financial = Map.of(
                "amountNumber", "15.000.000 VNĐ/tháng",
                "amountWords", "Mười lăm triệu đồng chẵn",
                "paymentCycle", "Hàng tháng",
                "paymentDueDay", "Từ ngày 01 đến ngày 05 hàng tháng",
                "paymentMethod", "Thanh toán trực tuyến qua hệ thống HomeSpace",
                "depositAmountNumber", "15.000.000 VNĐ",
                "depositAmountWords", "Mười lăm triệu đồng chẵn",
                "depositDescription", "Tiền đặt cọc tương đương 01 tháng tiền thuê nhà. Khoản tiền cọc này được bên A hoàn trả đầy đủ cho bên B ngay sau khi chấm dứt hợp đồng sau khi đã khấu trừ các chi phí sinh hoạt phát sinh chưa thanh toán (nếu có)."
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
                "HD-20260905-DEMO", LocalDate.now(), "Thành phố Hồ Chí Minh"
        );
    }

    private void putLandlordFields(Map<String, Object> model, Map<String, Object> l) {
        Map<String, Object> src = l != null ? l : Map.of();
        Map<String, Object> landlord = new LinkedHashMap<>();
        landlord.put("fullName", getStr(src, "fullName", ""));
        landlord.put("idNumber", getStr(src, "idNumber", ""));
        // Backward-compat for older Word templates that still have these tags.
        landlord.put("idIssueDate", getStr(src, "idIssueDate", ""));
        landlord.put("idIssuePlace", getStr(src, "idIssuePlace", ""));
        landlord.put("permanentAddress", getStr(src, "permanentAddress", ""));
        landlord.put("phone", getStr(src, "phone", ""));
        landlord.put("email", getStr(src, "email", ""));
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
        tenant.put("organizationName", getStr(src, "organizationName", ""));
        tenant.put("representativeName", getStr(src, "representativeName", ""));
        tenant.put("representativePosition", getStr(src, "representativePosition", ""));
        model.put("tenant", tenant);
    }

    private void putPropertyFields(Map<String, Object> model, Map<String, Object> p) {
        Map<String, Object> src = p != null ? p : Map.of();
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("fullAddress", getStr(src, "fullAddress", ""));
        property.put("areaText", getStr(src, "areaText", ""));
        property.put("propertyType", getStr(src, "propertyType", ""));
        property.put("unitNumber", getStr(src, "unitNumber", ""));
        property.put("floor", getStr(src, "floor", ""));
        model.put("property", property);
    }

    private void putLeaseFields(Map<String, Object> model, Map<String, Object> le) {
        Map<String, Object> src = le != null ? le : Map.of();
        Map<String, Object> lease = new LinkedHashMap<>();
        lease.put("rentalMode", getStr(src, "rentalMode", ""));
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
        rent.put("paymentCycle", getStr(src, "paymentCycle", ""));
        rent.put("paymentDueDay", getStr(src, "paymentDueDay", ""));
        rent.put("paymentMethod", getStr(src, "paymentMethod", ""));
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
