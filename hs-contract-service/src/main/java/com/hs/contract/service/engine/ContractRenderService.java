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

        // 1. Bên cho thuê (landlord)
        putLandlordFields(model, landlord);

        // 2. Bên thuê (tenant)
        putTenantFields(model, tenant);

        // 3. Bất động sản (property)
        putPropertyFields(model, property);

        // 4. Thời hạn thuê (lease)
        putLeaseFields(model, lease);

        // 5. Giá thuê & Cọc (financial)
        putFinancialFields(model, financial);

        // 6. Chỉ số công tơ điện nước (meters)
        putMeterFields(model, meters);

        // 7. Thông tin chung hợp đồng
        model.put("contract.number", contractNumber != null ? contractNumber : "HD-" + System.currentTimeMillis());
        model.put("contract.signingDate", signingDate != null ? signingDate.format(DATE_FORMATTER) : LocalDate.now().format(DATE_FORMATTER));
        model.put("contract.signingCity", signingCity != null ? signingCity : "Thành phố Hồ Chí Minh");

        // 8. Bảng phí dịch vụ động {{#chargesTable}}
        model.put("chargesTable", buildChargesTable(charges));

        // 9. Bảng trang thiết bị bàn giao {{#equipmentTable}}
        model.put("equipmentTable", buildEquipmentTable(equipments));

        return model;
    }

    /**
     * Sinh data model giả lập (Dummy Data) dùng cho Admin xem trước mẫu Word
     */
    public Map<String, Object> buildDummyDataModel() {
        Map<String, Object> landlord = Map.of(
                "fullName", "Nguyễn Văn An (Chủ nhà)",
                "idNumber", "079090001234",
                "idIssueDate", "15/05/2021",
                "idIssuePlace", "Cục Cảnh sát QLHC về TTXH",
                "permanentAddress", "123 Nguyễn Huệ, Phường Bến Nghé, Quận 1, TP.HCM",
                "phone", "0901234567",
                "email", "nguyenvanan.landlord@example.com",
                "bankAccount", "19031234567890",
                "bankName", "Techcombank - CN Sài Gòn"
        );

        Map<String, Object> tenant = new LinkedHashMap<>();
        tenant.put("fullName", "Trần Thị Bình (Người thuê)");
        tenant.put("idNumber", "079195009876");
        tenant.put("idIssueDate", "20/08/2022");
        tenant.put("idIssuePlace", "Cục Cảnh sát QLHC về TTXH");
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
                "paymentMethod", "Chuyển khoản ngân hàng",
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
        if (l == null) return;
        model.put("landlord.fullName", getStr(l, "fullName", ""));
        model.put("landlord.idNumber", getStr(l, "idNumber", ""));
        model.put("landlord.idIssueDate", getStr(l, "idIssueDate", ""));
        model.put("landlord.idIssuePlace", getStr(l, "idIssuePlace", ""));
        model.put("landlord.permanentAddress", getStr(l, "permanentAddress", ""));
        model.put("landlord.phone", getStr(l, "phone", ""));
        model.put("landlord.email", getStr(l, "email", ""));
        model.put("landlord.bankAccount", getStr(l, "bankAccount", ""));
        model.put("landlord.bankName", getStr(l, "bankName", ""));
    }

    private void putTenantFields(Map<String, Object> model, Map<String, Object> t) {
        if (t == null) return;
        model.put("tenant.fullName", getStr(t, "fullName", ""));
        model.put("tenant.idNumber", getStr(t, "idNumber", ""));
        model.put("tenant.idIssueDate", getStr(t, "idIssueDate", ""));
        model.put("tenant.idIssuePlace", getStr(t, "idIssuePlace", ""));
        model.put("tenant.permanentAddress", getStr(t, "permanentAddress", ""));
        model.put("tenant.phone", getStr(t, "phone", ""));
        model.put("tenant.email", getStr(t, "email", ""));
        model.put("tenant.occupantCount", String.valueOf(t.getOrDefault("occupantCount", "1")));
        model.put("tenant.organizationName", getStr(t, "organizationName", ""));
        model.put("tenant.representativeName", getStr(t, "representativeName", ""));
        model.put("tenant.representativePosition", getStr(t, "representativePosition", ""));
    }

    private void putPropertyFields(Map<String, Object> model, Map<String, Object> p) {
        if (p == null) return;
        model.put("property.fullAddress", getStr(p, "fullAddress", ""));
        model.put("property.areaText", getStr(p, "areaText", ""));
        model.put("property.propertyType", getStr(p, "propertyType", ""));
        model.put("property.unitNumber", getStr(p, "unitNumber", ""));
        model.put("property.floor", getStr(p, "floor", ""));
    }

    private void putLeaseFields(Map<String, Object> model, Map<String, Object> le) {
        if (le == null) return;
        model.put("lease.startDateText", getStr(le, "startDateText", ""));
        model.put("lease.endDateText", getStr(le, "endDateText", ""));
        model.put("lease.durationMonths", String.valueOf(le.getOrDefault("durationMonths", "12")));
        model.put("lease.durationText", getStr(le, "durationText", ""));
        model.put("lease.handoverDateText", getStr(le, "handoverDateText", ""));
    }

    private void putFinancialFields(Map<String, Object> model, Map<String, Object> f) {
        if (f == null) return;
        model.put("rent.amountNumber", getStr(f, "amountNumber", ""));
        model.put("rent.amountWords", getStr(f, "amountWords", ""));
        model.put("rent.paymentCycle", getStr(f, "paymentCycle", ""));
        model.put("rent.paymentDueDay", getStr(f, "paymentDueDay", ""));
        model.put("rent.paymentMethod", getStr(f, "paymentMethod", ""));
        model.put("deposit.amountNumber", getStr(f, "depositAmountNumber", ""));
        model.put("deposit.amountWords", getStr(f, "depositAmountWords", ""));
        model.put("deposit.description", getStr(f, "depositDescription", ""));
    }

    private void putMeterFields(Map<String, Object> model, Map<String, Object> m) {
        if (m == null) return;
        model.put("meters.electricityInitial", getStr(m, "electricityInitial", ""));
        model.put("meters.waterInitial", getStr(m, "waterInitial", ""));
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
