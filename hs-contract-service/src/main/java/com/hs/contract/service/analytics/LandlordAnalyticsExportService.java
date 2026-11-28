package com.hs.contract.service.analytics;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.analytics.*;
import com.hs.contract.service.analytics.LandlordAnalyticsQueries.Scope;
import com.hs.payment.receipt.PaymentReceiptPdfRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.client.RestClientException;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service @RequiredArgsConstructor @Slf4j
public class LandlordAnalyticsExportService {
    public static final int MAX_ROWS = 20_000;
    private final LandlordAnalyticsService analytics;
    private final LandlordAnalyticsQueries queries;
    private final PaymentReceiptPdfRenderer pdf;
    public record Export(String filename, String contentType, byte[] bytes) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Export export(String owner, LandlordAnalyticsQuery q, String report, String format, String granularity) {
        if (!LandlordAnalyticsQuery.SECTIONS.contains(report) || !Set.of("csv", "pdf").contains(format)
                || !Set.of("receipt", "month").contains(granularity)
                || !report.equals("revenue") && !granularity.equals("receipt")) throw new AppException(ErrorCode.INVALID_REQUEST);
        var data = analytics.analytics(owner, new LandlordAnalyticsQuery(q.from(), q.to(), q.branchId(), q.listingId(),
                Set.of(report), 1, 1, q.noLeadsOnly()));
        YearMonth from = YearMonth.parse(data.from()), to = YearMonth.parse(data.to());
        Instant start = from.atDay(1).atStartOfDay(BillingTime.ZONE).toInstant();
        Instant end = to.plusMonths(1).atDay(1).atStartOfDay(BillingTime.ZONE).toInstant();
        var scope = new Scope(owner, q.branchId(), q.listingId());
        List<String> headers;
        List<List<Object>> rows = new ArrayList<>();
        switch (report) {
            case "revenue" -> {
                if (granularity.equals("month")) {
                    headers = List.of("Tháng", "Đã nhận (VND)", "Hoàn tiền (VND)", "Phải thu đến hạn (VND)",
                            "Đã thu của kỳ đến hạn (VND)", "Thu trễ kỳ trước (VND)", "Tỉ lệ thu (%)", "Phí chậm (VND)", "Nợ chuyển kỳ (VND)", "Phí hội viên (VND)");
                    data.revenue().forEach(r -> rows.add(row(r.month(), r.receivedVnd(), r.refundedVnd(), r.dueVnd(),
                            r.dueCollectedVnd(), r.lateCollectedVnd(), percent(r.dueCollectedVnd(), r.dueVnd()), r.lateFeeVnd(), r.rolledOverVnd(), r.membershipFeeVnd())));
                } else {
                    headers = List.of("Số phiếu", "Ngày phát hành", "Chi nhánh", "Phòng", "Số hợp đồng", "Người thuê",
                            "Loại khoản", "Kỳ thanh toán", "Số tiền (VND)", "Tiền cọc loại trừ (VND)", "Mã chuyển khoản");
                    rows.addAll(queries.receiptExport(scope, start, end, MAX_ROWS + 1));
                    checkSize(rows.size());
                    BigDecimal total = rows.stream().map(r -> (BigDecimal) r.get(8)).reduce(BigDecimal.ZERO, BigDecimal::add);
                    rows.add(row("Tổng", "", "", "", "", "", "", "", total, "", ""));
                }
            }
            case "receivables" -> {
                headers = List.of("Chi nhánh", "Phòng", "Số hợp đồng", "Người thuê", "Hạn thanh toán", "Số ngày trễ", "Nhóm tuổi nợ", "Số tiền (VND)", "Trạng thái", "Mã chuyển khoản");
                rows.addAll(queries.debtExport(scope, data.billingNow(), LocalDate.ofInstant(data.billingNow(), BillingTime.ZONE), MAX_ROWS + 1));
                checkSize(rows.size());
                rows.forEach(r -> r.add(6, LandlordAnalyticsService.bucket(((Number) r.get(5)).intValue())));
            }
            case "occupancy" -> {
                headers = List.of("Tháng", "Chi nhánh", "Số phòng", "Ngày-phòng có người ở", "Tổng ngày-phòng", "Tỉ lệ lấp đầy (%)");
                data.occupancy().forEach(r -> rows.add(row(r.month(), q.branchId() == null ? "Tất cả" : q.branchId(),
                        r.rooms(), r.occupiedRoomDays(), r.roomDays(), percent(BigDecimal.valueOf(r.occupiedRoomDays()), BigDecimal.valueOf(r.roomDays())))));
            }
            case "listings" -> {
                headers = List.of("Tin", "Chi nhánh", "Trạng thái", "Ngày đăng", "Ngày hết hạn", "Lượt xem tích lũy", "Lượt xem trong khoảng", "Hẹn xem", "Yêu cầu thuê", "Hợp đồng", "Lượt lưu", "Chưa có khách", "Tỉ lệ xem→yêu cầu (%)");
                if (data.listings().total() > MAX_ROWS) checkSize(MAX_ROWS + 1);
                analytics.listings(scope, from, to, start, end, 1, MAX_ROWS, q.noLeadsOnly()).items().forEach(r -> rows.add(row(r.title(), r.branchName(), r.status(), r.publishedAt(), r.expiresAt(), r.viewCountTotal(), r.viewsInRange(), r.appointments(), r.requests(), r.contracts(), r.favorites(), r.noLeads(), percent(BigDecimal.valueOf(r.requests()), BigDecimal.valueOf(r.viewCountTotal())))));
            }
            case "funnel" -> {
                headers = List.of("Bước", "Số lượng", "Tỉ lệ so với bước trước (%)");
                var f = data.funnel();
                rows.add(row(f.views().cumulative() ? "Lượt xem tích lũy" : "Lượt xem trong khoảng", f.views().value(), null));
                rows.add(row("Hẹn xem", f.appointments(), percent(BigDecimal.valueOf(f.appointments()), BigDecimal.valueOf(f.views().value()))));
                rows.add(row("Yêu cầu thuê", f.requests(), percent(BigDecimal.valueOf(f.requests()), BigDecimal.valueOf(f.appointments()))));
                rows.add(row("Hợp đồng", f.contracts(), percent(BigDecimal.valueOf(f.contracts()), BigDecimal.valueOf(f.requests()))));
            }
            default -> throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        byte[] bytes;
        if (format.equals("pdf")) {
            StringBuilder html = new StringBuilder("<!doctype html><html lang=vi><meta charset=UTF-8><style>@page{size:A4 landscape}body{font:11px sans-serif}table{border-collapse:collapse}td,th{border:1px solid #ccc;padding:5px}</style><h1>HomeSpace — ")
                    .append(report).append("</h1><p>").append(data.from()).append(" → ").append(data.to())
                    .append(" · ").append(stamp(data.billingNow())).append(" · ").append(rows.size()).append(" dòng</p><p>Chi nhánh: ")
                    .append(HtmlUtils.htmlEscape(Objects.toString(q.branchId(), "Tất cả"))).append(" · Tin: ")
                    .append(HtmlUtils.htmlEscape(Objects.toString(q.listingId(), "Tất cả"))).append("</p><table><tr>");
            headers.forEach(h -> html.append("<th>").append(HtmlUtils.htmlEscape(h)).append("</th>"));
            html.append("</tr>");
            rows.forEach(r -> { html.append("<tr>"); r.forEach(v -> html.append("<td>").append(HtmlUtils.htmlEscape(text(v))).append("</td>")); html.append("</tr>"); });
            try { bytes = pdf.renderHtml(html.append("</table></html>").toString()); }
            catch (IllegalStateException | RestClientException e) { throw new AppException(503, "Chưa hỗ trợ xuất PDF", HttpStatus.SERVICE_UNAVAILABLE); }
        } else bytes = csv(headers, rows);
        log.info("LANDLORD_ANALYTICS_EXPORT user={} report={} from={} to={} rows={}", owner, report, data.from(), data.to(), rows.size());
        return new Export("homespace-" + report + "-" + data.from() + "_" + data.to() + "." + format,
                format.equals("pdf") ? "application/pdf" : "text/csv;charset=UTF-8", bytes);
    }

    static void checkSize(int count) {
        if (count > MAX_ROWS) throw new AppException(422, "Thu hẹp khoảng thời gian", HttpStatus.UNPROCESSABLE_ENTITY);
    }
    private static List<Object> row(Object... values) { return new ArrayList<>(Arrays.asList(values)); }
    private static BigDecimal percent(BigDecimal part, BigDecimal total) {
        return total.signum() == 0 ? null : part.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
    }
    static byte[] csv(List<String> headers, List<List<Object>> rows) {
        StringBuilder out = new StringBuilder("\uFEFF");
        out.append(headers.stream().map(LandlordAnalyticsExportService::cell).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        rows.forEach(r -> out.append(r.stream().map(LandlordAnalyticsExportService::cell).collect(java.util.stream.Collectors.joining(","))).append("\r\n"));
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static String cell(Object value) {
        String s = text(value);
        String stripped = s.stripLeading();
        if (!(value instanceof Number) && (!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0
                || s.startsWith("\t") || s.startsWith("\r") || s.startsWith("\n"))) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
    private static String stamp(Instant value) { return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(BillingTime.ZONE).format(value); }
    private static String text(Object value) {
        if (value == null) return "";
        if (value instanceof Timestamp t) return stamp(t.toInstant());
        if (value instanceof OffsetDateTime t) return stamp(t.toInstant());
        if (value instanceof Instant t) return stamp(t);
        if (value instanceof LocalDate d) return d.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        if (value instanceof BigDecimal d) return d.toPlainString();
        return value.toString();
    }
}
